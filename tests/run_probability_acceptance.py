"""一条命令运行原引擎与迁移契约离线测试，并保存机器可读报告。

子进程只执行 unittest，不启动引擎 CLI 计算或服务。清除继承的 PROB 环境变量，
避免开发终端的数据库/路径设置影响合成样本；报告不含数据库连接或凭据。
"""
from __future__ import annotations

import importlib.metadata
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def run_suite(directory: Path) -> dict:
    """分别在对应项目根运行发现测试，避免两个同名 tests 目录相互遮蔽。"""
    environment = {key: value for key, value in os.environ.items() if not key.startswith("PROB_")}
    environment.update(PYTHONUTF8="1", PYTHONDONTWRITEBYTECODE="1")
    completed = subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"],
                               cwd=directory, env=environment, capture_output=True,
                               text=True, encoding="utf-8", timeout=180)
    output = completed.stdout + completed.stderr
    count = re.search(r"Ran (\d+) tests?", output)
    return dict(directory=directory.relative_to(ROOT).as_posix(), exitCode=completed.returncode,
                tests=int(count.group(1)) if count else 0, output=output)


if __name__ == "__main__":
    suites = [run_suite(ROOT / "engines/probability"), run_suite(ROOT)]
    manifest = json.loads((ROOT / "contracts/algorithm-baseline.json").read_text("utf-8"))
    report = dict(generatedAt=datetime.now(timezone.utc).isoformat(),
                  status="PASS" if all(suite["exitCode"] == 0 for suite in suites) else "FAIL",
                  tests=sum(suite["tests"] for suite in suites), suites=suites,
                  baselineFiles=len(manifest["files"]),
                  unchangedFiles=sum(item["policy"] == "unchanged" for item in manifest["files"]),
                  runtimeAdapters=sum(item["policy"] != "unchanged" for item in manifest["files"]),
                  python=sys.version.split()[0],
                  packages={name: importlib.metadata.version(name) for name in
                            ("numpy", "pandas", "scipy", "scikit-learn", "PyMySQL", "cryptography")},
                  limitations=["未执行真实数据库训练", "未运行Java服务", "不承诺跨平台逐位一致"])
    destination = ROOT / ".local/runtime/verification/reports/probability-offline.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("status", "tests", "baselineFiles", "unchangedFiles", "runtimeAdapters")},
                     ensure_ascii=False))
    print(f"报告：{destination}")
    raise SystemExit(0 if report["status"] == "PASS" else 1)
