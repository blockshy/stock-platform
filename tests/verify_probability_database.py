"""隔离库的只读契约验收入口，绝不初始化 Schema、执行训练或修改记录。

运行前由部署脚本通过进程环境注入 PROB_DB_URL；仅允许本机 13306 端口的
stock_platform_verify。即使调用者误传生产连接，也会在连接前拒绝执行。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]


def require_verification_url(url: str) -> None:
    """先校验环境边界，不把连接串或凭据放入错误消息。"""
    try:
        parsed = urlsplit(url)
        valid = (parsed.scheme in {"mysql", "mysql+pymysql"}
                 and parsed.hostname in {"127.0.0.1", "localhost", "::1"}
                 and parsed.port == 13306 and parsed.path == "/stock_platform_verify")
    except ValueError:
        valid = False
    if not valid:
        raise ValueError("只允许本机 13306 端口的 stock_platform_verify 验收库")


def inspect_database(db) -> dict:
    """只查询必要的结构、状态和隔离计数，不读取任务配置快照或连接凭据。"""
    identity = db.query("SELECT DATABASE() AS database_name, @@port AS port")[0]
    if identity["database_name"] != "stock_platform_verify" or int(identity["port"]) != 13306:
        raise ValueError("数据库实际身份与隔离验收配置不一致")
    views = db.query("SELECT VIEW_DEFINITION FROM information_schema.VIEWS "
                     "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='stock_daily_all'")
    if not views or "stock_eastmoney" in str(views[0]["VIEW_DEFINITION"]).lower():
        raise ValueError("验收视图缺失或仍引用生产数据库，必须先修正隔离快照")
    tables = db.query("SELECT TABLE_NAME FROM information_schema.TABLES "
                      "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'prob\\_%' "
                      "ORDER BY TABLE_NAME")
    if len(tables) != 11:
        raise ValueError("概率表数量应为 11，隔离快照结构不完整")
    source_etfs = int(db.scalar("SELECT COUNT(*) FROM stock_daily_all WHERE code LIKE '58%'") or 0)
    prediction_etfs = int(db.scalar("SELECT COUNT(*) FROM prob_prediction WHERE code LIKE '58%'") or 0)
    if source_etfs or prediction_etfs:
        raise ValueError("股票源视图或概率明细发现 58 ETF，模型隔离验收失败")
    counts = db.query("SELECT pred_type,status,horizon,COUNT(*) AS rows_count,"
                      "COUNT(DISTINCT code,market) AS stocks FROM prob_prediction "
                      "GROUP BY pred_type,status,horizon ORDER BY pred_type,status,horizon")
    # 对 EXCLUDED 行单独统计，证明历史审计排除状态随数据库快照迁移得到保留。
    excluded = int(db.scalar("SELECT COUNT(*) FROM prob_prediction "
                             "WHERE pred_type='OOS' AND status='EXCLUDED'") or 0)
    return dict(database="stock_platform_verify", port=13306, probabilityTables=len(tables),
                etfRowsInStockView=source_etfs, etfPredictionRows=prediction_etfs,
                excludedOosRows=excluded, predictionCounts=counts, readOnly=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="仅检查隔离概率库，不运行计算任务")
    parser.add_argument("--output", type=Path, default=ROOT / ".local/runtime/verification/probability-db-audit.json")
    args = parser.parse_args()
    target = args.output.resolve()
    allowed = (ROOT / ".local/runtime/verification").resolve()
    if not target.is_relative_to(allowed):
        parser.error("验收报告只能写入新项目 .local/runtime/verification")
    connection_url = os.environ.get("PROB_DB_URL", "")
    require_verification_url(connection_url)
    sys.path.insert(0, str(ROOT / "engines/probability"))
    from engine.db import Db
    # 直接构造 Db，不构造会自动建表的 Pipeline，因此不会对库执行 DDL 或 DML。
    report = inspect_database(Db({"db": {"url": connection_url}}))
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, default=str))
