"""覆盖配置、任务记录、日志和模型产物的凭据泄漏回归测试。

全部使用虚构凭据和内存对象，不连接数据库、不读取本机密码、不启动计算任务。
"""
from __future__ import annotations

import copy
import hashlib
import io
import json
import logging
import pickle
import unittest
from contextlib import redirect_stdout
from unittest.mock import Mock

from engine.cli import _emit
from engine.config import config_snapshot, model_version, redact_database_url
from engine.logutil import RedactingFormatter
from engine.models.base import BaseModel
from engine.store import ProbStore


class TestCredentialRedaction(unittest.TestCase):
    """确保安全修复只影响输出，真实连接配置与统计模型版本保持原行为。"""

    def setUp(self):
        self.url = "mysql+pymysql://test_user:fake%40secret@127.0.0.1:13306/stock?charset=utf8mb4"
        self.safe_url = "mysql+pymysql://127.0.0.1:13306/stock?charset=utf8mb4"
        self.cfg = {"engine_code_version": "test-1", "db": {"url": self.url},
                    "horizons": [1, 3, 5, 10], "__config_path__": "private-path"}

    def assert_no_credentials(self, text):
        self.assertNotIn("test_user", text)
        self.assertNotIn("fake%40secret", text)

    def test_snapshot_does_not_change_live_config_or_version(self):
        original = copy.deepcopy(self.cfg)
        # 按修复前的算法独立计算期望哈希，防止脱敏顺带改变已存预测使用的模型版本。
        payload = json.dumps({k: v for k, v in self.cfg.items() if not k.startswith("__")},
                             sort_keys=True, ensure_ascii=False, default=str)
        expected = "test-1-" + hashlib.sha1(payload.encode("utf-8")).hexdigest()[:10]
        snapshot = json.loads(config_snapshot(self.cfg))
        self.assertEqual(snapshot["db"]["url"], self.safe_url)
        self.assertEqual(snapshot["horizons"], original["horizons"])
        self.assertNotIn("__config_path__", snapshot)
        self.assertEqual(self.cfg, original)
        self.assertEqual(model_version(self.cfg), expected)

    def test_url_keeps_ipv6_and_masks_query_credentials(self):
        url = "mysql://test_user:fake%40secret@[::1]:13306/stock?charset=utf8mb4&password=query-secret"
        safe = redact_database_url(url)
        self.assertEqual(safe, "mysql://[::1]:13306/stock?charset=utf8mb4&password=[REDACTED]")
        self.assertEqual(redact_database_url("mysql://[broken"), "[REDACTED]")

    def test_cli_and_exception_text_are_redacted(self):
        output = io.StringIO()
        with redirect_stdout(output):
            _emit({"data": self.cfg, "traceback": "连接失败：" + self.url})
        self.assert_no_credentials(output.getvalue())
        self.assertIn(self.safe_url, output.getvalue())
        self.assertEqual(self.cfg["db"]["url"], self.url)

    def test_task_insert_and_reused_run_both_use_safe_snapshots(self):
        database = Mock()
        database.insert.return_value = 9
        store = ProbStore(database, self.cfg)
        self.assertEqual(store.start_run("DAILY", None, "unchanged-version"), 9)
        inserted = database.insert.call_args.args[1]
        self.assert_no_credentials(inserted["config_json"])
        self.assertEqual(inserted["model_version"], "unchanged-version")
        store.start_run("DAILY", None, "unchanged-version", run_id=7)
        updated = database.update.call_args.args[1]
        self.assert_no_credentials(updated["config_json"])
        # 网页先建任务时使用占位版本，Python 接管后必须写入真实模型版本。
        self.assertEqual(updated["model_version"], "unchanged-version")
        store.finish_run(7, "FAILED", message="连接失败：" + self.url)
        self.assert_no_credentials(database.update.call_args.args[1]["message"])

    def test_logging_formatter_covers_interpolation_and_tracebacks(self):
        stream = io.StringIO()
        handler = logging.StreamHandler(stream)
        handler.setFormatter(RedactingFormatter("%(message)s"))
        logger = logging.Logger("redaction-test")
        logger.addHandler(handler)
        try:
            raise RuntimeError("模拟连接错误：" + self.url)
        except RuntimeError:
            logger.exception("无法连接 %s", self.url)
        self.assert_no_credentials(stream.getvalue())
        self.assertIn("RuntimeError", stream.getvalue())

    def test_pickle_config_is_safe_without_mutating_the_model(self):
        # 真实 pickle 序列化会调用模型基类协议；仅验证虚构模型，无需训练或加载外部产物。
        model = BaseModel()
        model.cfg = self.cfg
        model.coefficients = [0.25, -0.75]
        payload = pickle.dumps(model)
        self.assertNotIn(b"test_user", payload)
        self.assertNotIn(b"fake%40secret", payload)
        restored = pickle.loads(payload)
        self.assertEqual(restored.cfg["db"]["url"], self.safe_url)
        self.assertEqual(restored.coefficients, model.coefficients)
        self.assertEqual(model.cfg["db"]["url"], self.url)


if __name__ == "__main__":
    unittest.main()
