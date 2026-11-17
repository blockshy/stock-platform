"""固定输入的离线数值样本，不读取数据库、不拟合合并模型、不写模型产物。

本文件可分别导入迁移前后的 engine 包，以同一组合成行情验证清洗、特征、
历史资格、OOS 解析预测与固定种子 FHS 核心；完整训练流程仍需隔离库验收。
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def calculate(engine_root: Path) -> dict:
    """仅在进程启动时选择一份引擎代码，避免同进程混用两个同名 engine 包。"""
    sys.path.insert(0, str(engine_root))
    import numpy as np
    import pandas as pd
    from engine.config import read_json_yaml
    from engine.data import MarketData, NUMERIC_COLUMNS
    from engine.features import build_features
    from engine.models import PooledModels, fit_volatility, simulate_fhs, stable_seed
    from engine.predictor import predict_cross_section, prediction_keys

    # 固定样本直接读取受版本管理的参数，不受调用终端的数据库或路径环境变量影响。
    cfg = read_json_yaml(engine_root / "config.yaml")
    dates = list(pd.bdate_range("2025-01-02", periods=320).date)
    keys = ["000001.SZ", "300001.SZ", "600000.SH", "001221.SZ", "588000.SH"]
    md = MarketData(cfg, None, dates=dates, keys=keys, asof=dates[-1])
    md.markets = {key: key.split(".")[1] for key in keys}
    md._date_index = {day: index for index, day in enumerate(dates)}
    shape = (len(dates), len(keys))
    values = {name: np.ones(shape, dtype=np.float32) for name in NUMERIC_COLUMNS}
    days = np.arange(len(dates), dtype=np.float64)[:, None]
    stocks = np.arange(len(keys), dtype=np.float64)[None, :]
    returns = 0.0002 + 0.008 * np.sin(days * 0.37 + stocks * 0.8)
    close = (10 + stocks * 3) * np.exp(np.cumsum(returns, axis=0))
    previous = np.vstack([close[:1], close[:-1]])
    opening = previous * (1 + 0.001 * np.cos(days * 0.2 + stocks))
    values.update(close=close, preclose=previous, open=opening,
                  high=np.maximum(opening, close) * 1.005,
                  low=np.minimum(opening, close) * 0.995,
                  volume=np.full(shape, 100000.0) + days * 100 + stocks * 1000,
                  amount=(100000 + days * 100 + stocks * 1000) * close,
                  pct_chg=close / previous - 1, amplitude=np.full(shape, 0.02),
                  turnover=np.full(shape, 0.01), is_st=np.zeros(shape),
                  pe_ttm=np.full(shape, 15.0), pb_mrq=np.full(shape, 1.5),
                  ps_ttm=np.full(shape, 2.0))
    # 新股只有 249 个有效日；ETF 虽有行情，也必须被原股票前缀规则排除。
    for name in NUMERIC_COLUMNS:
        matrix = np.asarray(values[name], dtype=np.float32)
        matrix[:71, 3] = np.nan
        md.arr[name] = pd.DataFrame(matrix, index=dates, columns=keys)
    # 一条停牌记录验证有效历史计数口径，不改变其他模型统计参数。
    md.arr["volume"].iloc[150, 2] = 0
    md._clean()
    md._build_derived()
    md._build_universe()
    fs = build_features(md, cfg, registry=read_json_yaml(engine_root / "features.yaml"))
    pooled = {h: PooledModels(horizon=h) for h in cfg["horizons"]}
    rows, _, stats = predict_cross_section(md, fs, pooled, cfg, 310, "OOS", "fixture", 7,
                                          fast=True)
    columns = ("code", "market", "horizon", "p_up", "p_down", "exp_ret", "q05", "q25",
               "q50", "q75", "q95", "vol_h", "var95", "var99", "cvar95", "risk_level",
               "reliability_grade", "selected_method", "flags")
    normalized = [{name: row[name] for name in columns} for row in rows]
    normalized.sort(key=lambda row: (row["code"], row["market"], row["horizon"]))

    # 使用真实 EWMA 波动状态与 10000 条固定种子残差路径，单独覆盖 LIVE 的 FHS 核心。
    # 这里不调用 train_pooled、Pipeline 或任何持久化函数，因而不会触发训练任务。
    series = md.ret[keys[0]].dropna().to_numpy(dtype=float)
    vol = fit_volatility(series, cfg, max(cfg["horizons"]), force_ewma=True)
    seed = stable_seed("000001", md.asof, cfg["seed_base"])
    rng = np.random.default_rng(seed)
    z = vol.residual_pool[rng.integers(0, len(vol.residual_pool),
                                     size=(cfg["n_paths"], max(cfg["horizons"])))]
    fhs = {}
    for horizon in cfg["horizons"]:
        result = simulate_fhs(vol, horizon, 0.55, cfg, z[:, :horizon], 0.1)
        fhs[str(horizon)] = dict(p_up=result.p_up, mean=result.mean, quantiles=result.q.tolist(),
                                 var95=result.var95, var99=result.var99, cvar95=result.cvar95)
    probes = {name: fs.frames[name].iloc[-1].reindex(md.universe).tolist()
              for name in ("ret_1", "rsi_14", "cs_rank_ret_5")}
    return dict(universe=md.universe, excluded=md.excluded,
                boundary249=prediction_keys(md, cfg, 248, "OOS")[0],
                boundary250=prediction_keys(md, cfg, 249, "OOS")[0],
                oos_failed=stats["n_fail"], oos_rows=normalized, feature_probes=probes,
                seed=seed, fhs=fhs)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="仅使用合成行情的离线概率数值样本")
    parser.add_argument("--engine-root", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    output = json.dumps(calculate(args.engine_root), ensure_ascii=False, indent=2, allow_nan=False)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding="utf-8")
    else:
        print(output)
