"""复权候选的纯合成反例：测试门槛与精度，不代替真实行情的公式验收。"""
import json
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path

from compare import KEY_FIELDS, canonical_daily_row
from reconstruct_adjusted import (CANDIDATES, PRICES, assess_candidate, boundary_evidence,
                                  evaluate_adjusted_capture, multiply_price, reconstruct_candidate)
from test_compare import vendor_row
from test_evaluate_probe import make_capture


def factor(day, forward, backward="2"):
    """显式构造事件记录，不把任何缺失的真实事件假设为因子 1。"""
    return dict(code="sz.000001", dividOperateDate=day, foreAdjustFactor=forward,
                backAdjustFactor=backward, adjustFactor="1.25")


def boundary_fixture():
    """构造除权前、当天、次日三行，参考价格独立写定，避免测试只复述实现。"""
    raw, reference = [], []
    for day, price, expected in (("2026-01-05", "10", "5"), ("2026-01-06", "5", "5"), ("2026-01-07", "6", "6")):
        raw.append(canonical_daily_row(vendor_row(date=day, **dict.fromkeys(PRICES, price))))
        reference.append(canonical_daily_row(vendor_row(date=day, adjustflag="2", **dict.fromkeys(PRICES, expected))))
    return raw, reference, [factor("2025-01-01", "0.5"), factor("2026-01-06", "1", "4")]


class AdjustmentCandidateTest(unittest.TestCase):
    """除权边界、关键空值、原始字段和独立完整性都不能靠数据库舍入得到通过。"""

    def assess(self, raw, reference, factors, config=CANDIDATES[0], **kwargs):
        return assess_candidate(raw, reference, factors, 2, "2026-01-08", config,
                                sessions=("2026-01-05", "2026-01-06", "2026-01-07"),
                                start="2026-01-05", end="2026-01-07", **kwargs)

    def test_factor_effective_on_event_day_and_boundary_fields_are_separate(self):
        raw, reference, factors = boundary_fixture()
        report = self.assess(raw, reference, factors)
        self.assertEqual(report["status"], "MATCHED_SAMPLE")
        self.assertEqual(report["boundaries"]["status"], "COMPLETE_SAMPLE")
        self.assertEqual(report["comparison"]["status"], "INCOMPLETE")
        self.assertEqual(report["completeness"], "INDEPENDENT_KEYS_NOT_PROVIDED")
        self.assertEqual(report["precision"]["fields"]["preclose"]["exactMismatches"], 0)
        candidate, trace, missing = reconstruct_candidate(raw, factors, 2, "2026-01-08", CANDIDATES[0])
        self.assertFalse(missing)
        self.assertEqual(trace[("000001", "SZ", "2026-01-06")]["preclose"]["effectiveDate"], "2026-01-06")
        self.assertEqual(candidate[0]["volume"], raw[0]["volume"])
        self.assertEqual(candidate[0]["amount"], raw[0]["amount"])

    def test_previous_session_preclose_is_explicit_diagnostic_not_default(self):
        raw, reference, factors = boundary_fixture()
        report = self.assess(raw, reference, factors, CANDIDATES[-1])
        self.assertEqual(report["status"], "INCOMPLETE")
        self.assertEqual(report["missingFactors"][0]["reason"], "previous_calendar_session_missing")
        discrepancy = next(row for row in report["precision"]["examples"] if row["field"] == "preclose")
        self.assertEqual(discrepancy["date"], "2026-01-06")
        self.assertEqual(discrepancy["candidate"], Decimal("2.500000"))
        self.assertEqual(discrepancy["factor"]["effectiveDate"], "2025-01-01")

    def test_backward_flag_uses_backward_factor_without_transforming_volume(self):
        raw, _, factors = boundary_fixture()
        candidate, _, missing = reconstruct_candidate(raw, factors, 1, "2026-01-08", CANDIDATES[0])
        self.assertFalse(missing)
        self.assertEqual([row["close"] for row in candidate], [Decimal("20"), Decimal("20"), Decimal("24")])
        self.assertTrue(all(row["adjustflag"] == 1 for row in candidate))
        self.assertEqual([row["volume"] for row in candidate], [row["volume"] for row in raw])

    def test_missing_initial_factor_is_not_one_or_backfilled_from_future_event(self):
        raw, reference, factors = boundary_fixture()
        report = self.assess(raw, reference, factors[1:])
        self.assertEqual(report["status"], "INCOMPLETE")
        self.assertEqual(report["missingFactors"], [dict(code="sz.000001", date="2026-01-05", reason="initial_factor_missing")])
        self.assertEqual(report["comparison"]["missingRows"], 1)

    def test_duplicate_nonpositive_and_future_factors_are_rejected(self):
        raw, _, _ = boundary_fixture()
        invalid_sets = ([factor("2025-01-01", "1"), factor("2025-01-01", "2")],
                        [factor("2025-01-01", "0")], [factor("2026-01-09", "1")])
        for factors in invalid_sets:
            with self.subTest(factors=factors), self.assertRaises(ValueError):
                reconstruct_candidate(raw, factors, 2, "2026-01-08", CANDIDATES[0])

    def test_rounding_modes_and_factor_quantization_have_distinct_evidence(self):
        self.assertEqual(multiply_price(Decimal("1"), Decimal("0.1234565")), Decimal("0.1234565"))
        self.assertEqual(multiply_price(Decimal("1"), Decimal("0.1234565"), "half-up-6"), Decimal("0.123457"))
        self.assertEqual(multiply_price(Decimal("1"), Decimal("0.1234565"), "half-even-6"), Decimal("0.123456"))
        # 因子先截位再乘价格的误差会被价格放大，不得当作与“最后量化价格”等价。
        full = multiply_price(Decimal("100"), Decimal("0.1234565"), "half-even-6")
        rounded_factor = multiply_price(Decimal("100"), Decimal("0.1234565"), "half-even-6", "half-even-6")
        self.assertEqual(full, Decimal("12.345650"))
        self.assertEqual(rounded_factor, Decimal("12.345600"))
        self.assertNotEqual(full, rounded_factor)
        # 默认 Decimal 精度为 28；精确候选不得在价格末尾偷偷引入上下文舍入。
        precise = Decimal("1.000000000000000000000000000001")
        self.assertEqual(multiply_price(precise, Decimal("1")), precise)

    def test_storage_rounding_cannot_hide_source_price_difference(self):
        raw, reference, factors = boundary_fixture()
        reference[0]["close"] = Decimal("5.000001")
        report = self.assess(raw, reference, factors)
        self.assertEqual(report["status"], "MISMATCH")
        stats = report["precision"]["fields"]["close"]
        self.assertEqual(stats["exactMismatches"], 1)
        self.assertEqual(stats["maskedByStorageProjection"], 1)
        self.assertEqual(stats["maxAbsoluteDifference"], Decimal("0.000001"))

    def test_volume_and_amount_differences_are_not_adjusted_away(self):
        raw, reference, factors = boundary_fixture()
        reference[0]["volume"] += 1
        reference[1]["amount"] += Decimal("0.01")
        report = self.assess(raw, reference, factors)
        self.assertEqual(report["status"], "MISMATCH")
        for field in ("volume", "amount"):
            self.assertEqual(report["precision"]["fields"][field]["exactMismatches"], 1)

    def test_null_prices_are_preserved_but_never_claim_complete_reconstruction(self):
        raw, reference, factors = boundary_fixture()
        raw[0]["open"] = reference[0]["open"] = None
        report = self.assess(raw, reference, factors)
        self.assertEqual(report["status"], "INCOMPLETE")
        self.assertEqual(report["sourceCriticalNulls"], 1)
        self.assertEqual(report["precision"]["fields"]["open"]["nullPairs"], 1)

    def test_verified_suspended_trade_pair_matches_without_filling_zero(self):
        """仅双方停牌且量额同时为空是已核实源契约，精确匹配可独立记载而不误判公式缺数据。"""
        raw, reference, factors = boundary_fixture()
        for row in (raw[0], reference[0]):
            row.update(tradestatus=Decimal(0), volume=None, amount=None)
        report = self.assess(raw, reference, factors)
        self.assertEqual(report["status"], "MATCHED_SAMPLE")
        self.assertEqual(report["sourceCriticalNulls"], 0)
        self.assertEqual(report["referenceCriticalNulls"], 0)
        self.assertEqual(report["matchedSourceNulls"], [dict(code="sz.000001", date="2026-01-05",
                         fields=["volume", "amount"], reason="verified_suspended_pair")])
        self.assertEqual(report["precision"]["fields"]["volume"]["nullPairs"], 1)

    def test_active_empty_pair_and_suspended_single_empty_are_still_incomplete(self):
        """不得把真实停牌特例泛化到正常交易缺值或单字段缺失。"""
        for status, fields in ((Decimal(1), ("volume", "amount")), (Decimal(0), ("volume",))):
            with self.subTest(status=status, fields=fields):
                raw, reference, factors = boundary_fixture()
                for row in (raw[0], reference[0]):
                    row["tradestatus"] = status
                    for field in fields:
                        row[field] = None
                report = self.assess(raw, reference, factors)
                self.assertEqual(report["status"], "INCOMPLETE")
                self.assertEqual(report["matchedSourceNulls"], [])
                self.assertGreater(report["sourceCriticalNulls"], 0)

    def test_independent_expected_key_detects_both_sides_missing_day(self):
        raw, reference, factors = boundary_fixture()
        expected = [{key: row[key] for key in KEY_FIELDS} for row in reference]
        report = self.assess(raw[:2], reference[:2], factors, expected_keys=expected)
        self.assertEqual(report["status"], "MISMATCH")
        self.assertEqual(report["comparison"]["values"], "PASS")
        self.assertEqual(report["comparison"]["completeness"]["candidateMissing"], 1)
        self.assertEqual(report["boundaries"]["status"], "INCOMPLETE")
        self.assertEqual(report["boundaries"]["events"][0]["missing"], ["after"])

    def test_no_event_is_not_a_boundary_pass(self):
        raw, reference, factors = boundary_fixture()
        report = boundary_evidence(raw, reference, factors[:1], "2026-01-08", [r["trade_date"] for r in raw],
                                   "2026-01-05", "2026-01-07")
        self.assertEqual(report["status"], "NOT_EXERCISED")

    def test_empty_captures_remain_not_run_and_never_train(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = make_capture(Path(tmp))
            report = json.loads(path.read_text("utf-8"))
            report["requests"] = [row for row in report["requests"] if row["operation"] in {"calendar", "stock-list"}]
            report["status"] = "FAILED"
            path.write_text(json.dumps(report), encoding="utf-8")
            result = evaluate_adjusted_capture(path)
            self.assertEqual(result["status"], "NOT_RUN")
            self.assertEqual(len(result["missingSnapshots"]), 2)
            self.assertIsNone(result["selectedFormula"])
            self.assertEqual(result["modelParity"], "NOT_RUN")
            self.assertFalse(result["cutoverAllowed"])

    def test_capture_diagnostics_do_not_select_formula_or_include_etf_as_stock(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = evaluate_adjusted_capture(make_capture(Path(tmp), extra_etf=True))
            self.assertEqual(result["status"], "PARTIAL")
            self.assertEqual(result["checks"][-1]["code"], "sh.588000")
            self.assertEqual(result["checks"][-1]["status"], "NOT_APPLICABLE")
            self.assertIsNone(result["selectedFormula"])
            self.assertEqual(result["adjustmentFormula"], "NOT_ACCEPTED")

    def test_verified_holiday_is_not_a_deferred_batch_date(self):
        """日历核实的休市跳过独立保留，不冒充空行情，也不使已有批量日的公式对照漏行。"""
        with tempfile.TemporaryDirectory() as tmp:
            result = evaluate_adjusted_capture(make_capture(Path(tmp), with_holiday=True))
            self.assertEqual(result["skippedDates"], [dict(date="2026-01-04", reason="CALENDAR_NON_TRADING")])
            for check in result["checks"]:
                if check.get("basis") == "batch-raw-sample":
                    self.assertEqual(check["deferredBatchDates"], [])
                    self.assertEqual(check["candidates"][0]["comparison"]["missingRows"], 0)


if __name__ == "__main__":
    unittest.main()
