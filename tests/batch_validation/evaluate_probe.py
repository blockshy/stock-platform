"""离线读取 BaoStockBatchProbe 快照，核对样本字段、逐日证券集合和因子证据。

不发请求、不推算复权价格、不启动模型。即使所有已执行检查通过，报告仍为 PARTIAL，
因为代表样本不是全市场三年完整计算验收；原始文件不会被修改。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter
from datetime import date, timedelta
from pathlib import Path
from uuid import UUID

from compare import (ROOT, audit_factor_coverage, canonical_daily_row, compare_daily,
                     compare_values, decimal_value, load_snapshot, required)

STOCK = re.compile(r"(?:sz\.(?:00|30)|sh\.60)\d{4}")
ETF = re.compile(r"sh\.58\d{4}")
FACTOR_FIELDS = {"code", "dividOperateDate", "foreAdjustFactor", "backAdjustFactor", "adjustFactor"}
ETF_SOURCE_VALUATIONS = {"peTTM", "pbMRQ", "psTTM", "pcfNcfTTM"}


def compact_sha(value):
    """供应方快照都是字符串 Map；保持 Java LinkedHashMap 字段顺序与 UTF-8 紧凑 JSON 摘要。"""
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")).hexdigest()


def identity(operation, parameters):
    """请求身份与调用顺序无关，参数必须精确相同，不能把同日其他接口的缓存当作当前结果。"""
    return operation, json.dumps(parameters, sort_keys=True, separators=(",", ":"))


def read_capture(path):
    """核对快照真实路径、完整标记、请求身份和原始字符串摘要；拒绝损坏/越界/重复缓存。"""
    path = Path(path).resolve()
    report, snapshots, timings = load_snapshot(path), {}, []
    if report.get("schema") != 1 or report.get("scope") != "SOURCE_SNAPSHOTS_ONLY":
        raise ValueError("不支持的采样报告版本或范围")
    if report.get("cutoverAllowed") is not False or report.get("modelParity") != "NOT_RUN":
        raise ValueError("采样报告错误声明了生产切换或模型验收")
    # 观察日不是上游复权版本：同日重启也必须使用本轮独立 UUID，防止旧因子混入新采样。
    capture_id = required(report, "adjustedCaptureId")
    if not isinstance(capture_id, str) or str(UUID(capture_id)) != capture_id:
        raise ValueError("复权采样身份必须为规范 UUID")
    for request in required(report, "requests"):
        name = required(request, "file")
        if not re.fullmatch(r"[0-9a-f]{64}\.json", name):
            raise ValueError("快照文件名不是请求摘要")
        file = (path.parent / name).resolve()
        if not file.is_relative_to(path.parent):
            raise ValueError("快照文件越过隔离目录")
        saved = load_snapshot(file)
        rows = required(saved, "rows")
        if not isinstance(rows, list) or any(not isinstance(r, dict) or any(not isinstance(v, str) for v in r.values()) for r in rows):
            raise ValueError("源响应必须是保留字符串精度的行数组")
        if saved.get("complete") is not True or saved.get("contract") != "baostock-batch-v2":
            raise ValueError("快照传输未完成或契约不匹配")
        operation, params = required(request, "operation"), required(request, "parameters")
        if operation not in {"calendar", "stock-list", "daily-stock", "daily-etf", "daily-factors", "history", "factors"}:
            raise ValueError("采样报告包含未知请求类型")
        if operation != saved.get("operation") or params != saved.get("parameters"):
            raise ValueError("报告请求与快照身份不一致")
        key_data = dict(contract=saved["contract"], operation=operation, parameters=dict(sorted(params.items())))
        if "observedOn" in saved:
            key_data["observedOn"] = saved["observedOn"]
        # 字段顺序与 Java LinkedHashMap 一致；原始快照没有本轮字段，仍可验证后跨执行复用。
        if "adjustedCaptureId" in saved:
            key_data["adjustedCaptureId"] = saved["adjustedCaptureId"]
        if compact_sha(key_data) != name[:-5] or saved.get("key") != name[:-5]:
            raise ValueError("快照身份摘要不一致")
        if compact_sha(rows) != saved.get("rowsSha256") or request.get("rows") != len(rows):
            raise ValueError("快照行摘要或行数不一致")
        key = identity(operation, params)
        if key in snapshots:
            raise ValueError("采样报告重复引用同一请求")
        snapshots[key] = saved
        timings.append(dict(operation=operation, cached=bool(request.get("cached")), rows=len(rows),
                            originalElapsedMillis=saved.get("elapsedMillis")))
    validated_skipped_dates(report, snapshots)
    return report, snapshots, timings


def factor_rows(rows, *, expected_code=None, expected_day=None):
    """保留三项因子原值；不假定 adjustFactor 与前/后复权因子之间的乘法关系。"""
    result, keys = [], set()
    for row in rows:
        if set(row) != FACTOR_FIELDS:
            raise ValueError("因子字段集合不完整或有未审查变化")
        code, day = row["code"], row["dividOperateDate"]
        if not re.fullmatch(r"(?:sh|sz|bj)\.\d{6}", code):
            raise ValueError("因子证券代码无效")
        date.fromisoformat(day)
        if expected_code and code != expected_code or expected_day and day != expected_day:
            raise ValueError("因子证券或事件日期偏离请求")
        if (code, day) in keys:
            raise ValueError("因子出现重复事件")
        keys.add((code, day))
        item = dict(row)
        for field in FACTOR_FIELDS - {"code", "dividOperateDate"}:
            value = decimal_value(row[field], field)
            if value is None or value <= 0:
                raise ValueError("因子为空或非正数")
            item[field] = value
        result.append(item)
    return result


def daily_rows(rows, day, asset, notes):
    """先按完整源集合核对日期/键，再取项目支持前缀；源行数与本地筛选行数分别记录。

    ETF 批量可附带 isST 及四个全空估值列，但既有逐股 ETF 查询不请求这些列。
    只接受已实际核实的附加结构，逐列记录后排除，不转成个股 ST/估值等效。
    """
    result, keys = [], set()
    pattern = STOCK if asset == "stock" else ETF
    for row in rows:
        code = required(row, "code")
        if required(row, "date") != day or required(row, "adjustflag") != "3":
            raise ValueError("批量日线的日期或原始复权标记不符合请求")
        if code in keys:
            raise ValueError("同日批量响应包含重复证券")
        keys.add(code)
        if not pattern.fullmatch(code):
            continue
        source = dict(row)
        if asset == "etf":
            valuations = ETF_SOURCE_VALUATIONS & set(source)
            if valuations and valuations != ETF_SOURCE_VALUATIONS:
                raise ValueError("ETF 源估值附加字段必须完整，不能静默接受部分结构变化")
            for field in sorted(valuations):
                # 2026-11-16 实际 1719 行四列均空。非空值尚无语义证据，必须待审查，不能丢弃。
                if source[field] != "":
                    raise ValueError("ETF 源估值附加字段出现未验证的非空值")
                source.pop(field)
                notes["etfOptionalSourceValuationEmpty:" + field] += 1
                notes["etfOptionalSourceValuationNotStored"] += 1
        if asset == "etf" and "isST" in source:
            if source["isST"] not in {"", "0", "1"}:
                raise ValueError("ETF 可选源状态值无效")
            # 空字符串也是供应方允许的空状态；只记录来源，不将缺失值补成 0 或个股 ST。
            if source["isST"] == "":
                notes["etfOptionalSourceIsSTEmpty"] += 1
            notes["etfOptionalSourceIsSTValue:" + (source["isST"] or "empty")] += 1
            source.pop("isST")
            notes["etfOptionalSourceIsSTNotStored"] += 1
        result.append(canonical_daily_row(source, asset=asset))
    return result


def trading_dates(calendar, start, end):
    """严格校验完整自然日日历，仅把源明确标为 1 的日期加入交易日集合。

    新查询支持协议分页，因此不再按 2000 条限制窗口；必须以闭区间内每一个自然日
    恰好出现一次为完整性依据。缺一天、重复休市日、未知状态以及旧的仅交易日格式均拒绝，
    不能自行根据星期几或缺行推测休市，以免漏行情被误认成非交易日。
    """
    first, last = date.fromisoformat(start), date.fromisoformat(end)
    if first > last:
        raise ValueError("日历区间倒置")
    expected = {(first + timedelta(days=offset)).isoformat() for offset in range((last - first).days + 1)}
    seen, trading = set(), set()
    for row in calendar:
        day = required(row, "date")
        if set(row) != {"date", "is_trading_day"} or day not in expected or day in seen:
            raise ValueError("自然日日历字段、日期范围或重复日期异常")
        if row["is_trading_day"] not in {"0", "1"}:
            raise ValueError("日历交易状态必须明确为 0 或 1")
        seen.add(day)
        if row["is_trading_day"] == "1":
            trading.add(day)
    if seen != expected:
        raise ValueError("自然日日历缺失日期，不能把缺行视为休市")
    return trading


def validated_skipped_dates(report, snapshots):
    """仅接受完整日历明确休市、计划内且没有日请求成功快照的跳过声明。

    这不是成功空行情响应：缺字段/失败响应不能据此改称休市，交易日也不能跳过。
    旧版未提供 skippedDates 的捕获报告保持原有缺请求判定，不从星期几自行补跳过。
    """
    skipped = report.get("skippedDates", [])
    if not isinstance(skipped, list):
        raise ValueError("休市跳过声明必须为数组")
    if not skipped:
        return set()
    plan = required(report, "plan")
    start, end, days = required(plan, "from"), required(plan, "to"), required(plan, "dates")
    calendar = snapshots.get(identity("calendar", {"from": start, "to": end}))
    if calendar is None:
        raise ValueError("休市跳过缺少完整自然日日历证据")
    trading = trading_dates(calendar["rows"], start, end)
    closed = {row["date"] for row in calendar["rows"] if row["is_trading_day"] == "0"}
    result = set()
    for item in skipped:
        if (not isinstance(item, dict) or set(item) != {"date", "reason"}
                or item.get("reason") != "CALENDAR_NON_TRADING"):
            raise ValueError("休市跳过声明的结构或原因不正确")
        day = item["date"]
        if not isinstance(day, str) or day not in days or day not in closed or day in trading or day in result:
            raise ValueError("休市跳过日期必须为计划内唯一且日历明确为 0 的日期")
        if any(saved["operation"] in {"stock-list", "daily-stock", "daily-etf", "daily-factors"}
               and saved["parameters"].get("date") == day for saved in snapshots.values()):
            raise ValueError("休市跳过与已成功保存的同日请求冲突")
        result.add(day)
    return result


def evaluate_capture(path):
    """输出分层证据；异常输入拒绝验收，缺快照和未运行模块保持待验，不自动联网补齐。"""
    captured, snapshots, timings = read_capture(path)
    plan = required(captured, "plan")
    start, end, observed = required(plan, "from"), required(plan, "to"), required(captured, "observedOn")
    capture_id = required(captured, "adjustedCaptureId")
    if not date.fromisoformat(start) <= date.fromisoformat(end) < date.fromisoformat(observed):
        raise ValueError("采样窗口或观察日不正确")
    days, codes = required(plan, "dates"), required(plan, "codes")
    if not days or not codes or len(set(days)) != len(days) or len(set(codes)) != len(codes):
        raise ValueError("采样日期/证券为空或重复")
    if any(not start <= day <= end for day in days) or any(not (STOCK.fullmatch(code) or ETF.fullmatch(code)) for code in codes):
        raise ValueError("代表日期或证券超出计划范围")
    for day in days:
        if date.fromisoformat(day).isoformat() != day:
            raise ValueError("代表日期必须为 YYYY-MM-DD")
    issues, checks, notes = [], [], Counter()

    def get(operation, parameters, adjusted=False):
        saved = snapshots.get(identity(operation, parameters))
        if saved is None:
            issues.append(dict(kind="missing_snapshot", operation=operation, parameters=parameters))
            return None
        if adjusted and saved.get("observedOn") != observed:
            raise ValueError("复权或因子快照跨观察日混用")
        if adjusted and saved.get("adjustedCaptureId") != capture_id:
            raise ValueError("复权或因子快照跨执行批次混用")
        if saved.get("capturedOn") != observed:
            notes["cachedRawFromOtherObservationDay"] += 1
        return saved["rows"]

    calendar = get("calendar", {"from": start, "to": end})
    trading = trading_dates(calendar, start, end) if calendar is not None else set()
    notes["calendarReturnedNaturalDays"] = len(calendar) if calendar is not None else 0
    notes["calendarReturnedTradingDays"] = len(trading)
    skipped_days = validated_skipped_dates(captured, snapshots)
    required_day_requests = set(days) - skipped_days
    notes["calendarVerifiedSkippedDates"] = len(skipped_days)

    samples = {"stock": [], "etf": []}
    expected = {"stock": [], "etf": []}
    available_days = {"stock": set(), "etf": set()}
    deferred_reference_rows = Counter()
    sample_codes = set(codes)
    factors_by_day = {}
    include_etf = any(ETF.fullmatch(code) for code in codes)
    for day in days:
        if day in skipped_days:
            checks.append(dict(name=f"date-{day}", status="SKIPPED_NON_TRADING", date=day,
                               reason="CALENDAR_NON_TRADING", successfulEmptySnapshot=False))
            continue
        listed = get("stock-list", {"date": day})
        listed_codes = set()
        if listed is not None:
            for row in listed:
                code = required(row, "code")
                if not re.fullmatch(r"(?:sh|sz|bj)\.\d{6}", code):
                    raise ValueError("逐日证券列表的代码格式无效")
                if code in listed_codes:
                    raise ValueError("逐日证券列表重复")
                listed_codes.add(code)
        for asset, operation, pattern in (("stock", "daily-stock", STOCK), ("etf", "daily-etf", ETF)):
            if asset == "etf" and not include_etf:
                continue
            raw = get(operation, {"date": day})
            if raw is None:
                continue
            available_days[asset].add(day)
            canonical = daily_rows(raw, day, asset, notes)
            actual = {row["market"].lower() + "." + row["code"] for row in canonical}
            baseline = {code for code in listed_codes if pattern.fullmatch(code)} if day in trading else set()
            list_known = listed is not None and calendar is not None
            # queryAllStock 是否完整覆盖 ETF 需独立实测；无 ETF 列表证据时不得从候选批量自建期望集。
            if asset == "etf" and day in trading and not baseline:
                list_known = False
            checks.append(dict(name=f"universe-{asset}-{day}", status="NOT_CHECKED" if not list_known else "PASS" if actual == baseline else "FAIL",
                               sourceRows=len(raw), supportedRows=len(canonical), expectedRows=len(baseline),
                               missing=sorted(baseline - actual), unexpected=sorted(actual - baseline)))
            samples[asset].extend(row for row in canonical if row["market"].lower() + "." + row["code"] in sample_codes)
            if list_known:
                expected[asset].extend(dict(code=code.split(".")[1], market=code[:2].upper(), trade_date=day, adjustflag=3)
                                       for code in sorted(baseline & sample_codes))
            else:
                issues.append(dict(kind="independent_universe_missing", operation=operation, parameters={"date": day}))
        events = get("daily-factors", {"date": day}, True)
        if events is not None:
            factors_by_day[day] = factor_rows(events, expected_day=day)

    reference = {"stock": [], "etf": []}
    available_reference_codes = {"stock": set(), "etf": set()}
    available_factor_codes = set()
    period_factors = []
    for code in codes:
        asset = "etf" if ETF.fullmatch(code) else "stock"
        for flag in (["3"] if asset == "etf" else ["3", "2", "1"]):
            rows = get("history", dict(code=code, **{"from": start, "to": end}, adjustflag=flag), flag != "3")
            if rows is None:
                continue
            normalized, keys = [], set()
            for row in rows:
                item = canonical_daily_row(row, code=code, asset=asset)
                if not start <= item["trade_date"] <= end or item["adjustflag"] != int(flag):
                    raise ValueError("逐股参考日期或实际复权标记与请求不一致")
                if item["trade_date"] in keys:
                    raise ValueError("逐股参考出现重复日期")
                keys.add(item["trade_date"])
                if item["trade_date"] in days:
                    if calendar is not None and item["trade_date"] not in trading:
                        raise ValueError("逐股日线与交易日历冲突，不能视为非交易日空表")
                    # 整日请求尚未取得，与“成功响应里少一只股票”不同；只比较已捕获日期。
                    # 缺请求继续保留在 issues，不能通过删除参考行把完整性变成 PASS。
                    if flag != "3" or item["trade_date"] in available_days[asset]:
                        normalized.append(item)
                    else:
                        deferred_reference_rows[asset] += 1
            if flag == "3":
                available_reference_codes[asset].add(code)
                reference[asset].extend(normalized)
            else:
                checks.append(dict(name=f"adjusted-reference-{code}-{flag}", status="CAPTURED_NOT_COMPARED", rows=len(rows)))
        if asset == "stock":
            rows = get("factors", dict(code=code, **{"from": "1990-12-19", "to": observed}), True)
            if rows is not None:
                available_factor_codes.add(code)
                period_factors.extend(factor_rows(rows, expected_code=code))

    for asset in ("stock", "etf"):
        if asset == "etf" and not include_etf:
            continue
        complete_evidence = not issues
        # 同样地，整只证券的逐股参考请求未取得时，不能把现有批量行伪报成“多余行情”。
        # 成功但空的逐股响应仍计入可比较证券，由后续比较发现真正的双侧集合冲突。
        available_samples = [row for row in samples[asset] if row["market"].lower() + "." + row["code"] in available_reference_codes[asset]]
        deferred_candidates = len(samples[asset]) - len(available_samples)
        if not reference[asset] and not available_samples:
            checks.append(dict(name=f"raw-representatives-{asset}", status="NOT_RUN",
                               comparedDates=sorted(available_days[asset]), deferredReferenceRows=deferred_reference_rows[asset],
                               deferredCandidateRows=deferred_candidates,
                               reason="没有可比较的代表证券行情行，空表一致不证明字段数值等效"))
            continue
        result = compare_daily(reference[asset], available_samples, expected_keys=expected[asset] if complete_evidence else None,
                               allow_empty=True)
        result.update(name=f"raw-representatives-{asset}", comparedDates=sorted(available_days[asset]),
                      deferredReferenceRows=deferred_reference_rows[asset], deferredCandidateRows=deferred_candidates)
        checks.append(result)
    stock_codes = [code for code in codes if STOCK.fullmatch(code)]
    if stock_codes:
        init_factors = [dict(code=row["code"].split(".")[1], market=row["code"][:2].upper(),
                             effective_date=row["dividOperateDate"], forward_factor=row["foreAdjustFactor"],
                             backward_factor=row["backAdjustFactor"]) for row in period_factors]
        coverage = audit_factor_coverage(init_factors, [dict(code=code.split(".")[1], market=code[:2].upper()) for code in stock_codes],
                                         history_start=start, asof=observed)
        checks.append(dict(name="factor-initialization-evidence", **coverage))
        expected_events = sorted((r for r in period_factors if r["dividOperateDate"] in factors_by_day), key=lambda r: (r["code"], r["dividOperateDate"]))
        actual_events = sorted((r for events in factors_by_day.values() for r in events if r["code"] in available_factor_codes), key=lambda r: (r["code"], r["dividOperateDate"]))
        event_comparison = compare_values(expected_events, actual_events)
        # 未取得日事件快照时，两侧空数组不能变成“事件一致已通过”的证据。
        if not factors_by_day or not available_factor_codes:
            event_comparison["status"] = "NOT_RUN"
        elif (set(factors_by_day) != required_day_requests or len(available_factor_codes) != len(stock_codes)) and event_comparison["status"] == "PASS":
            event_comparison["status"] = "INCOMPLETE"
        checks.append(dict(name="factor-events-on-sampled-days", comparedDates=sorted(factors_by_day),
                           missingDates=sorted(required_day_requests - set(factors_by_day)),
                           missingReferenceSecurities=sorted(set(stock_codes) - available_factor_codes),
                           sourceEventsOnComparedDays=sum(len(events) for events in factors_by_day.values()),
                           referenceEvents=len(expected_events), comparedEvents=len(actual_events),
                           eventsOutsideReferenceSecurities=sum(row["code"] not in available_factor_codes
                                                                for events in factors_by_day.values() for row in events),
                           **event_comparison))
    return dict(status="FAILED" if any(c["status"] == "FAIL" for c in checks) else "PARTIAL", scope="REPRESENTATIVE_SOURCE_EVIDENCE_ONLY",
                capturedStatus=captured.get("status"), historyStart=start, asof=end, observationDate=observed,
                adjustedCaptureId=capture_id,
                requestedDates=days, skippedDates=[dict(date=day, reason="CALENDAR_NON_TRADING") for day in sorted(skipped_days)],
                representativeCodes=codes, issues=issues, notes=dict(notes), checks=checks,
                requestTimings=timings, timingMeaning="方法耗时，包含其内部重试；排除请求间等待。缓存耗时为原采样值，不计算提速倍数。",
                adjustmentFormula="NOT_VALIDATED", modelParity="NOT_RUN", cutoverAllowed=False,
                limitation="代表样本与日证券列表核对不证明全市场三年无缺口；未运行评分/短期/概率/GA/策略/做T同输入重算。")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    target = args.output.resolve()
    if not target.is_relative_to((ROOT / ".local").resolve()) or target == args.capture.resolve() or re.fullmatch(r"[0-9a-f]{64}\.json", target.name):
        parser.error("报告须位于本仓库 .local，且不得覆盖捕获报告或请求快照")
    try:
        report = evaluate_capture(args.capture)
    except (ValueError, KeyError, TypeError, OSError):
        report = dict(status="INVALID_INPUT", modelParity="NOT_RUN", cutoverAllowed=False,
                      message="快照身份、摘要、字段、日期、证券或业务键未通过严格校验；未联网补齐。")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    print(json.dumps({"status": report["status"], "modelParity": "NOT_RUN", "cutoverAllowed": False}))
    return 0 if report["status"] == "PARTIAL" else 1


if __name__ == "__main__":
    raise SystemExit(main())
