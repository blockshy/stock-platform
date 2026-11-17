"""滚动评估断点及 DAILY 参数透传的回归测试。

使用极小的内存行情和模拟数据库，只验证身份、进度及编排，不执行真实预测或写库。
"""
from __future__ import annotations

import copy
import logging
import unittest
from datetime import date, timedelta
from types import SimpleNamespace
from unittest.mock import ANY, Mock, patch

import numpy as np
import pandas as pd

from engine.checkpoint import CHECKPOINT_SCHEMA, data_signature, resume_progress
from engine.cli import main
from engine.data import NUMERIC_COLUMNS
from engine.pipeline import Pipeline


class TestCheckpointIdentity(unittest.TestCase):
    """相同数量不足以证明数据相同，断点还必须对应实际股票及完整行情内容。"""

    def setUp(self):
        self.days = [date(2026, 1, 5) + timedelta(days=n) for n in range(4)]
        self.keys = ["000001.SZ", "000002.SZ"]
        self.md = SimpleNamespace(
            asof=self.days[-1], dates=self.days, keys=list(self.keys),
            arr={name: pd.DataFrame(np.ones((4, 2), dtype=np.float32),
                                    index=self.days, columns=self.keys)
                 for name in NUMERIC_COLUMNS})
        self.blocks = [{"test_start": 1, "test_end": 2, "train_positions": [0]}]
        self.signature = data_signature(self.md, self.keys, self.blocks, "model-1")

    def test_identity_changes_with_same_size_stocks_prices_or_model(self):
        self.assertEqual(data_signature(self.md, self.keys, self.blocks, "model-1"), self.signature)
        self.assertNotEqual(data_signature(self.md, ["000003.SZ", "000004.SZ"],
                                           self.blocks, "model-1"), self.signature)
        changed = copy.deepcopy(self.md)
        changed.arr["close"].iloc[0, 0] = 1.25
        self.assertNotEqual(data_signature(changed, self.keys, self.blocks, "model-1"), self.signature)
        self.assertNotEqual(data_signature(self.md, self.keys, self.blocks, "model-2"), self.signature)

    def test_resume_requires_matching_database_rows(self):
        state = dict(schema=CHECKPOINT_SCHEMA, data_signature=self.signature,
                     done=1, completed_rows=8)
        database = Mock()
        database.scalar.return_value = 8
        self.assertEqual(resume_progress(state, self.signature, database, self.md,
                                          self.keys, self.blocks, "model-1"), (1, 8))
        # 数据库清空或只写入一部分结果时，即使文件显示完成也必须重新计算。
        for stored in (0, 7, 9):
            database.scalar.return_value = stored
            self.assertEqual(resume_progress(state, self.signature, database, self.md,
                                              self.keys, self.blocks, "model-1"), (0, 0))
        sql, params = database.scalar.call_args.args
        self.assertIn("model_version=%s", sql)
        self.assertIn("status IN ('PENDING','SETTLED')", sql)
        self.assertEqual(params, ("model-1", self.days[1], self.days[2], *self.keys))

    def test_old_or_different_checkpoint_never_queries_or_skips(self):
        database = Mock()
        for state in ({"asof": str(self.md.asof), "keys": 2, "done": 1},
                      {"schema": CHECKPOINT_SCHEMA, "data_signature": "different", "done": 1}):
            self.assertEqual(resume_progress(state, self.signature, database, self.md,
                                              self.keys, self.blocks, "model-1"), (0, 0))
        database.scalar.assert_not_called()


class TestDailyNoResume(unittest.TestCase):
    """验证 CLI 与 DAILY 编排均尊重禁用断点参数，避免只修正命令行而遗漏内部调用。"""

    def test_cli_passes_no_resume_to_daily(self):
        with patch("engine.cli.load_config", return_value={}), \
                patch("engine.cli._emit"), patch("engine.pipeline.Pipeline") as factory:
            factory.return_value.cmd_daily.return_value = {}
            self.assertEqual(main(["daily", "--no-resume"]), 0)
            factory.return_value.cmd_daily.assert_called_once_with(None, 0, None, False, False)

    def test_daily_forces_walkforward_without_checkpoint_when_requested(self):
        # 绕过连接数据库的构造方法，所有计算及写入边界均替换为模拟对象。
        pipe = object.__new__(Pipeline)
        today = date(2026, 1, 8)
        pipe.cfg = {"horizons": [], "retrain_every": 20}
        pipe.version = "model-1"
        pipe.db = Mock()
        pipe.db.scalar.side_effect = [5000, today]
        pipe.store = Mock()
        pipe.store.start_run.return_value = 1
        pipe.logger = logging.getLogger("checkpoint-test")
        pipe.prepare = Mock(return_value=(SimpleNamespace(asof=today, dates=[today],
                                                         universe=[], excluded={}, warn_count=0,
                                                         date_pos=lambda _: 0), None))
        pipe.cmd_walkforward = Mock(return_value={"failed": 0, "excluded": {}})
        pipe.settle_all = Mock(return_value=0)
        pipe.period_grades = Mock(return_value={})
        pipe._train_all = Mock(return_value={})
        stats = dict(n_fail=0, n_universe=0, excluded={}, warn_count=0)
        with patch("engine.pipeline.evaluate", return_value={"n": 0}), \
                patch("engine.pipeline.predict_cross_section", return_value=([], [], stats)), \
                patch("engine.pipeline.fit_evt_market", return_value={}), \
                patch("engine.pipeline.run_backtest", return_value=None):
            pipe.cmd_daily(resume=False)
        pipe.cmd_walkforward.assert_called_once_with(None, 0, None, resume=False, progress=ANY)


if __name__ == "__main__":
    unittest.main()
