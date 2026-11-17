"""滚动评估断点身份与数据库结果校验。

断点只记录已完成工作的索引，不是预测数据本身；恢复前必须同时确认输入行情、
股票池、配置版本、测试区间及数据库内结果仍然匹配，避免沿用其他环境的旧进度。
"""
from __future__ import annotations

import hashlib
import json
from typing import Any

import numpy as np

from .data import NUMERIC_COLUMNS

# 第三版加入预测日历史资格检查，并且只提交无计算失败的完整块。
# 旧断点可能掩盖短历史股票的预测异常，不能直接沿用其完成标记。
CHECKPOINT_SCHEMA = 3


def data_signature(md, keys: list[str], blocks: list[dict[str, Any]], version: str) -> str:
    """对实际输入和抽样身份生成摘要，不保存数据库 URL 或其他认证字段。

    横截面特征依赖完整股票池，因此不能只检查抽中的 300 只股票或股票数量。
    对全部基础行情列逐列计算摘要，修订同日历史价格时也会使旧断点失效；
    每次只临时转换一列 float32 矩阵，避免再复制完整特征集。
    """
    identity = dict(version=version, asof=str(md.asof), dates=[str(day) for day in md.dates],
                    universe=list(md.keys), sampled_keys=list(keys), blocks=blocks)
    digest = hashlib.sha256(json.dumps(identity, sort_keys=True, ensure_ascii=False,
                                       default=str).encode("utf-8"))
    for name in NUMERIC_COLUMNS:
        values = np.ascontiguousarray(md.arr[name].to_numpy(dtype=np.float32), dtype="<f4")
        digest.update(name.encode("utf-8"))
        digest.update(memoryview(values).cast("B"))
    return digest.hexdigest()


def resume_progress(state: dict[str, Any], signature: str, db, md, keys: list[str],
                    blocks: list[dict[str, Any]], version: str) -> tuple[int, int]:
    """返回可安全跳过的块数和累计行数；身份或持久化结果不一致时从零重算。

    校验查询限定模型版本、已完成测试日期和抽样股票，防止空数据库、其他股票池
    或仅有同日期的旧结果被误认为当前断点已完成。查询只取 COUNT，不加载大结果集。
    """
    if state.get("schema") != CHECKPOINT_SCHEMA or state.get("data_signature") != signature:
        return 0, 0
    try:
        done = int(state.get("done", 0))
        rows = int(state.get("completed_rows", 0))
    except (TypeError, ValueError):
        return 0, 0
    if not 0 < done <= len(blocks) or rows <= 0 or not keys:
        return 0, 0
    dates = [md.dates[pos] for block in blocks[:done]
             for pos in range(block["test_start"], block["test_end"] + 1)]
    date_slots = ",".join(["%s"] * len(dates))
    key_slots = ",".join(["%s"] * len(keys))
    count = db.scalar(
        "SELECT COUNT(*) FROM prob_prediction WHERE pred_type='OOS' AND model_version=%s "
        # 历史不合格衍生行可以保留为 EXCLUDED；它们不属于有效断点成果。
        "AND status IN ('PENDING','SETTLED') "
        f"AND asof_date IN ({date_slots}) AND CONCAT(code,'.',market) IN ({key_slots})",
        (version, *dates, *keys))
    return (done, rows) if int(count or 0) == rows else (0, 0)
