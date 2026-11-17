"""任务编排：prepare / train / predict / walkforward / settle / evaluate / backtest / daily。

自动化目标（文档 §10.1、§13 P3）：`engine daily` 一条命令跑完全链路，
按 retrain_every 自动决定是否需要重训与滚动评估，支持按 asof_date 断点续跑。
任何一步都被包进 prob_run，记录耗时、成功/失败数量与配置快照。
"""
from __future__ import annotations

import json
import traceback
from datetime import date, datetime
from pathlib import Path
from typing import Any

import numpy as np

from .backtest import run_backtest
from .checkpoint import CHECKPOINT_SCHEMA, data_signature, resume_progress
from .config import (BASE_DIR, config_snapshot, load_config, load_features, model_version,
                     redact_text, resolve_runtime_path)
from .data import MarketData
from .db import Db
from .evaluation import evaluate, fit_evt_market
from .features import build_features
from .labels import training_positions, walkforward_blocks
from .logutil import get_logger, task_timer
from .models import PooledModels
from .predictor import predict_cross_section, train_fusion_from_oos, train_pooled
from .store import ProbStore


class Pipeline:
    def __init__(self, cfg: dict[str, Any] | None = None):
        self.cfg = cfg or load_config()
        self.db = Db(self.cfg)
        self.store = ProbStore(self.db, self.cfg)
        self.logger = get_logger(self.cfg)
        self.version = model_version(self.cfg)
        self.db.ensure_schema()

    # ------------------------------------------------------------------ 基础
    def prepare(self, asof: date | None = None, max_stocks: int = 0):
        md = MarketData(self.cfg, self.db).load(asof=asof, max_stocks=max_stocks)
        fs = build_features(md, self.cfg)
        if md.completeness_message:
            self.logger.warning("DATA_WARN %s", md.completeness_message)
        self.logger.info("PREPARE asof=%s dates=%s stocks=%s universe=%s excluded=%s warn=%s",
                         md.asof, len(md.dates), len(md.keys), len(md.universe),
                         md.excluded, md.warn_count)
        return md, fs

    def _start(self, run_type: str, asof: date | None, run_id: int | None = None,
               max_stocks: int = 0):
        return self.store.start_run(run_type, asof, self.version, run_id)

    def _progress(self, run_id, stage, *, callback=None, **values):
        """同时更新当前任务与可选父任务，父任务据此显示子任务的真实执行阶段。"""
        self.store.update_progress(run_id, stage, **values)
        if callback:
            callback(stage, **values)

    def period_grades(self) -> dict[int, str]:
        try:
            rows = self.db.query(
                "SELECT horizon,value FROM prob_eval_metric "
                "WHERE metric_name='reliability_grade_num' AND scope='ALL' "
                "AND eval_date=(SELECT MAX(eval_date) FROM prob_eval_metric)")
            return {int(r["horizon"]): "ABCD"[max(0, min(3, int(float(r["value"]))))]
                    for r in rows}
        except Exception:
            return {}

    def _keys_for_oos(self, md: MarketData) -> list[str]:
        n = int(self.cfg["walkforward"].get("max_stocks", 0) or 0)
        if n <= 0 or n >= len(md.universe):
            return md.universe
        rng = np.random.default_rng(int(self.cfg.get("seed_base", 20260101)))
        idx = np.sort(rng.choice(len(md.universe), size=n, replace=False))
        return [md.universe[i] for i in idx]

    def _train_all(self, md: MarketData, fs, positions_by_h: dict[int, list[int]],
                   keys: list[str], fusions: dict[int, Any] | None = None, progress=None):
        pooled: dict[int, PooledModels] = {}
        for index, h in enumerate(self.cfg["horizons"], 1):
            if progress:
                progress("TRAIN", message=f"正在训练周期 h{h}，第 {index}/{len(self.cfg['horizons'])} 个周期")
            pos_list = positions_by_h[h]
            end_pos = int(pos_list[-1]) if pos_list else len(md.dates) - 1
            pooled[h] = train_pooled(md, fs, self.cfg, h, pos_list, keys, end_pos,
                                     md.dates[end_pos], self.store, self.logger,
                                     fusion=(fusions or {}).get(h))
            if progress:
                progress("TRAIN", message=f"周期 h{h} 已完成，训练样本 {pooled[h].n_samples}，"
                         f"周期进度 {index}/{len(self.cfg['horizons'])}")
        return pooled

    def settle_all(self, max_batches: int = 30) -> int:
        total = 0
        for _ in range(max_batches):
            n = self.store.settle(self.version, self.cfg.get("__settle_to") or date.today())
            total += n
            if n == 0:
                break
        if total:
            self.logger.info("SETTLE rows=%s", total)
        return total

    # ------------------------------------------------------------------ 各命令
    def cmd_prepare(self, asof=None, max_stocks=0, run_id=None):
        t = self.cfg.get("__asof_to") or date.today()
        rid = self._start("PREPARE", None, run_id, max_stocks)
        try:
            self._progress(rid, "PREPARE", message="正在加载行情与生成特征")
            md, _ = self.prepare(asof, max_stocks)
            self._progress(rid, "PREPARE", asof=md.asof, n_universe=len(md.universe))
            self.store.finish_run(rid, "SUCCESS", n_universe=len(md.universe),
                                  n_ok=len(md.universe), excluded=md.excluded,
                                  warn_count=md.warn_count,
                                  message=f"asof={md.asof} 完整性={md.completeness:.1%}")
            return dict(runId=rid, asof=str(md.asof), nUniverse=len(md.universe),
                        excluded=md.excluded, dataWarnCount=md.warn_count,
                        completeness=md.completeness)
        except Exception as e:
            self.store.finish_run(rid, "FAILED", message=str(e))
            raise

    def cmd_train(self, asof=None, max_stocks=0, run_id=None):
        rid = self._start("TRAIN", None, run_id, max_stocks)
        try:
            self._progress(rid, "PREPARE", message="正在加载训练行情与特征")
            md, fs = self.prepare(asof, max_stocks)
            self._progress(rid, "TRAIN", asof=md.asof, n_universe=len(md.universe),
                           message="正在训练融合模型")
            fusions = {h: train_fusion_from_oos(self.db, self.cfg, h, self.logger)
                       for h in self.cfg["horizons"]}
            positions = {h: training_positions(self.cfg, len(md.dates), h)
                         for h in self.cfg["horizons"]}
            pooled = self._train_all(md, fs, positions, md.universe, fusions,
                                     progress=lambda stage, **v: self._progress(rid, stage, **v))
            self.store.finish_run(rid, "SUCCESS", n_universe=len(md.universe),
                                  n_ok=len(md.universe), warn_count=md.warn_count,
                                  message="合并模型训练完成：" +
                                          ", ".join(f"h{h}={pooled[h].n_samples}" for h in pooled))
            return {"runId": rid, "samples": {h: pooled[h].n_samples for h in pooled}}
        except Exception as e:
            self.store.finish_run(rid, "FAILED", message=str(e))
            raise

    def cmd_predict(self, asof=None, max_stocks=0, run_id=None, fast=False,
                    pred_type="LIVE"):
        rid = self._start("PREDICT", None, run_id, max_stocks)
        stats = dict(n_universe=0, n_ok=0, n_fail=0, excluded={}, warn_count=0)
        try:
            self._progress(rid, "PREPARE", message="正在加载预测行情与特征")
            md, fs = self.prepare(asof, max_stocks)
            stats.update(n_universe=len(md.universe), excluded=md.excluded, warn_count=md.warn_count)
            self._progress(rid, "TRAIN", asof=md.asof, n_universe=len(md.universe),
                           message="正在训练融合模型")
            self.cfg["__settle_to"] = md.asof
            fusions = {h: train_fusion_from_oos(self.db, self.cfg, h, self.logger)
                       for h in self.cfg["horizons"]}
            positions = {h: training_positions(self.cfg, len(md.dates), h)
                         for h in self.cfg["horizons"]}
            pooled = self._train_all(md, fs, positions, md.universe, fusions,
                                     progress=lambda stage, **v: self._progress(rid, stage, **v))

            def prediction_progress(value):
                # 异常退出时沿用最后一次已完成计数，避免覆盖此前已显示的实际进度。
                stats.update(n_ok=value["n_ok"], n_fail=value["n_fail"])
                self._progress(rid, "PREDICT", n_ok=value["n_ok"], n_fail=value["n_fail"],
                               message=f"股票 {value['processed']}/{value['total']}，"
                                       f"已生成 {value['n_ok']} 条")

            rows, states, stats = predict_cross_section(
                md, fs, pooled, self.cfg, md.date_pos(md.asof), pred_type,
                self.version, rid, fast=fast, period_grade=self.period_grades(),
                logger=self.logger, progress=prediction_progress)
            self.store.save_predictions(rows)
            self.store.save_model_states(states)
            fit_evt_market(self.db, self.cfg, date.today(), stats.get("evt_pools", []), self.logger)
            rate = stats["n_fail"] / max(stats["n_universe"], 1)
            if rate > float(self.cfg["engine"].get("fail_rate_alert", 0.05)):
                self.logger.warning("FAIL_RATE %.2f%% 超过阈值", rate * 100)
            status = "FAILED" if stats["n_fail"] else "SUCCESS"
            self.store.finish_run(rid, status, n_universe=stats["n_universe"],
                                  n_ok=len(rows), n_fail=stats["n_fail"],
                                  excluded=stats["excluded"], warn_count=stats["warn_count"],
                                  message=f"asof={md.asof} 预测 {len(rows)} 条，失败率 {rate:.2%}")
            return dict(runId=rid, asof=str(md.asof), rows=len(rows),
                        states=len(states), failed=stats["n_fail"], status=status)
        except Exception as e:
            self.store.finish_run(rid, "FAILED", n_universe=stats["n_universe"],
                                  n_ok=stats.get("n_ok", 0), n_fail=max(stats["n_fail"], 1),
                                  excluded=stats["excluded"], warn_count=stats["warn_count"],
                                  message=str(e))
            raise

    def cmd_walkforward(self, asof=None, max_stocks=0, run_id=None, resume=True, progress=None):
        rid = self._start("WALKFORWARD", None, run_id, max_stocks)
        total, failed, history_excluded = 0, 0, 0
        current_generated, current_failed = 0, 0
        keys, excluded, warn_count = [], {}, 0

        def report(stage, **values):
            self._progress(rid, stage, callback=progress, **values)

        try:
            report("PREPARE", message="正在加载样本外行情与特征")
            md, fs = self.prepare(asof, max_stocks)
            self.cfg["__settle_to"] = md.asof
            blocks = walkforward_blocks(md, self.cfg)
            keys = self._keys_for_oos(md)
            excluded, warn_count = dict(md.excluded), md.warn_count
            report("WALKFORWARD", asof=md.asof, n_universe=len(keys), n_ok=0, n_fail=0,
                   excluded=excluded, warn_count=warn_count,
                   message=f"股票池 {len(keys)} 只，共 {len(blocks)} 个样本外块")
            signature = data_signature(md, keys, blocks, self.version)
            # 断点随运行根隔离，避免验证库计算推进或覆盖生产的完成标记。
            ckpt = resolve_runtime_path(self.cfg["walkforward"].get(
                "checkpoint_file", "artifacts/walkforward_checkpoint.json"))
            done, completed_rows = 0, 0
            if resume and ckpt.exists():
                try:
                    state = json.loads(ckpt.read_text(encoding="utf-8"))
                    done, completed_rows = resume_progress(
                        state, signature, self.db, md, keys, blocks, self.version)
                    if done:
                        history_excluded = int(state.get("excluded_min_history", 0))
                except Exception:
                    # 断点损坏或结果不可核验时不跳过计算，避免报告不存在的完成进度。
                    done, completed_rows = 0, 0
            if not done:
                # 强制重跑或旧断点失效时先撤销旧完成标记。否则首块失败后，磁盘上可能
                # 仍保留同一数据身份的旧“全部完成”断点，下次恢复会把失败重跑错误跳过。
                ckpt.parent.mkdir(parents=True, exist_ok=True)
                pending_checkpoint = ckpt.with_suffix(ckpt.suffix + ".tmp")
                pending_checkpoint.write_text(json.dumps(dict(
                    schema=CHECKPOINT_SCHEMA, data_signature=signature, done=0,
                    completed_rows=0, updated=datetime.now().isoformat())), encoding="utf-8")
                pending_checkpoint.replace(ckpt)
            h_max = max(self.cfg["horizons"])
            init_end = max(int(self.cfg.get("warmup_days", 60)), blocks[0]["test_start"] - h_max - 1)
            init_positions = {h: list(range(int(self.cfg.get("warmup_days", 60)),
                                            max(int(self.cfg.get("warmup_days", 60)) + 1,
                                                init_end - h + 1)))
                              for h in self.cfg["horizons"]}
            pooled = self._train_all(md, fs, init_positions, keys, progress=report)
            for bi, block in enumerate(blocks):
                if bi < done:
                    continue
                report("WALKFORWARD", n_ok=total, n_fail=failed,
                       message=f"样本外块 {bi + 1}/{len(blocks)}：训练融合模型；"
                               f"本次已写入 {total} 条，恢复已有 {completed_rows} 条")
                # 滚动 L2：只用当前测试块之前已结算的 OOS 训练融合与校准
                fusions = {}
                for h in self.cfg["horizons"]:
                    fusions[h] = train_fusion_from_oos(self.db, self.cfg, h, self.logger,
                                                       before=md.dates[block["test_start"]])
                    pooled[h].m10 = fusions[h]
                for pos in range(block["test_start"], block["test_end"] + 1):
                    current_generated, current_failed = 0, 0

                    def prediction_progress(value):
                        # n_ok 为本次已完成的预测行；消息区分当前日期与此前已写入的行数。
                        nonlocal current_generated, current_failed
                        current_generated, current_failed = value["n_ok"], value["n_fail"]
                        report("WALKFORWARD", n_ok=total + value["n_ok"],
                               n_fail=failed + value["n_fail"],
                               message=f"样本外块 {bi + 1}/{len(blocks)}，日期 {md.dates[pos]}，"
                                       f"股票 {value['processed']}/{value['total']}，"
                                       f"当日历史不足排除 {value['excluded'].get('oos_min_history', 0)} 只")

                    rows, states, stats = predict_cross_section(
                        md, fs, pooled, self.cfg, pos, "OOS", self.version, rid,
                        fast=bool(self.cfg.get("oos_fast", True)), keys=keys,
                        period_grade=self.period_grades(), logger=self.logger,
                        progress=prediction_progress)
                    # 即使保存明细时失败，也必须保留预测阶段已经确认的真实个股失败数。
                    current_generated, current_failed = len(rows), stats["n_fail"]
                    self.store.save_predictions(rows)
                    total += len(rows)
                    failed += current_failed
                    # 本日结果已计入累计值，立即清零，避免后续结算异常再次累加同一批失败。
                    current_generated, current_failed = 0, 0
                    history_excluded += stats["excluded"].get("oos_min_history", 0)
                excluded["oos_min_history_stock_dates"] = history_excluded
                report("SETTLE", n_ok=total, n_fail=failed, excluded=excluded,
                       message=f"样本外块 {bi + 1}/{len(blocks)} 预测完成，正在结算；"
                               f"历史不足累计排除 {history_excluded} 股票日次数")
                self.settle_all()
                ckpt.parent.mkdir(parents=True, exist_ok=True)
                # 写入临时文件后原子替换，防止进程中断留下半个 JSON；不改变预测统计口径。
                checkpoint_state = dict(schema=CHECKPOINT_SCHEMA, data_signature=signature,
                                        asof=str(md.asof), keys=len(keys), done=bi + 1,
                                        completed_rows=completed_rows + total,
                                        excluded_min_history=history_excluded,
                                        updated=datetime.now().isoformat())
                # 一旦出现真实计算失败，不再推进断点；下次可从失败块之前恢复，避免把
                # 部分结果当作完整块永久跳过。历史资格排除属于预期行为，不阻止断点提交。
                if not failed:
                    pending_checkpoint = ckpt.with_suffix(ckpt.suffix + ".tmp")
                    pending_checkpoint.write_text(json.dumps(checkpoint_state, ensure_ascii=False),
                                                  encoding="utf-8")
                    pending_checkpoint.replace(ckpt)
                self.logger.info("WALKFORWARD block %s/%s done rows=%s",
                                 bi + 1, len(blocks), total)
            status = "FAILED" if failed else "SUCCESS"
            self.store.finish_run(rid, status, n_universe=len(keys), n_ok=total, n_fail=failed,
                                  excluded=excluded, warn_count=warn_count,
                                  message=f"{len(blocks)} 个样本外块，本次 {total} 条 OOS 预测；"
                                          f"失败 {failed} 股票日次数，历史不足排除 {history_excluded} 股票日次数")
            return dict(runId=rid, blocks=len(blocks), rows=total, keys=len(keys),
                        failed=failed, status=status, excluded=excluded)
        except Exception as e:
            # 完整堆栈先脱敏后截断，避免仅保存半段连接串时泄漏认证信息。
            self.store.finish_run(rid, "FAILED", n_universe=len(keys), n_ok=total,
                                  n_fail=max(failed + current_failed, 1),
                                  excluded=excluded, warn_count=warn_count,
                                  message=f"已确认写入 {total} 条；当前日期已生成 {current_generated} 条，"
                                          f"尚未确认全部写入。{e}\n"
                                          f"{redact_text(traceback.format_exc())[-800:]}")
            raise

    def cmd_settle(self, run_id=None):
        rid = self._start("SETTLE", None, run_id)
        try:
            n = self.settle_all()
            self.store.finish_run(rid, "SUCCESS", n_ok=n, message=f"结算 {n} 条")
            return dict(runId=rid, rows=n)
        except Exception as e:
            self.store.finish_run(rid, "FAILED", message=str(e))
            raise

    def cmd_evaluate(self, run_id=None):
        rid = self._start("EVAL", None, run_id)
        try:
            result = evaluate(self.db, self.cfg, date.today(), self.logger)
            self.store.finish_run(rid, "SUCCESS", n_ok=result.get("n", 0),
                                  message=json.dumps(result, ensure_ascii=False))
            return dict(runId=rid, **result)
        except Exception as e:
            self.store.finish_run(rid, "FAILED", message=str(e))
            raise

    def cmd_backtest(self, run_id=None, max_stocks=0):
        rid = self._start("BACKTEST", None, run_id)
        try:
            md, _ = self.prepare(None, max_stocks)
            self._progress(rid, "BACKTEST", asof=md.asof, n_universe=len(md.universe),
                           message="正在回测已生成的预测")
            bid = run_backtest(md, self.db, self.cfg, rid, self.logger)
            self.store.finish_run(rid, "SUCCESS", n_ok=int(bid is not None),
                                  message=f"backtest_id={bid}")
            return dict(runId=rid, backtestId=bid)
        except Exception as e:
            self.store.finish_run(rid, "FAILED", message=str(e))
            raise

    # ------------------------------------------------------------------ 一键日常
    def cmd_daily(self, asof=None, max_stocks=0, run_id=None, force=False, resume=True):
        """prepare →（按间隔）walkforward/train → LIVE 预测 → settle → evaluate → backtest。"""
        rid = self._start("PREDICT", None, run_id, max_stocks)
        steps: list[str] = []
        wf_failed, live_failed, live_rows, universe_size = 0, 0, 0, 0
        excluded, warn_count = {}, 0
        try:
            with task_timer(self.logger, "daily", asof=str(asof), version=self.version) as t:
                self._progress(rid, "PREPARE", message="正在加载日常任务行情与特征")
                md, fs = self.prepare(asof, max_stocks)
                self.cfg["__settle_to"] = md.asof
                universe_size = len(md.universe)
                excluded, warn_count = dict(md.excluded), md.warn_count
                self._progress(rid, "PREPARE", asof=md.asof, n_universe=universe_size,
                               n_ok=0, n_fail=0, excluded=excluded, warn_count=warn_count,
                               message=f"行情基准日 {md.asof}，可预测股票 {universe_size} 只")
                steps.append("prepare")

                # 是否需要（重新）生成样本外：没有 OOS 或距上次超过 retrain_every 个交易日
                n_oos = self.db.scalar(
                    "SELECT COUNT(*) FROM prob_prediction WHERE pred_type='OOS' "
                    # 审计排除的历史记录保留原值，但不能被当成足量的可用样本。
                    "AND status IN ('PENDING','SETTLED')") or 0
                last_wf = self.db.scalar(
                    "SELECT MAX(asof_date) FROM prob_run WHERE run_type='WALKFORWARD' "
                    "AND status='SUCCESS'")
                gap = 999
                if last_wf:
                    try:
                        later = [d for d in md.dates if d > last_wf]
                        gap = len(later)
                    except Exception:
                        gap = 999
                # 用户明确禁用断点时重新执行滚动评估，DAILY 与独立 WALKFORWARD 参数一致。
                need_wf = (not resume) or force or n_oos < 1000 or gap >= int(self.cfg.get("retrain_every", 20))
                if need_wf:
                    def wf_progress(stage, **values):
                        # 父任务保留完整 LIVE 股票池，消息和中间计数明确对应当前样本外阶段。
                        nonlocal wf_failed
                        wf_failed = int(values.get("n_fail", wf_failed))
                        values["n_universe"] = universe_size
                        self._progress(rid, f"OOS/{stage}", **values)

                    wf_result = self.cmd_walkforward(asof, max_stocks, None, resume=resume,
                                                     progress=wf_progress)
                    wf_failed = int(wf_result.get("failed", 0))
                    excluded["oos_min_history_stock_dates"] = wf_result.get("excluded", {}).get(
                        "oos_min_history_stock_dates", 0)
                    steps.append("walkforward")
                else:
                    steps.append("walkforward(skip)")
                t["ok"] += 1
                t["fail"] += wf_failed

                self._progress(rid, "SETTLE", n_fail=wf_failed,
                               message=f"样本外处理完成（失败 {wf_failed} 股票日次数），正在结算")
                self.settle_all()
                self._progress(rid, "EVALUATE", message="正在评估已结算的样本外预测")
                eval_result = evaluate(self.db, self.cfg, date.today(), self.logger)
                grades = self.period_grades()
                steps.append("evaluate")
                t["ok"] += 1

                # 开始 LIVE 阶段后 n_ok 重新统计实时预测行；此前 OOS 数量在消息与子任务保留。
                self._progress(rid, "TRAIN", n_ok=0, n_fail=wf_failed, excluded=excluded,
                               message="样本外评估完成，正在训练实时融合模型")
                fusions = {h: train_fusion_from_oos(self.db, self.cfg, h, self.logger)
                           for h in self.cfg["horizons"]}
                positions = {h: training_positions(self.cfg, len(md.dates), h)
                             for h in self.cfg["horizons"]}
                pooled = self._train_all(md, fs, positions, md.universe, fusions,
                                         progress=lambda stage, **v: self._progress(rid, stage, **v))
                steps.append("train")

                def live_progress(value):
                    # 出错时也保留最近一次实际计数，不让异常处理把已完成成果重置为零。
                    nonlocal live_failed, live_rows
                    live_failed, live_rows = value["n_fail"], value["n_ok"]
                    self._progress(rid, "PREDICT", n_ok=live_rows, n_fail=wf_failed + live_failed,
                                   message=f"LIVE 股票 {value['processed']}/{value['total']}，"
                                           f"已生成 {live_rows} 条；OOS 失败 {wf_failed} 股票日次数")

                rows, states, stats = predict_cross_section(
                    md, fs, pooled, self.cfg, md.date_pos(md.asof), "LIVE",
                    self.version, rid, fast=False, period_grade=grades, logger=self.logger,
                    progress=live_progress)
                self.store.save_predictions(rows)
                self.store.save_model_states(states)
                live_failed, live_rows = stats["n_fail"], len(rows)
                steps.append(f"predict:{len(rows)}")
                t["ok"] += len(rows)
                t["fail"] += stats["n_fail"]

                self._progress(rid, "SETTLE", n_ok=live_rows, n_fail=wf_failed + live_failed,
                               message=f"LIVE 已写入 {live_rows} 条，正在结算")
                settled = self.settle_all()
                steps.append(f"settle:{settled}")
                self._progress(rid, "EVALUATE", message="正在更新评估指标")
                evaluate(self.db, self.cfg, date.today(), self.logger)
                self._progress(rid, "EVT", message="正在更新市场尾部风险估计")
                evt = fit_evt_market(self.db, self.cfg, date.today(),
                                     stats.get("evt_pools", []), self.logger)
                steps.append(f"evt:{len(evt)}")
                self._progress(rid, "BACKTEST", message="正在回测已生成的预测")
                bid = run_backtest(md, self.db, self.cfg, rid, self.logger)
                steps.append(f"backtest:{bid}")
                # 预期的历史资格排除不算失败；真实 OOS/LIVE 错误都必须体现在父任务终态。
                status = "FAILED" if wf_failed + live_failed else "SUCCESS"
                self.store.finish_run(
                    rid, status, n_universe=stats["n_universe"], n_ok=len(rows),
                    n_fail=wf_failed + live_failed, excluded=excluded,
                    warn_count=stats["warn_count"],
                    message=" | ".join(steps) + f" | OOS={eval_result.get('n', 0)}"
                            f" | OOS失败={wf_failed} 股票日次数，LIVE失败={live_failed} 只")
            return dict(runId=rid, asof=str(md.asof), steps=steps, rows=len(rows),
                        oos=eval_result.get("n", 0), backtestId=bid,
                        failed=wf_failed + live_failed, status=status)
        except Exception as e:
            # 与独立滚动评估入口保持一致，截断前先移除连接串凭据。
            self.store.finish_run(rid, "FAILED", n_universe=universe_size, n_ok=live_rows,
                                  n_fail=max(wf_failed + live_failed, 1), excluded=excluded,
                                  warn_count=warn_count,
                                  message=f"{e}\n{redact_text(traceback.format_exc())[-800:]}")
            self.logger.exception("DAILY FAILED")
            raise
