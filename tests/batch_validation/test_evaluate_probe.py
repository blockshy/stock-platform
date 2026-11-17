"""用合成 Java 快照文件验证离线适配器，不登录 BaoStock，不读写任何数据库。"""
import json
import tempfile
import unittest
from collections import Counter
from datetime import date, timedelta
from pathlib import Path

from evaluate_probe import compact_sha, daily_rows, evaluate_capture, read_capture, trading_dates
from test_compare import vendor_row


def make_capture(directory, *, missing_security=False, extra_etf=False, etf_status="1",
                 capture_id="3e87604b-26e9-4640-9552-d3e3fc08497e", with_holiday=False):
    """构造一个交易日的完整请求清单；日期、原始字段和身份摘要均模拟 Java 写出格式。"""
    day, observed = "2026-01-05", "2026-01-06"
    start = "2026-01-04" if with_holiday else day
    codes = ["sz.000001"] + (["sh.588000"] if extra_etf else [])
    requests = []

    def save(operation, parameters, rows, adjusted=False):
        record = dict(contract="baostock-batch-v2", operation=operation, parameters=dict(sorted(parameters.items())))
        if adjusted:
            record["observedOn"] = observed
            record["adjustedCaptureId"] = capture_id
        key = compact_sha(record)
        record.update(key=key, complete=True, capturedOn=observed, elapsedMillis=10,
                      rowsSha256=compact_sha(rows), rows=rows)
        file = key + ".json"
        (directory / file).write_text(json.dumps(record, ensure_ascii=False), encoding="utf-8")
        requests.append(dict(operation=operation, parameters=parameters, file=file, cached=False, rows=len(rows), elapsedMillis=10))

    calendar = ([{"date": start, "is_trading_day": "0"}] if with_holiday else []) + [{"date": day, "is_trading_day": "1"}]
    save("calendar", {"from": start, "to": day}, calendar)
    listed = [{"code": code, "tradeStatus": "1", "code_name": "合成样本"} for code in codes]
    if missing_security:
        listed.append({"code": "sh.600000", "tradeStatus": "1", "code_name": "漏行情样本"})
    save("stock-list", {"date": day}, listed)
    save("daily-stock", {"date": day}, [vendor_row()])
    save("daily-factors", {"date": day}, [], True)
    for code in codes:
        etf = code.startswith("sh.58")
        raw = vendor_row(code=code)
        if etf:
            for key in ("isST", "peTTM", "pbMRQ", "psTTM", "pcfNcfTTM"):
                raw.pop(key)
            # 批量 ETF 的可选 isST 仅记录为源侧额外字段，不写入项目股票语义。
            save("daily-etf", {"date": day}, [dict(raw, isST=etf_status)])
        for flag in (["3"] if etf else ["3", "2", "1"]):
            row = dict(raw, adjustflag=flag)
            row.pop("code")
            save("history", dict(code=code, **{"from": start, "to": day}, adjustflag=flag), [row], flag != "3")
        if not etf:
            save("factors", dict(code=code, **{"from": "1990-12-19", "to": observed}),
                 [dict(code=code, dividOperateDate="2025-01-01", foreAdjustFactor="0.9", backAdjustFactor="1.1", adjustFactor="0.8")], True)
    report = dict(schema=1, observedOn=observed, adjustedCaptureId=capture_id, scope="SOURCE_SNAPSHOTS_ONLY",
                  cutoverAllowed=False, modelParity="NOT_RUN", status="CAPTURED_NOT_ACCEPTED", requests=requests)
    report["plan"] = {"from": start, "to": day, "dates": [day] + ([start] if with_holiday else []), "codes": codes, "requestDelayMillis": 1500}
    if with_holiday:
        report["skippedDates"] = [dict(date=start, reason="CALENDAR_NON_TRADING")]
    path = directory / "capture-report.json"
    path.write_text(json.dumps(report, ensure_ascii=False), encoding="utf-8")
    return path


class ProbeEvaluationTest(unittest.TestCase):
    def test_matching_samples_still_do_not_claim_model_or_formula_acceptance(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = make_capture(Path(tmp))
            result = evaluate_capture(path)
            self.assertEqual(result["status"], "PARTIAL")
            self.assertEqual(result["modelParity"], "NOT_RUN")
            self.assertEqual(result["adjustmentFormula"], "NOT_VALIDATED")
            self.assertFalse(result["cutoverAllowed"])
            raw = next(c for c in result["checks"] if c["name"] == "raw-representatives-stock")
            self.assertEqual(raw["status"], "PASS")

    def test_independent_stock_list_detects_non_sample_missing_security(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = evaluate_capture(make_capture(Path(tmp), missing_security=True))
            self.assertEqual(result["status"], "FAILED")
            universe = next(c for c in result["checks"] if c["name"].startswith("universe-stock"))
            self.assertEqual(universe["missing"], ["sh.600000"])

    def test_etf_comparison_is_separate_and_optional_source_status_is_explicit(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = evaluate_capture(make_capture(Path(tmp), extra_etf=True))
            raw = next(c for c in result["checks"] if c["name"] == "raw-representatives-etf")
            self.assertEqual(raw["status"], "PASS")
            self.assertEqual(result["notes"]["etfOptionalSourceIsSTNotStored"], 1)

    def test_empty_optional_etf_status_is_recorded_without_becoming_stock_status(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = evaluate_capture(make_capture(Path(tmp), extra_etf=True, etf_status=""))
            raw = next(c for c in result["checks"] if c["name"] == "raw-representatives-etf")
            self.assertEqual(raw["status"], "PASS")
            self.assertEqual(result["notes"]["etfOptionalSourceIsSTEmpty"], 1)
            self.assertEqual(result["notes"]["etfOptionalSourceIsSTNotStored"], 1)
            self.assertEqual(result["status"], "PARTIAL")

    def test_etf_four_empty_source_valuations_are_explicitly_recorded_and_excluded(self):
        """真实18列结构四估值全空，源 isST=1 仅保留统计，不转成ETF股票状态。"""
        row = vendor_row(code="sh.588000", isST="1", peTTM="", pbMRQ="", psTTM="", pcfNcfTTM="")
        notes = Counter()
        result = daily_rows([row], row["date"], "etf", notes)[0]
        self.assertEqual(notes["etfOptionalSourceValuationNotStored"], 4)
        self.assertEqual(notes["etfOptionalSourceValuationEmpty:peTTM"], 1)
        self.assertEqual(notes["etfOptionalSourceIsSTValue:1"], 1)
        for field in ("is_st", "pe_ttm", "pb_mrq", "ps_ttm", "pcf_ncf_ttm"):
            self.assertIsNone(result[field])

    def test_etf_nonempty_or_partial_source_valuations_are_not_silently_dropped(self):
        """新出现的估值实值或不完整字段集合都要重新审查，不能凭ETF身份自动丢掉。"""
        source = vendor_row(code="sh.588000", isST="1", peTTM="", pbMRQ="", psTTM="", pcfNcfTTM="")
        for value in ("1.2", "bad", " ", None):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "非空值"):
                daily_rows([dict(source, peTTM=value)], source["date"], "etf", Counter())
        partial = dict(source)
        partial.pop("peTTM")
        with self.assertRaisesRegex(ValueError, "完整"):
            daily_rows([partial], source["date"], "etf", Counter())

    def test_full_natural_calendar_selects_only_explicit_trading_days(self):
        rows = [{"date": "2026-01-03", "is_trading_day": "0"},
                {"date": "2026-01-04", "is_trading_day": "0"},
                {"date": "2026-01-05", "is_trading_day": "1"}]
        self.assertEqual(trading_dates(rows, "2026-01-03", "2026-01-05"), {"2026-01-05"})

    def test_calendar_rejects_missing_day_repeated_closed_day_and_old_shape(self):
        closed = {"date": "2026-01-03", "is_trading_day": "0"}
        with self.assertRaisesRegex(ValueError, "缺失日期"):
            trading_dates([closed], "2026-01-03", "2026-01-04")
        with self.assertRaisesRegex(ValueError, "重复日期"):
            trading_dates([closed, closed], "2026-01-03", "2026-01-03")
        for invalid in ({"date": "2026-01-03"}, dict(closed, is_trading_day=""),
                        dict(closed, is_trading_day="2"), dict(closed, date="2026-01-02")):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                trading_dates([invalid], "2026-01-03", "2026-01-03")

    def test_complete_calendar_over_two_thousand_rows_is_supported(self):
        first = date(2020, 1, 1)
        rows = [{"date": (first + timedelta(days=offset)).isoformat(),
                 "is_trading_day": "1" if offset % 7 < 5 else "0"} for offset in range(2001)]
        expected = {row["date"] for row in rows if row["is_trading_day"] == "1"}
        self.assertEqual(trading_dates(rows, rows[0]["date"], rows[-1]["date"]), expected)

    def test_modified_rows_without_updated_hash_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = make_capture(Path(tmp))
            report = json.loads(path.read_text("utf-8"))
            daily = next(r for r in report["requests"] if r["operation"] == "daily-stock")
            file = path.parent / daily["file"]
            saved = json.loads(file.read_text("utf-8"))
            saved["rows"][0]["close"] = "999"
            file.write_text(json.dumps(saved), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "行摘要"):
                read_capture(path)

    def test_identity_mismatch_is_not_hidden_by_unchanged_key_string(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = make_capture(Path(tmp))
            report = json.loads(path.read_text("utf-8"))
            request = report["requests"][0]
            file = path.parent / request["file"]
            saved = json.loads(file.read_text("utf-8"))
            saved["parameters"]["to"] = "2026-01-04"
            file.write_text(json.dumps(saved), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "身份不一致"):
                read_capture(path)

    def test_adjusted_snapshots_reject_same_day_other_capture_with_valid_hash(self):
        """保留同一观察日并重算合法摘要，确保失败原因确实是跨轮复权身份而非文件损坏。"""
        for operation, flag in (("daily-factors", None), ("factors", None), ("history", "1"), ("history", "2")):
            with self.subTest(operation=operation, flag=flag), tempfile.TemporaryDirectory() as tmp:
                path = make_capture(Path(tmp))
                report = json.loads(path.read_text("utf-8"))
                request = next(r for r in report["requests"] if r["operation"] == operation
                               and (flag is None or r["parameters"].get("adjustflag") == flag))
                saved = json.loads((path.parent / request["file"]).read_text("utf-8"))
                saved["adjustedCaptureId"] = "3b26ade4-1564-44b2-98c7-4f0e8b355887"
                key_data = {key: saved[key] for key in ("contract", "operation", "parameters", "observedOn", "adjustedCaptureId")}
                saved["key"] = compact_sha(key_data)
                request["file"] = saved["key"] + ".json"
                (path.parent / request["file"]).write_text(json.dumps(saved), encoding="utf-8")
                path.write_text(json.dumps(report), encoding="utf-8")
                # 文件与请求本身完整，只有放入本轮验收上下文时才应拒绝旧批次复权值。
                read_capture(path)
                with self.assertRaisesRegex(ValueError, "跨执行批次"):
                    evaluate_capture(path)

    def test_capture_id_required_and_must_be_canonical_uuid(self):
        """报告缺少轮次身份或以空值代替身份均不能回退到仅按日期验证。"""
        for invalid in (None, "", "not-a-uuid", 123):
            with self.subTest(invalid=invalid), tempfile.TemporaryDirectory() as tmp:
                path = make_capture(Path(tmp))
                report = json.loads(path.read_text("utf-8"))
                if invalid is None:
                    report.pop("adjustedCaptureId")
                else:
                    report["adjustedCaptureId"] = invalid
                path.write_text(json.dumps(report), encoding="utf-8")
                with self.assertRaises(ValueError):
                    read_capture(path)

    def test_only_raw_snapshot_keys_are_reusable_across_executions(self):
        """同一计划两次执行：原始数据键稳定，所有复权/因子键必须随本轮 UUID 改变。"""
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            left, left_snapshots, _ = read_capture(make_capture(Path(first)))
            right, right_snapshots, _ = read_capture(make_capture(Path(second), capture_id="3b26ade4-1564-44b2-98c7-4f0e8b355887"))
            self.assertNotEqual(left["adjustedCaptureId"], right["adjustedCaptureId"])
            self.assertEqual(set(left_snapshots), set(right_snapshots))
            for key, saved in left_snapshots.items():
                other = right_snapshots[key]
                if "adjustedCaptureId" in saved:
                    self.assertNotEqual(saved["key"], other["key"])
                else:
                    self.assertNotIn("adjustedCaptureId", other)
                    self.assertEqual(saved["key"], other["key"])
            self.assertEqual(evaluate_capture(Path(second) / "capture-report.json")["status"], "PARTIAL")

    def test_missing_request_is_partial_not_silently_complete(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = make_capture(Path(tmp))
            report = json.loads(path.read_text("utf-8"))
            report["requests"] = [r for r in report["requests"] if r["operation"] != "daily-factors"]
            report["status"] = "FAILED"
            path.write_text(json.dumps(report), encoding="utf-8")
            result = evaluate_capture(path)
            self.assertEqual(result["status"], "PARTIAL")
            self.assertTrue(any(i["kind"] == "missing_snapshot" for i in result["issues"]))
            events = next(check for check in result["checks"] if check["name"] == "factor-events-on-sampled-days")
            self.assertEqual(events["status"], "NOT_RUN")

    def test_missing_entire_batch_request_is_not_a_field_difference(self):
        """缺快照只表示尚未执行该日期对照；真实成功批次中的漏股仍由独立名单反例检测。"""
        with tempfile.TemporaryDirectory() as tmp:
            path = make_capture(Path(tmp))
            report = json.loads(path.read_text("utf-8"))
            report["requests"] = [row for row in report["requests"] if row["operation"] != "daily-stock"]
            path.write_text(json.dumps(report), encoding="utf-8")
            result = evaluate_capture(path)
            raw = next(check for check in result["checks"] if check["name"] == "raw-representatives-stock")
            self.assertEqual(result["status"], "PARTIAL")
            self.assertEqual(raw["status"], "NOT_RUN")
            self.assertEqual(raw["deferredReferenceRows"], 1)

    def test_daily_only_capture_defers_absent_history_and_factor_reference(self):
        """有批量但尚无逐股请求，也不能把整只证券视为多余行或宣称空因子事件已验证。"""
        with tempfile.TemporaryDirectory() as tmp:
            path = make_capture(Path(tmp), extra_etf=True)
            report = json.loads(path.read_text("utf-8"))
            report["requests"] = [row for row in report["requests"] if row["operation"] not in {"history", "factors"}]
            path.write_text(json.dumps(report), encoding="utf-8")
            result = evaluate_capture(path)
            self.assertEqual(result["status"], "PARTIAL")
            for asset in ("stock", "etf"):
                check = next(check for check in result["checks"] if check["name"] == "raw-representatives-" + asset)
                self.assertEqual(check["status"], "NOT_RUN")
                self.assertEqual(check["deferredCandidateRows"], 1)
            events = next(check for check in result["checks"] if check["name"] == "factor-events-on-sampled-days")
            self.assertEqual(events["status"], "NOT_RUN")
            self.assertEqual(events["missingReferenceSecurities"], ["sz.000001"])

    def test_calendar_verified_holiday_is_skipped_without_fake_empty_snapshot(self):
        """仅显式且可核实的休市声明免去日请求；已有交易日仍执行完整字段和集合比较。"""
        with tempfile.TemporaryDirectory() as tmp:
            result = evaluate_capture(make_capture(Path(tmp), with_holiday=True))
            self.assertEqual(result["issues"], [])
            skipped = next(check for check in result["checks"] if check["name"] == "date-2026-01-04")
            self.assertEqual(skipped["status"], "SKIPPED_NON_TRADING")
            self.assertFalse(skipped["successfulEmptySnapshot"])
            raw = next(check for check in result["checks"] if check["name"] == "raw-representatives-stock")
            self.assertEqual(raw["status"], "PASS")
            events = next(check for check in result["checks"] if check["name"] == "factor-events-on-sampled-days")
            self.assertEqual(events["status"], "PASS")
            self.assertEqual(events["missingDates"], [])

    def test_holiday_skip_rejects_trading_outside_duplicate_and_unknown_reason(self):
        """跳过声明不是免检开关：交易日、计划外、重复项和未知原因全部拒绝。"""
        valid = dict(date="2026-01-04", reason="CALENDAR_NON_TRADING")
        invalid = ([dict(valid, date="2026-01-05")], [dict(valid, date="2026-01-03")],
                   [valid, valid], [dict(valid, reason="REQUEST_FAILED")], "2026-01-04")
        for skipped in invalid:
            with self.subTest(skipped=skipped), tempfile.TemporaryDirectory() as tmp:
                path = make_capture(Path(tmp), with_holiday=True)
                report = json.loads(path.read_text("utf-8"))
                report["skippedDates"] = skipped
                path.write_text(json.dumps(report), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "休市跳过"):
                    read_capture(path)

    def test_holiday_skip_requires_calendar_and_rejects_successful_day_request(self):
        """即使同日快照是空数组，也不能一边声明未请求跳过，一边保存成功日响应。"""
        for conflict in (False, True):
            with self.subTest(conflict=conflict), tempfile.TemporaryDirectory() as tmp:
                path = make_capture(Path(tmp), with_holiday=True)
                report = json.loads(path.read_text("utf-8"))
                if conflict:
                    record = dict(contract="baostock-batch-v2", operation="daily-stock", parameters={"date": "2026-01-04"})
                    key = compact_sha(record)
                    record.update(key=key, complete=True, capturedOn=report["observedOn"], elapsedMillis=1,
                                  rowsSha256=compact_sha([]), rows=[])
                    (path.parent / (key + ".json")).write_text(json.dumps(record), encoding="utf-8")
                    report["requests"].append(dict(operation="daily-stock", parameters=record["parameters"], file=key + ".json", rows=0))
                else:
                    report["requests"] = [request for request in report["requests"] if request["operation"] != "calendar"]
                path.write_text(json.dumps(report), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "休市跳过"):
                    read_capture(path)

    def test_missing_holiday_declaration_is_not_inferred_from_weekend(self):
        """没有明确跳过声明的旧报告继续保留缺请求证据，不自行把失败请求解释成休市跳过。"""
        with tempfile.TemporaryDirectory() as tmp:
            path = make_capture(Path(tmp), with_holiday=True)
            report = json.loads(path.read_text("utf-8"))
            report.pop("skippedDates")
            path.write_text(json.dumps(report), encoding="utf-8")
            result = evaluate_capture(path)
            self.assertEqual(result["skippedDates"], [])
            self.assertTrue(any(issue["parameters"].get("date") == "2026-01-04" for issue in result["issues"]))

    def test_factor_events_outside_representatives_are_explicit_not_claimed_compared(self):
        """批量因子有事件但不属于代表证券时，零事件一致不能冒充这些源事件都被逐股验收。"""
        with tempfile.TemporaryDirectory() as tmp:
            path = make_capture(Path(tmp))
            report = json.loads(path.read_text("utf-8"))
            request = next(row for row in report["requests"] if row["operation"] == "daily-factors")
            file = path.parent / request["file"]
            saved = json.loads(file.read_text("utf-8"))
            saved["rows"] = [dict(code="sh.600000", dividOperateDate="2026-01-05", foreAdjustFactor="1",
                                  backAdjustFactor="2", adjustFactor="2")]
            saved["rowsSha256"] = compact_sha(saved["rows"])
            request["rows"] = 1
            file.write_text(json.dumps(saved), encoding="utf-8")
            path.write_text(json.dumps(report), encoding="utf-8")
            result = evaluate_capture(path)
            events = next(check for check in result["checks"] if check["name"] == "factor-events-on-sampled-days")
            self.assertEqual(events["sourceEventsOnComparedDays"], 1)
            self.assertEqual(events["eventsOutsideReferenceSecurities"], 1)
            self.assertEqual(events["referenceEvents"], 0)
            self.assertEqual(events["comparedEvents"], 0)
            self.assertEqual(result["status"], "PARTIAL")


if __name__ == "__main__":
    unittest.main()
