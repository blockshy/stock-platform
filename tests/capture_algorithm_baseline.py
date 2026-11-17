"""显式建立迁移基线；普通测试不会调用本脚本或自动更新期望结果。

只读取旧仓库，将算法文件摘要及受审查的路径适配摘要写入新仓库 contracts。
除列出的四个资源路径适配文件外，任何迁移差异都会中止基线生成。
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADAPTERS = {
    "engine/config.py": "新增运行目录/特征环境解析；不改 cfg 参数及 model_version 算法。",
    "engine/predictor.py": "模型输出路径改走 resolve_runtime_path；预测统计逻辑保持原样。",
    "engine/pipeline.py": "断点路径改走 resolve_runtime_path；任务及统计逻辑保持原样。",
    "engine/logutil.py": "日志路径改走 resolve_runtime_path；格式和脱敏逻辑保持原样。",
}


def digest(path: Path) -> str:
    """以原始字节计算 SHA256，代码、参数或换行变化均能被发现。"""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def capture(legacy_root: Path) -> dict:
    entries = []
    old_engine = legacy_root / "stock-probability-engine"
    old_api = legacy_root / "stock"
    engine_files = sorted(old_engine.glob("engine/**/*.py"))
    engine_files += [old_engine / name for name in
                     ("config.yaml", "features.yaml", "requirements.txt", "engine/schema.sql")]
    pairs = [(path, ROOT / "engines/probability" / path.relative_to(old_engine),
              ADAPTERS.get(path.relative_to(old_engine).as_posix())) for path in engine_files]
    java_root = old_api / "src/main/java/com/li/stock"
    java_files = set()
    for folder in ("ga/engine", "ga/feature", "ga/label", "ga/predict", "ga/metric",
                   "quant/strategy", "quant/indicator", "quant/analytics", "quant/backtest"):
        java_files.update((java_root / folder).rglob("*.java"))
    for name in ("ScoreCalculationService", "ScoreContinuityAnalyzer", "ShortTermForecastService",
                 "ForecastService", "TTradingStrategyEngine"):
        java_files.add(java_root / "service" / (name + ".java"))
    pairs += [(path, ROOT / "apps/api" / path.relative_to(old_api), None)
              for path in sorted(java_files)]
    pairs.append((old_api / "src/main/resources/prob_schema.sql",
                  ROOT / "apps/api/src/main/resources/prob_schema.sql", None))
    for old, new, rationale in pairs:
        before, after = digest(old), digest(new)
        if before != after and not rationale:
            raise RuntimeError(f"未获准的算法/参数差异：{new.relative_to(ROOT)}")
        entries.append(dict(path=new.relative_to(ROOT).as_posix(),
                            legacyPath=old.relative_to(legacy_root).as_posix(),
                            baselineSha256=before, expectedSha256=after,
                            policy="runtime-path-adapter" if rationale else "unchanged",
                            rationale=rationale))
    return dict(formatVersion=1, description="当前已修复版本的迁移基线；非远程原始版本。",
                note="修改期望摘要必须重新人工审查，不允许测试自动接受新摘要。", files=entries)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="一次性捕获只读旧仓库算法基线")
    parser.add_argument("--legacy-root", type=Path, required=True)
    args = parser.parse_args()
    target = ROOT / "contracts/algorithm-baseline.json"
    target.write_text(json.dumps(capture(args.legacy_root), ensure_ascii=False, indent=2), encoding="utf-8")
    print("已记录算法摘要；未修改旧仓库。")
