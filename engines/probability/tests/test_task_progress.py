"""任务进度、历史资格、失败统计及预测归属的回归测试。

所有行情均为内存构造，数据库和模型训练均使用模拟对象；本测试不读取本机密码，
不连接服务，不启动真实模型，也不读写正在使用的断点或模型产物。
"""
from __future__ import annotations

import json
import logging
import tempfile
import unittest
from contextlib import ExitStack
from datetime import date, timedelta
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, Mock, patch

import numpy as np
import pandas as pd

from engine.db import Db
from engine.pipeline import Pipeline
from engine.predictor import predict_cross_section, prediction_keys
from engine.store import ProbStore


class TestProgressStorage(unittest.TestCase):
    """进度只能补充运行中任务，不能清空未指定字段或覆盖已完成状态。"""

    def test_update_is_conditional_preserves_counts_and_redacts_message(self):
        db = Mock()
        store = ProbStore(db, {})
        day = date(2026, 11, 16)
        store.update_progress(3, "TRAIN", asof=day, n_universe=1356,
                              message="诊断 mysql+pymysql://fake_user:fake_password@localhost/db")
        table, values, where, params = db.update.call_args.args
        self.assertEqual(table, "prob_run")
        self.assertEqual(params, (3,))
        self.assertIn("status='RUNNING'", where)
        self.assertEqual(values["asof_date"], day)
        self.assertEqual(values["n_universe"], 1356)
        self.assertNotIn("fake_user", values["message"])
        self.assertNotIn("fake_password", values["message"])
        for name in ("n_ok", "n_fail", "finished_at", "status", "config_json"):
            self.assertNotIn(name, values)


class TestHistoricalEligibility(unittest.TestCase):
    """用门槛边界和未来数据验证按预测日资格检查，防止后视偏差与空波动率异常。"""

    def setUp(self):
        days = [date(2025, 1, 1) + timedelta(days=i) for i in range(300)]
        keys = ["000001.SZ", "001221.SZ"]
        valid = pd.DataFrame(True, index=days, columns=keys)
        valid.loc[days[:100], keys[1]] = False
        self.md = SimpleNamespace(dates=days, universe=keys, valid=valid,
                                  excluded={"min_history": 0}, warn_count=0, completeness=1.0)
        self.cfg = {"min_history": 250, "horizons": [1, 3, 5, 10]}

    def test_threshold_uses_only_history_at_prediction_date(self):
        self.assertEqual(prediction_keys(self.md, self.cfg, 248, "OOS"), ([], 2))
        self.assertEqual(prediction_keys(self.md, self.cfg, 249, "OOS"), (["000001.SZ"], 1))
        self.assertEqual(prediction_keys(self.md, self.cfg, 299, "OOS"), (["000001.SZ"], 1))
        # LIVE 继续使用准备阶段已选定的股票池；本修复不改实时预测算法或配置门槛。
        self.assertEqual(prediction_keys(self.md, self.cfg, 299, "LIVE"), (self.md.universe, 0))

    def test_empty_eligible_pool_never_calls_models_or_features(self):
        fs, progress = Mock(), Mock()
        with patch("engine.predictor.fit_volatility") as fit:
            rows, states, stats = predict_cross_section(
                self.md, fs, {}, self.cfg, 248, "OOS", "test", 3, progress=progress)
        self.assertEqual((rows, states), ([], []))
        self.assertEqual(stats["n_fail"], 0)
        self.assertEqual(stats["excluded"]["oos_min_history"], 2)
        self.assertNotIn("oos_min_history", self.md.excluded)
        fs.cross_section.assert_not_called()
        fit.assert_not_called()
        self.assertEqual(progress.call_args.args[0]["total"], 0)

    def test_explicit_empty_keys_do_not_expand_to_full_universe(self):
        self.assertEqual(prediction_keys(self.md, self.cfg, 299, "LIVE", []), ([], 0))

    def test_failure_progress_is_throttled_and_includes_final_count(self):
        # 使用不足 60 个收益率触发现有失败分支，验证 continue 也能推动进度。
        # 整个测试不拟合任何模型；固定时钟排除机器速度对节流断言的影响。
        keys = [f"{i:06d}.SZ" for i in range(57)]
        md = SimpleNamespace(universe=keys, dates=[date(2026, 1, 1)] * 59,
                             arr={"ret_1": pd.DataFrame(0.01, index=range(59), columns=keys)},
                             code_of=lambda key: key[:6], market_of=lambda key: key[-2:],
                             excluded={}, warn_count=0, completeness=1.0)
        fs = SimpleNamespace(frames={}, cross_section=lambda *args: pd.DataFrame(index=keys))
        progress = Mock()
        with patch("engine.predictor.signal_columns", return_value={}), \
                patch("engine.predictor.time.monotonic", return_value=0.0):
            _, _, stats = predict_cross_section(
                md, fs, {1: SimpleNamespace(m2=None, m6=None)}, {"horizons": [1]},
                58, "LIVE", "test", 3, progress=progress)
        self.assertEqual(stats["n_fail"], 57)
        self.assertEqual([call.args[0]["processed"] for call in progress.call_args_list],
                         [0, 25, 50, 57])
        self.assertEqual(progress.call_args.args[0]["n_fail"], 57)


class TestPredictionOwnership(unittest.TestCase):
    """同一预测主键重算时更新来源任务；结算与其他表不得改动 run_id。"""

    def sql_for(self, table, columns, updates):
        db = object.__new__(Db)
        connection = MagicMock()
        db.connect = Mock(return_value=connection)
        cursor = connection.__enter__.return_value.cursor.return_value.__enter__.return_value
        db.upsert(table, [{"run_id": 3, "p_up": 0.5, "status": "SETTLED"}], columns, updates)
        return cursor.executemany.call_args.args[0].split(" ON DUPLICATE KEY UPDATE ", 1)[-1]

    def test_recomputed_prediction_updates_run_but_run_table_does_not(self):
        self.assertIn("`run_id`=VALUES(`run_id`)", self.sql_for(
            "prob_prediction", ["run_id", "p_up"], ["run_id", "p_up"]))
        self.assertNotIn("`run_id`=VALUES(`run_id`)", self.sql_for(
            "prob_run", ["run_id", "status"], ["run_id", "status"]))

    def test_settlement_does_not_overwrite_prediction_owner(self):
        self.assertNotIn("run_id", self.sql_for("prob_prediction", ["status"], ["status"]))
        self.assertNotIn("`run_id`=VALUES(`run_id`)", self.sql_for(
            "prob_prediction", ["run_id", "status"], ["status"]))


class TestPipelineAccounting(unittest.TestCase):
    """通过完整编排的模拟边界验证失败汇总、真实基准日及父子任务进度透传。"""

    def make_pipeline(self):
        pipe = object.__new__(Pipeline)
        days = [date(2026, 1, 1) + timedelta(days=i) for i in range(5)]
        md = SimpleNamespace(asof=days[-1], dates=days, universe=["000001.SZ", "000002.SZ"],
                             excluded={}, warn_count=0, date_pos=lambda _: 4)
        pipe.cfg = {"horizons": [1], "retrain_every": 20, "walkforward": {}, "warmup_days": 0}
        pipe.version, pipe.db, pipe.store = "test", Mock(), Mock()
        pipe.store.start_run.return_value = 3
        pipe.logger = logging.getLogger("progress-test")
        pipe.prepare = Mock(return_value=(md, None))
        pipe.settle_all = Mock(return_value=0)
        pipe.period_grades = Mock(return_value={})
        pipe._train_all = Mock(return_value={1: SimpleNamespace(n_samples=10)})
        return pipe, md

    def test_walkforward_counts_failures_and_never_checkpoints_failed_block(self):
        pipe, md = self.make_pipeline()
        blocks = [{"test_start": 1, "test_end": 1}, {"test_start": 2, "test_end": 2}]
        parent = Mock()
        # 第一个块完整，第二个块有真实失败。只能保留第一个块的可恢复断点。
        results = [([{}], [], {"n_fail": 0, "excluded": {"oos_min_history": 1}}),
                   ([{}], [], {"n_fail": 2, "excluded": {"oos_min_history": 0}})]
        with tempfile.TemporaryDirectory() as folder, ExitStack() as stack:
            pipe.cfg["walkforward"]["checkpoint_file"] = str(Path(folder) / "checkpoint.json")
            stack.enter_context(patch("engine.pipeline.walkforward_blocks", return_value=blocks))
            stack.enter_context(patch("engine.pipeline.data_signature", return_value="safe-signature"))
            stack.enter_context(patch("engine.pipeline.train_fusion_from_oos"))
            stack.enter_context(patch("engine.pipeline.predict_cross_section", side_effect=results))
            result = pipe.cmd_walkforward(resume=False, progress=parent)
            saved = json.loads(Path(pipe.cfg["walkforward"]["checkpoint_file"]).read_text("utf-8"))
        self.assertEqual(saved["done"], 1)
        self.assertEqual(result["failed"], 2)
        self.assertEqual(result["status"], "FAILED")
        self.assertEqual(result["excluded"]["oos_min_history_stock_dates"], 1)
        finished = pipe.store.finish_run.call_args
        self.assertEqual(finished.args, (3, "FAILED"))
        self.assertEqual(finished.kwargs["n_fail"], 2)
        self.assertEqual(finished.kwargs["n_ok"], 2)
        self.assertTrue(any(c.kwargs.get("asof") == md.asof for c in parent.call_args_list))
        self.assertTrue(any(c.kwargs.get("n_fail") == 2 for c in parent.call_args_list))

    def test_daily_preserves_walkforward_failures_and_actual_asof(self):
        pipe, md = self.make_pipeline()
        pipe.db.scalar.side_effect = [0, None]
        pipe.cmd_walkforward = Mock(return_value={"failed": 2, "excluded": {
            "oos_min_history_stock_dates": 7}})
        stats = {"n_fail": 1, "n_universe": 2, "excluded": {}, "warn_count": 0}
        with patch("engine.pipeline.evaluate", return_value={"n": 9}), \
                patch("engine.pipeline.train_fusion_from_oos"), \
                patch("engine.pipeline.predict_cross_section", return_value=([{}], [], stats)), \
                patch("engine.pipeline.fit_evt_market", return_value={}), \
                patch("engine.pipeline.run_backtest", return_value=None):
            result = pipe.cmd_daily()
        self.assertEqual(result["failed"], 3)
        self.assertEqual(result["status"], "FAILED")
        finished = pipe.store.finish_run.call_args
        self.assertEqual(finished.args, (3, "FAILED"))
        self.assertEqual(finished.kwargs["n_fail"], 3)
        self.assertEqual(finished.kwargs["excluded"]["oos_min_history_stock_dates"], 7)
        self.assertIn("status IN ('PENDING','SETTLED')", pipe.db.scalar.call_args_list[0].args[0])
        progress = pipe.store.update_progress.call_args_list
        self.assertTrue(any(c.kwargs.get("asof") == md.asof for c in progress))
        self.assertTrue(any(c.args[1] == "BACKTEST" for c in progress))

    def test_force_retry_invalidates_old_checkpoint_before_first_failed_block(self):
        pipe, _ = self.make_pipeline()
        with tempfile.TemporaryDirectory() as folder, ExitStack() as stack:
            checkpoint = Path(folder) / "checkpoint.json"
            checkpoint.write_text('{"done": 8, "completed_rows": 48000}', encoding="utf-8")
            pipe.cfg["walkforward"]["checkpoint_file"] = str(checkpoint)
            stack.enter_context(patch("engine.pipeline.walkforward_blocks", return_value=[
                {"test_start": 1, "test_end": 1}]))
            stack.enter_context(patch("engine.pipeline.data_signature", return_value="same-input"))
            stack.enter_context(patch("engine.pipeline.train_fusion_from_oos"))
            stack.enter_context(patch("engine.pipeline.predict_cross_section", return_value=(
                [], [], {"n_fail": 1, "excluded": {}})))
            pipe.cmd_walkforward(resume=False)
            self.assertEqual(json.loads(checkpoint.read_text("utf-8"))["done"], 0)

    def test_walkforward_exception_preserves_last_callback_failure_count(self):
        for fail_at in ("prediction", "save", "settle"):
            with self.subTest(fail_at=fail_at), tempfile.TemporaryDirectory() as folder, ExitStack() as stack:
                pipe, _ = self.make_pipeline()
                pipe.cfg["walkforward"]["checkpoint_file"] = str(Path(folder) / "checkpoint.json")
                stack.enter_context(patch("engine.pipeline.walkforward_blocks", return_value=[
                    {"test_start": 1, "test_end": 1}]))
                stack.enter_context(patch("engine.pipeline.data_signature", return_value="safe-input"))
                stack.enter_context(patch("engine.pipeline.train_fusion_from_oos"))

                def predict(*args, **kwargs):
                    # 模拟已经上报七次真实失败后在预测、保存或结算阶段异常退出。
                    kwargs["progress"](dict(processed=10, total=10, n_ok=3, n_fail=7, excluded={}))
                    if fail_at == "prediction":
                        raise RuntimeError("模拟预测中断")
                    return [{}] * 3, [], {"n_fail": 7, "excluded": {}}

                stack.enter_context(patch("engine.pipeline.predict_cross_section", side_effect=predict))
                if fail_at == "save":
                    pipe.store.save_predictions.side_effect = RuntimeError("模拟保存中断")
                if fail_at == "settle":
                    pipe.settle_all.side_effect = RuntimeError("模拟结算中断")
                with self.assertRaises(RuntimeError):
                    pipe.cmd_walkforward(resume=False)
                finished = pipe.store.finish_run.call_args
                self.assertEqual(finished.kwargs["n_fail"], 7)
                self.assertEqual(finished.kwargs["n_ok"], 3 if fail_at == "settle" else 0)

    def test_training_reports_each_horizon_without_changing_training_inputs(self):
        pipe, md = self.make_pipeline()
        pipe.cfg["horizons"] = [1, 3]
        progress = Mock()
        with patch("engine.pipeline.train_pooled", return_value=SimpleNamespace(n_samples=12)) as train:
            # 直接调用类方法，绕过 fixture 中用于其他编排测试的训练 mock。
            Pipeline._train_all(pipe, md, None, {1: [0, 1], 3: [0]}, md.universe,
                                progress=progress)
        self.assertEqual(train.call_count, 2)
        self.assertEqual([c.args[3:5] for c in train.call_args_list], [(1, [0, 1]), (3, [0])])
        self.assertEqual(progress.call_count, 4)
        self.assertIn("h3 已完成", progress.call_args.kwargs["message"])

    def test_standalone_predict_marks_real_failure_and_persists_asof(self):
        pipe, md = self.make_pipeline()
        pipe.cfg["engine"] = {}
        stats = dict(n_fail=1, n_ok=1, n_universe=2, excluded={}, warn_count=0)
        with patch("engine.pipeline.train_fusion_from_oos"), \
                patch("engine.pipeline.predict_cross_section", return_value=([{}], [], stats)), \
                patch("engine.pipeline.fit_evt_market"):
            result = pipe.cmd_predict()
        self.assertEqual(result["status"], "FAILED")
        self.assertEqual(pipe.store.finish_run.call_args.kwargs["n_fail"], 1)
        self.assertTrue(any(c.kwargs.get("asof") == md.asof
                            for c in pipe.store.update_progress.call_args_list))

    def test_standalone_training_persists_actual_asof(self):
        pipe, md = self.make_pipeline()
        with patch("engine.pipeline.train_fusion_from_oos"):
            pipe.cmd_train()
        self.assertTrue(any(c.kwargs.get("asof") == md.asof
                            for c in pipe.store.update_progress.call_args_list))


if __name__ == "__main__":
    unittest.main()
