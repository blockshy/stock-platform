"""基于已捕获文件诊断复权公式候选；没有联网、写库或训练入口。

官方本地教程的乘法只作为待证候选。保留完整因子精度、字段差异及边界证据，
不把缺因子补成 1，不按最吻合的候选自动切换生产，也不将样本匹配声明为模型等效。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from bisect import bisect_right
from collections import Counter
from datetime import date
from decimal import Decimal, ROUND_HALF_EVEN, ROUND_HALF_UP, localcontext
from pathlib import Path

from compare import (ROOT, canonical_daily_row, compare_daily,
                     required, storage_projection)
from evaluate_probe import (STOCK, daily_rows, evaluate_capture, factor_rows, identity,
                            read_capture, trading_dates)

PRICES = ("open", "high", "low", "close", "preclose")
ROUNDINGS = {"half-up-6": ROUND_HALF_UP, "half-even-6": ROUND_HALF_EVEN}
# 所有候选在读数据之前固定。不同精度或昨收政策不是“放宽容差”，必须各自完整报差异。
CANDIDATES = (
    dict(name="multiply-exact", price_rounding=None, factor_rounding=None, preclose_policy="same-day"),
    dict(name="multiply-half-up-6", price_rounding="half-up-6", factor_rounding=None, preclose_policy="same-day"),
    dict(name="multiply-half-even-6", price_rounding="half-even-6", factor_rounding=None, preclose_policy="same-day"),
    dict(name="tutorial-six-digit-diagnostic", price_rounding="half-even-6", factor_rounding="half-even-6", preclose_policy="same-day"),
    dict(name="previous-session-preclose-diagnostic", price_rounding="half-up-6", factor_rounding=None, preclose_policy="previous-session"),
)


def row_key(row):
    """跨复权模式对齐只去除 adjustflag；模式本身由调用方严格校验，不可用它隐藏错标。"""
    return tuple(required(row, field) for field in ("code", "market", "trade_date"))


def index_rows(rows, flag):
    """禁止重复行、错误复权标记和 ETF 混入；完整集合差异留给比较器报告。"""
    result = {}
    for row in rows:
        key = row_key(row)
        if row["adjustflag"] != flag or not STOCK.fullmatch(row["market"].lower() + "." + row["code"]):
            raise ValueError("复权候选只接受明确标记的支持股票行情")
        if key in result:
            raise ValueError("复权候选输入含重复业务键")
        result[key] = row
    return result


def build_factor_index(rows, observed_on):
    """沿用三项原始因子严格字段契约，完整保留精度；未来事件不能充当历史的有效因子。"""
    date.fromisoformat(observed_on)
    result = {}
    for row in factor_rows(rows):
        if row["dividOperateDate"] > observed_on:
            raise ValueError("因子事件晚于采样观察日")
        result.setdefault(row["code"], []).append(row)
    for events in result.values():
        events.sort(key=lambda event: event["dividOperateDate"])
    return result


def effective_factor(events, day):
    """只允许使用 <= 行情日的最后一条事件；缺期初因子时返回缺失，绝不补 1。"""
    offset = bisect_right([event["dividOperateDate"] for event in events], day) - 1
    return events[offset] if offset >= 0 else None


def multiply_price(value, factor, price_rounding=None, factor_rounding=None):
    """乘法使用足够的 Decimal 上下文；六位量化必须显式声明，不能静默截因子或价格。

    HALF_EVEN 只是十进制定点的候选，不能声称重现 pandas 二进制浮点 round 的全部行为。
    源空值保持空值，由验收门槛记录；本函数不会将空值替换成 0。
    """
    for rounding in (price_rounding, factor_rounding):
        if rounding is not None and rounding not in ROUNDINGS:
            raise ValueError("未知的显式量化策略")
    if value is None:
        return None
    with localcontext() as context:
        context.prec = max(80, len(value.as_tuple().digits) + len(factor.as_tuple().digits)
                           + abs(value.as_tuple().exponent) + abs(factor.as_tuple().exponent) + 16)
        if factor_rounding:
            factor = factor.quantize(Decimal("0.000001"), rounding=ROUNDINGS[factor_rounding])
        adjusted = value * factor
        return adjusted.quantize(Decimal("0.000001"), rounding=ROUNDINGS[price_rounding]) if price_rounding else adjusted


def reconstruct_candidate(raw, factors, flag, observed_on, config, sessions=()):
    """生成独立的候选行与逐行因子溯源；成交量、金额和非价格字段按原始值保留并另行比较。

    previous-session 仅诊断除权昨收的另一种假设。上一交易日必须由完整日历提供，
    不能用“上一条已返回行情”替代，否则停牌或漏行会改变因子选择。
    """
    if flag not in (1, 2) or config.get("preclose_policy") not in {"same-day", "previous-session"}:
        raise ValueError("复权候选标记或昨收策略不受支持")
    rows = index_rows(raw, 3)
    timelines = build_factor_index(factors, observed_on)
    sessions = sorted(set(sessions))
    factor_field = "foreAdjustFactor" if flag == 2 else "backAdjustFactor"
    result, provenance, missing = [], {}, []
    for key, row in sorted(rows.items()):
        code, day = row["market"].lower() + "." + row["code"], row["trade_date"]
        events = timelines.get(code, [])
        selected = effective_factor(events, day)
        if selected is None:
            missing.append(dict(code=code, date=day, reason="initial_factor_missing"))
            continue
        preclose_event = selected
        if config["preclose_policy"] == "previous-session":
            offset = bisect_right(sessions, day) - 1
            if offset < 1 or sessions[offset] != day:
                missing.append(dict(code=code, date=day, reason="previous_calendar_session_missing"))
                continue
            preclose_event = effective_factor(events, sessions[offset - 1])
            if preclose_event is None:
                missing.append(dict(code=code, date=day, reason="previous_session_factor_missing"))
                continue
        candidate = dict(row, adjustflag=flag)
        provenance[key] = {}
        for field in PRICES:
            event = preclose_event if field == "preclose" else selected
            factor = event[factor_field]
            candidate[field] = multiply_price(row[field], factor, config.get("price_rounding"), config.get("factor_rounding"))
            provenance[key][field] = dict(effectiveDate=event["dividOperateDate"], sourceFactor=factor)
        result.append(candidate)
    return result, provenance, missing


def boundary_evidence(raw, reference, factors, observed_on, sessions, start, end):
    """逐事件检查前一交易日、生效交易日、后一交易日的双侧行情；不以相邻已返回行代替。

    日历或窗口没有覆盖边界邻日时仍为 INCOMPLETE；没有任何窗口内事件为 NOT_EXERCISED，
    不能把普通交易日上的匹配扩大成除权边界已经通过。
    """
    raw_keys, reference_keys = {row_key(row) for row in raw}, {row_key(row) for row in reference}
    timelines, sessions = build_factor_index(factors, observed_on), sorted(set(sessions))
    output = []
    for code, events in sorted(timelines.items()):
        for event in events:
            day = event["dividOperateDate"]
            if not start <= day <= end:
                continue
            position = bisect_right(sessions, day) - 1
            if position < 0 or sessions[position] != day:
                position += 1
            dates = {"before": sessions[position - 1] if position > 0 else None,
                     "effective": sessions[position] if position < len(sessions) else None,
                     "after": sessions[position + 1] if position + 1 < len(sessions) else None}
            missing = []
            for role, session in dates.items():
                key = (code.split(".")[1], code[:2].upper(), session)
                if session is None or key not in raw_keys or key not in reference_keys:
                    missing.append(role)
            output.append(dict(code=code, eventDate=day, sessions=dates, missing=missing,
                               status="COMPLETE_SAMPLE" if not missing else "INCOMPLETE"))
    status = "NOT_EXERCISED" if not output else "INCOMPLETE" if any(row["missing"] for row in output) else "COMPLETE_SAMPLE"
    return dict(status=status, events=output)


def precision_evidence(reference, candidate, provenance):
    """分别统计源级价格误差和落库四位掩盖数量；金额/成交量保留独立精确检查。

    所有差异完整计数，仅详细例子限制为 30 条。数据库投影仅用于定位精度影响，
    不能替代真正 MySQL 回读，也不会把原始差异重新标为通过。
    """
    reference_map = {row_key(row): row for row in reference}
    candidate_map = {row_key(row): row for row in candidate}
    common = sorted(set(reference_map) & set(candidate_map))
    output, examples = {}, []
    for field in (*PRICES, "volume", "amount"):
        stats = dict(compared=0, exactMismatches=0, nullPairs=0, nullMismatches=0,
                     maskedByStorageProjection=0, maxAbsoluteDifference=Decimal(0))
        for key in common:
            expected, actual = reference_map[key][field], candidate_map[key][field]
            stats["compared"] += 1
            if expected is None and actual is None:
                stats["nullPairs"] += 1
            if expected == actual:
                continue
            stats["exactMismatches"] += 1
            delta = None
            if expected is None or actual is None:
                stats["nullMismatches"] += 1
            else:
                with localcontext() as context:
                    context.prec = max(80, len(expected.as_tuple().digits) + len(actual.as_tuple().digits)
                                       + abs(expected.as_tuple().exponent) + abs(actual.as_tuple().exponent) + 16)
                    delta = abs(expected - actual)
                stats["maxAbsoluteDifference"] = max(stats["maxAbsoluteDifference"], delta)
                if storage_projection(reference_map[key])[field] == storage_projection(candidate_map[key])[field]:
                    stats["maskedByStorageProjection"] += 1
            if len(examples) < 30:
                examples.append(dict(code=key[1].lower() + "." + key[0], date=key[2], field=field,
                                     reference=expected, candidate=actual, absoluteDifference=delta,
                                     factor=provenance.get(key, {}).get(field)))
        output[field] = stats
    return dict(fields=output, examples=examples)


def assess_candidate(raw, reference, factors, flag, observed_on, config, *, sessions=(),
                     start, end, expected_keys=None):
    """组合公式、集合、边界和精度证据；价格空值与已实测的停牌量额双空分别处理。"""
    reference_map = index_rows(reference, flag)
    candidate, provenance, missing = reconstruct_candidate(raw, factors, flag, observed_on, config, sessions)
    if not raw or not reference:
        return dict(name=config["name"], status="NOT_RUN", reason="缺少非空原始或逐股复权参考行")
    comparison = compare_daily(reference, candidate, expected_keys=expected_keys, allow_empty=True)
    precision = precision_evidence(reference, candidate, provenance)
    matched_null_keys, matched_source_nulls = set(), []
    for row in raw:
        key, target = row_key(row), reference_map.get(row_key(row))
        # 已由真实逐股与批量接口共同核实的窄契约：双方停牌且量额同时为空。
        # 只记录精确匹配的源空值；正常交易双空、停牌单空和价格空值仍是缺项。
        if (target is not None and row["tradestatus"] == target["tradestatus"] == Decimal(0)
                and all(item[field] is None for item in (row, target) for field in ("volume", "amount"))):
            matched_null_keys.add(key)
            matched_source_nulls.append(dict(code=key[1].lower() + "." + key[0], date=key[2],
                                             fields=["volume", "amount"], reason="verified_suspended_pair"))

    def unexplained_nulls(rows):
        return sum(row[field] is None and not (field in {"volume", "amount"} and row_key(row) in matched_null_keys)
                   for row in rows for field in (*PRICES, "volume", "amount"))

    source_nulls, reference_nulls = unexplained_nulls(raw), unexplained_nulls(reference)
    matched = comparison["status"] != "FAIL"
    status = "INCOMPLETE" if missing or source_nulls or reference_nulls else "MATCHED_SAMPLE" if matched else "MISMATCH"
    return dict(name=config["name"], status=status, formula="price * latest factor on/before selected session",
                configuration=config, comparison=comparison, missingFactors=missing,
                sourceCriticalNulls=source_nulls, referenceCriticalNulls=reference_nulls,
                matchedSourceNulls=matched_source_nulls,
                precision=precision,
                boundaries=boundary_evidence(raw, reference, factors, observed_on, sessions, start, end),
                completeness="INDEPENDENT_KEYS_CHECKED" if expected_keys is not None else "INDEPENDENT_KEYS_NOT_PROVIDED")


def evaluate_adjusted_capture(path):
    """适配同轮 Java 探针文件：逐股原始轨检验公式，批量轨检验真实采集路径，两者分开报告。"""
    # 先执行既有严格来源核对，复用其摘要、观察日、UUID、自然日历与原始集合门槛。
    capture_digest = hashlib.sha256(Path(path).read_bytes()).hexdigest()
    source_report = evaluate_capture(path)
    capture, snapshots, _ = read_capture(path)
    # 捕获进程可能仍在替换报告；两轮读取必须来自相同执行，不能拼接不同 UUID 的证据。
    if (capture["adjustedCaptureId"] != source_report["adjustedCaptureId"]
            or hashlib.sha256(Path(path).read_bytes()).hexdigest() != capture_digest):
        raise ValueError("读取期间捕获报告发生变化，请待采样完成后重试")
    plan, observed = capture["plan"], capture["observedOn"]
    start, end = plan["from"], plan["to"]
    skipped_days = {item["date"] for item in source_report["skippedDates"]}
    calendar = snapshots.get(identity("calendar", {"from": start, "to": end}))
    sessions = trading_dates(calendar["rows"], start, end) if calendar else set()
    checks, missing = [], []
    for code in plan["codes"]:
        if not STOCK.fullmatch(code):
            checks.append(dict(code=code, status="NOT_APPLICABLE", reason="58 ETF 保持原始行情，独立原始字段验收"))
            continue
        factor_snapshot = snapshots.get(identity("factors", dict(code=code, **{"from": "1990-12-19", "to": observed})))
        for flag in (2, 1):
            raw_snapshot = snapshots.get(identity("history", dict(code=code, **{"from": start, "to": end}, adjustflag="3")))
            reference_snapshot = snapshots.get(identity("history", dict(code=code, **{"from": start, "to": end}, adjustflag=str(flag))))
            if not all(item is not None for item in (raw_snapshot, reference_snapshot, factor_snapshot)):
                missing.append(dict(code=code, adjustflag=flag, status="NOT_RUN", reason="原始历史、复权历史或本轮因子快照未齐"))
                continue
            raw = [canonical_daily_row(row, code=code) for row in raw_snapshot["rows"]]
            reference = [canonical_daily_row(row, code=code) for row in reference_snapshot["rows"]]
            factors = factor_snapshot["rows"]
            batches, expected, available_days, universe_known = [], [], set(), bool(calendar)
            for day in plan["dates"]:
                if day in skipped_days:
                    continue
                batch = snapshots.get(identity("daily-stock", {"date": day}))
                listed = snapshots.get(identity("stock-list", {"date": day}))
                if batch is None or listed is None:
                    universe_known = False
                    continue
                available_days.add(day)
                batches.extend(row for row in daily_rows(batch["rows"], day, "stock", Counter())
                               if row["market"].lower() + "." + row["code"] == code)
                if day in sessions and code in {row["code"] for row in listed["rows"]}:
                    expected.append(dict(code=code.split(".")[1], market=code[:2].upper(), trade_date=day, adjustflag=flag))
            # 缺少整日 batch 快照只表示该日尚未比较，不把它解释成候选公式丢失该日行情。
            selected_reference = [row for row in reference if row["trade_date"] in available_days]
            for basis, input_rows, reference_rows, keys in (("per-stock-raw-formula-diagnostic", raw, reference, None),
                                                           ("batch-raw-sample", batches, selected_reference, expected if universe_known else None)):
                results = [assess_candidate(input_rows, reference_rows, factors, flag, observed, config,
                                            sessions=sessions, start=start, end=end, expected_keys=keys) for config in CANDIDATES]
                checks.append(dict(code=code, adjustflag=flag, basis=basis, candidates=results,
                                   deferredBatchDates=sorted(set(plan["dates"]) - skipped_days - available_days) if basis == "batch-raw-sample" else []))
    status = "FAILED" if source_report["status"] == "FAILED" else "PARTIAL" if any("candidates" in check for check in checks) else "NOT_RUN"
    return dict(status=status, offline=True,
                scope="ADJUSTMENT_CANDIDATE_DIAGNOSTICS_ONLY", observedOn=observed,
                adjustedCaptureId=capture["adjustedCaptureId"], capturedStatus=capture.get("status"),
                captureReportSha256=capture_digest, sourceEvidenceStatus=source_report["status"],
                sourceIssues=source_report["issues"], skippedDates=source_report["skippedDates"], missingSnapshots=missing, checks=checks,
                inputSnapshots=[dict(operation=saved["operation"], parameters=saved["parameters"],
                                     key=saved["key"], rowsSha256=saved["rowsSha256"]) for saved in snapshots.values()],
                selectedFormula=None, adjustmentFormula="NOT_ACCEPTED", modelParity="NOT_RUN", cutoverAllowed=False,
                limitation="候选匹配仅覆盖已提供样本；全市场完整性、除权边界、实际落库与全部模型同输入重算仍需独立验收。")


def main():
    """报告仅写入本仓库私有 .local；禁止覆盖原始请求快照或捕获清单。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    target = args.output.resolve()
    if not target.is_relative_to((ROOT / ".local").resolve()) or target == args.capture.resolve() or re.fullmatch(r"[0-9a-f]{64}\.json", target.name):
        parser.error("报告只能写入本仓库 .local，且不得覆盖原始快照")
    try:
        report = evaluate_adjusted_capture(args.capture)
    except (ValueError, KeyError, TypeError, OSError):
        report = dict(status="INVALID_INPUT", offline=True, modelParity="NOT_RUN", cutoverAllowed=False,
                      message="输入身份、字段、因子或日期未通过严格核对；未联网补齐。")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("status", "modelParity", "cutoverAllowed")}))
    return 0 if report["status"] == "PARTIAL" else 1


if __name__ == "__main__":
    raise SystemExit(main())
