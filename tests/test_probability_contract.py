"""monorepo 迁移的离线验收：代码摘要、CLI、配置、路径、Schema 与数值基线。

本测试不创建 Pipeline 实例，不启动 Java/Python 子进程，不访问数据库或网络。
CLI 编排边界均使用 mock，数值测试仅使用固定的内存合成行情。
"""
from __future__ import annotations

import hashlib
import importlib.metadata
import json
import math
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "engines/probability"
sys.path.insert(0, str(ENGINE))

from engine.cli import main
from engine.config import (BASE_DIR, load_config, load_features, model_version,
                           read_json_yaml, resolve_runtime_path, runtime_root)
from engine.evaluation import load_oos
from engine.predictor import train_fusion_from_oos
from engine.backtest import run_backtest
from probability_fixture import calculate
from verify_probability_database import inspect_database, require_verification_url

CONTRACT = json.loads((ROOT / "contracts/probability-engine.json").read_text("utf-8"))


class TestSourceAndSchemaParity(unittest.TestCase):
    """参数和算法保持基线；四个路径适配文件使用单独审查后的期望摘要。"""

    def test_all_recorded_source_hashes_match_reviewed_baseline(self):
        manifest = json.loads((ROOT / "contracts/algorithm-baseline.json").read_text("utf-8"))
        for entry in manifest["files"]:
            with self.subTest(path=entry["path"]):
                actual = hashlib.sha256((ROOT / entry["path"]).read_bytes()).hexdigest()
                self.assertEqual(actual, entry["expectedSha256"])
                if entry["policy"] == "unchanged":
                    self.assertEqual(actual, entry["baselineSha256"])
                else:
                    self.assertTrue(entry["rationale"])

    def test_java_and_python_schema_are_byte_identical(self):
        schema = (ENGINE / "engine/schema.sql").read_bytes()
        self.assertEqual(schema, (ROOT / "apps/api/src/main/resources/prob_schema.sql").read_bytes())
        self.assertEqual(sum(line.startswith(b"CREATE TABLE IF NOT EXISTS")
                             for line in schema.splitlines()), 11)

    def test_statistical_configuration_is_preserved(self):
        cfg = read_json_yaml(ENGINE / "config.yaml")
        expected = CONTRACT["statistics"]
        for key in ("lookback_days", "min_history", "horizons", "n_paths", "seed_base", "oos_fast"):
            self.assertEqual(cfg[key], expected[key])
        self.assertEqual(cfg["walkforward"]["max_stocks"], expected["oos_max_stocks"])
        for key in ("oos_max_days", "oos_stride"):
            self.assertEqual(cfg["walkforward"][key], expected[key])
        self.assertEqual(cfg["db"]["source_table"], CONTRACT["source"]["table"])
        self.assertEqual(cfg["universe_prefixes"], CONTRACT["source"]["prefixes"])

    def test_python_and_installed_dependencies_match_migration_lock(self):
        # 精确锁文件包括科学计算及其传递依赖；不可仅满足 requirements 的宽范围就验收。
        self.assertEqual(sys.version_info[:3], (3, 10, 11))
        for line in (ENGINE / "requirements.lock.txt").read_text("utf-8-sig").splitlines():
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            package, expected = line.strip().split("==", 1)
            with self.subTest(package=package):
                self.assertEqual(importlib.metadata.version(package), expected)


class TestRuntimeAndCliContract(unittest.TestCase):
    """部署路径与凭据的传递规则需明确，尤其不能误改统计版本。"""

    def test_runtime_root_changes_only_paths_and_keeps_model_version(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, {}, clear=True):
            cfg = load_config()
            original = json.dumps(cfg, sort_keys=True)
            version = model_version(cfg)
            self.assertEqual(runtime_root(), BASE_DIR)
            self.assertEqual(resolve_runtime_path("artifacts/model.pkl"), BASE_DIR / "artifacts/model.pkl")
            os.environ["PROB_RUNTIME_ROOT"] = folder
            self.assertEqual(resolve_runtime_path("artifacts/model.pkl"), Path(folder) / "artifacts/model.pkl")
            self.assertEqual(resolve_runtime_path(Path(folder) / "absolute.pkl"), Path(folder) / "absolute.pkl")
            self.assertEqual(model_version(cfg), version)
            self.assertEqual(json.dumps(cfg, sort_keys=True), original)

    def test_config_and_features_environment_share_explicit_file_identity(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, {}, clear=True):
            config = Path(folder) / "config.json"
            config.write_text((ENGINE / "config.yaml").read_text("utf-8"), encoding="utf-8")
            feature = Path(folder) / "features.json"
            feature.write_text('{"test_registry": 1}', encoding="utf-8")
            os.environ.update(PROB_CONFIG=str(config), PROB_FEATURES=str(feature))
            self.assertEqual(load_config()["__config_path__"], str(config))
            self.assertEqual(load_features(), {"test_registry": 1})
            self.assertEqual(load_features(ENGINE / "features.yaml"), read_json_yaml(ENGINE / "features.yaml"))

    def test_database_url_change_expectedly_changes_legacy_version_hash(self):
        # 测试库与生产库的连接配置不同，因此版本元数据不能作为数值不等效的证据。
        with patch.dict(os.environ, {}, clear=True):
            cfg = load_config()
            old = model_version(cfg)
            os.environ["PROB_DB_URL"] = "mysql+pymysql://fake:fake@127.0.0.1:13306/stock_platform_verify"
            test_cfg = load_config()
        self.assertNotEqual(model_version(test_cfg), old)
        self.assertEqual(test_cfg["horizons"], cfg["horizons"])

    def test_cli_forwards_daily_arguments_without_constructing_real_pipeline(self):
        with patch("engine.cli.load_config", return_value={}) as load, \
                patch("engine.cli._emit"), patch("engine.pipeline.Pipeline") as factory:
            factory.return_value.cmd_daily.return_value = {}
            result = main(["--config", "configuration.json", "daily", "--asof", "2026-11-16",
                           "--run-id", "17", "--limit-stocks", "0", "--force", "--no-resume"])
        self.assertEqual(result, 0)
        load.assert_called_once_with("configuration.json")
        from datetime import date
        factory.return_value.cmd_daily.assert_called_once_with(date(2026, 11, 16), 0, 17, True, False)

    def test_cli_command_mapping_covers_all_java_task_types(self):
        for external, command in CONTRACT["javaCommands"].items():
            with self.subTest(command=external), patch("engine.cli.load_config", return_value={}), \
                    patch("engine.cli._emit"), patch("engine.pipeline.Pipeline") as factory:
                self.assertEqual(main([command, "--run-id", "9"]), 0)
                getattr(factory.return_value, "cmd_" + command).assert_called_once()


class TestNumericAndIsolationParity(unittest.TestCase):
    """数值结果用独立保存的旧代码基线比较；集合、离散状态和计数必须完全一致。"""

    def test_fixed_input_seed_and_boundaries_match_legacy_results(self):
        expected = json.loads((ROOT / "contracts/probability-numeric-baseline.json").read_text("utf-8"))
        actual = calculate(ENGINE)
        tolerance = CONTRACT["numericTolerance"]

        def compare(left, right, path="result"):
            if isinstance(left, dict):
                self.assertEqual(set(left), set(right), path)
                for key in left:
                    compare(left[key], right[key], path + "." + key)
            elif isinstance(left, list):
                self.assertEqual(len(left), len(right), path)
                for index, (a, b) in enumerate(zip(left, right)):
                    compare(a, b, path + f"[{index}]")
            elif isinstance(left, float):
                self.assertTrue(math.isclose(left, right, rel_tol=tolerance["relative"],
                                             abs_tol=tolerance["absolute"]), path)
            else:
                self.assertEqual(left, right, path)
        compare(expected, actual)
        self.assertEqual(actual["oos_failed"], 0)
        self.assertEqual(actual["boundary249"], [])
        self.assertEqual(actual["excluded"]["code_prefix"], 1)
        self.assertEqual(actual["excluded"]["min_history"], 1)
        self.assertFalse(any(key.startswith("58") for key in actual["universe"]))

    def test_all_oos_consumers_exclude_historical_excluded_records(self):
        cfg = read_json_yaml(ENGINE / "config.yaml")
        for consume in (lambda db: load_oos(db, cfg),
                        lambda db: train_fusion_from_oos(db, cfg, 1),
                        lambda db: run_backtest(None, db, cfg, 1)):
            db = Mock()
            db.query.return_value = []
            consume(db)
            self.assertIn("status='SETTLED'", db.query.call_args.args[0])
            db.execute.assert_not_called()

    def test_verification_guard_rejects_production_before_connection(self):
        for url in ("mysql://fake:fake@127.0.0.1:13306/stock_eastmoney",
                    "mysql://fake:fake@remote:13306/stock_platform_verify",
                    "mysql://fake:fake@127.0.0.1:3306/stock_platform_verify", "malformed"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                require_verification_url(url)
        require_verification_url("mysql+pymysql://fake:fake@127.0.0.1:13306/stock_platform_verify")

    def test_verification_view_cannot_reference_production_schema(self):
        db = Mock()
        db.query.side_effect = [dict(database_name="stock_platform_verify", port=13306)], \
                              [dict(VIEW_DEFINITION="select * from stock_eastmoney.stock_daily_00")]
        with self.assertRaisesRegex(ValueError, "生产数据库"):
            inspect_database(db)
        db.scalar.assert_not_called()


if __name__ == "__main__":
    unittest.main()
