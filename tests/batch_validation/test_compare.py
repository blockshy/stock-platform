"""比较器的离线反例测试，验证门槛能发现漏行、重复、空值及错误复权，不调用真实数据源。"""
import json
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path

from compare import (KEY_FIELDS, audit_factor_coverage, canonical_daily_row, compare_daily,
                     compare_values, java_projection, load_snapshot, run_plan, storage_projection)


def vendor_row(**changes):
    """构造字段齐全的合成供应方记录；空值必须显式存在，不能用省略字段代替。"""
    row = dict(code="sz.000001", date="2026-01-05", open="10.000000", high="11.2", low="9.8",
               close="10.5", preclose="10.1", volume="100000", amount="1010101.55", adjustflag="3",
               turn="1.25", tradestatus="1", pctChg="3.960396", isST="0", peTTM="12.5", pbMRQ="1.2",
               psTTM="", pcfNcfTTM="-3.1")
    row.update(changes)
    return row


class DailyComparisonTest(unittest.TestCase):
    """原始字段、Java 派生值与完整性分别验收，不能通过精度压缩逃过原始差异。"""

    def setUp(self):
        self.row = canonical_daily_row(vendor_row())
        self.keys = [{key: self.row[key] for key in KEY_FIELDS}]

    def test_decimal_format_is_equal_but_missing_coverage_is_incomplete(self):
        candidate = canonical_daily_row(vendor_row(open="10.0"))
        report = compare_daily([self.row], [candidate])
        self.assertEqual(report["values"], "PASS")
        self.assertEqual(report["status"], "INCOMPLETE")
        self.assertEqual(compare_daily([self.row], [candidate], expected_keys=self.keys)["status"], "PASS")

    def test_coverage_detects_same_missing_row_on_both_sides(self):
        extra = dict(self.keys[0], trade_date="2026-01-06")
        report = compare_daily([self.row], [self.row], expected_keys=self.keys + [extra])
        self.assertEqual(report["values"], "PASS")
        self.assertEqual(report["completeness"]["candidateMissing"], 1)
        self.assertEqual(report["status"], "FAIL")

    def test_duplicate_page_is_not_silently_deduplicated(self):
        with self.assertRaisesRegex(ValueError, "重复业务主键"):
            compare_daily([self.row], [self.row, self.row], expected_keys=self.keys)

    def test_missing_column_and_nan_are_invalid(self):
        source = vendor_row()
        del source["peTTM"]
        with self.assertRaisesRegex(ValueError, "必需字段"):
            canonical_daily_row(source)
        for value in ("NaN", "Infinity", "invalid", True):
            with self.subTest(value=value), self.assertRaises(ValueError):
                canonical_daily_row(vendor_row(close=value))

    def test_undeclared_field_is_not_silently_discarded(self):
        with self.assertRaisesRegex(ValueError, "未声明字段"):
            canonical_daily_row(vendor_row(unexpected="123"))

    def test_null_does_not_equal_zero(self):
        candidate = canonical_daily_row(vendor_row(psTTM="0"))
        report = compare_daily([self.row], [candidate], expected_keys=self.keys)
        self.assertEqual(report["differences"], 1)

    def test_adjustment_flag_is_part_of_key(self):
        candidate = canonical_daily_row(vendor_row(adjustflag="2"))
        report = compare_daily([self.row], [candidate], expected_keys=self.keys)
        self.assertEqual((report["missingRows"], report["extraRows"]), (1, 1))

    def test_empty_holiday_needs_explicit_evidence(self):
        with self.assertRaises(ValueError):
            compare_daily([], [], expected_keys=[])
        self.assertEqual(compare_daily([], [], allow_empty=True)["status"], "INCOMPLETE")
        self.assertEqual(compare_daily([], [], allow_empty=True, expected_keys=[])["status"], "PASS")

    def test_asset_market_and_integer_volume_guards(self):
        for source in (vendor_row(code="sh.588000"), vendor_row(market="SH"), vendor_row(volume="1.5"), vendor_row(code=1)):
            with self.subTest(source=source["code"]), self.assertRaises(ValueError):
                canonical_daily_row(source)
        etf = vendor_row(code="sh.588000")
        for field in ("isST", "peTTM", "pbMRQ", "psTTM", "pcfNcfTTM"):
            etf.pop(field)
        self.assertIsNone(canonical_daily_row(etf, asset="etf")["is_st"])
        etf["isST"] = "0"
        with self.assertRaises(ValueError):
            canonical_daily_row(etf, asset="etf")

    def test_java_projection_preserves_division_rounding_order(self):
        row = canonical_daily_row(vendor_row(high="2", low="1", preclose="3", close="3.5"))
        projected = java_projection(row)
        self.assertEqual(projected["amplitude"], Decimal("33.33333300"))
        self.assertEqual(projected["chg_amt"], Decimal("0.5"))
        self.assertEqual(storage_projection(projected)["amplitude"], Decimal("33.3333"))

    def test_storage_agreement_cannot_hide_raw_difference(self):
        a = canonical_daily_row(vendor_row(close="10.500001"))
        b = canonical_daily_row(vendor_row(close="10.500002"))
        self.assertEqual(compare_daily([a], [b], expected_keys=self.keys)["status"], "FAIL")
        self.assertEqual(compare_daily([storage_projection(a)], [storage_projection(b)], expected_keys=self.keys)["status"], "PASS")


class ResultAndFactorTest(unittest.TestCase):
    """数值容差只在批准路径生效；结果标签、股票集合与元数据映射依然有硬门槛。"""

    def test_probability_tolerance_does_not_cover_labels(self):
        a, b = {"p_up": "0.5", "grade": "A"}, {"p_up": "0.5000005", "grade": "B"}
        report = compare_values(a, b, numeric_paths=["$.p_up"], tolerance="probability-persisted")
        self.assertEqual(report["differences"], 1)
        self.assertEqual(report["examples"][0]["path"], "$.grade")
        self.assertEqual(compare_values(a, b)["differences"], 2)

    def test_missing_field_and_array_order_are_not_ignored(self):
        self.assertEqual(compare_values({"x": None}, {})["status"], "FAIL")
        self.assertEqual(compare_values(["A", "B"], ["B", "A"])["status"], "FAIL")

    def test_metadata_requires_exact_pair_and_reason(self):
        mappings = {"$.runId": {"reference": 1, "candidate": 2, "reason": "隔离重放任务身份"}}
        self.assertEqual(compare_values({"runId": 1}, {"runId": 2}, metadata_mappings=mappings)["status"], "PASS")
        self.assertEqual(compare_values({"runId": 1}, {"runId": 3}, metadata_mappings=mappings)["status"], "FAIL")
        with self.assertRaises(ValueError):
            compare_values({}, {}, metadata_mappings=mappings)
        with self.assertRaises(ValueError):
            compare_values({"status": "A"}, {"status": "B"}, metadata_mappings={"$.status": mappings["$.runId"]})

    def test_factor_boundary_requires_prior_positive_factor(self):
        keys = [dict(code="000001", market="SZ")]
        event = dict(code="000001", market="SZ", effective_date="2026-01-06", forward_factor="0.9", backward_factor="1.1")
        args = dict(history_start="2026-01-05", asof="2026-01-10")
        report = audit_factor_coverage([event], keys, **args)
        self.assertEqual(report["status"], "INCOMPLETE")
        baseline = dict(event, effective_date="2025-06-01")
        report = audit_factor_coverage([baseline, event], keys, **args)
        self.assertEqual(report["status"], "PASS")
        self.assertEqual(report["priceEquivalence"], "NOT_CHECKED")
        with self.assertRaises(ValueError):
            audit_factor_coverage([dict(baseline, forward_factor="")], keys, **args)
        with self.assertRaises(ValueError):
            audit_factor_coverage([baseline, baseline], keys, **args)

    def test_json_precision_and_plan_deferred_status(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "a.json").write_text('{"p":0.12345678901234567890}', encoding="utf-8")
            (root / "b.json").write_text('{"p":0.12345678901234567890}', encoding="utf-8")
            value = load_snapshot(root / "a.json")["p"]
            self.assertEqual(str(value), "0.12345678901234567890")
            plan = {"checks": [dict(name="stored", kind="result", reference="a.json", candidate="b.json"),
                               dict(name="recomputed", deferredReason="尚未运行隔离重算")]}
            report = run_plan(plan, root)
            self.assertEqual(report["status"], "INCOMPLETE")
            self.assertFalse(report["recomputedByThisTool"])

    def test_duplicate_json_field_is_invalid(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "duplicate.json"
            target.write_text('{"close":1,"close":2}', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "重复字段"):
                load_snapshot(target)


if __name__ == "__main__":
    unittest.main()
