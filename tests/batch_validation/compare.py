"""方案 A 的纯离线验收工具：只读取本地快照，不导入引擎、不连接数据库或行情源。

原始数据不填零、不猜复权因子。Decimal 精确保留供应方十进制值；Java 派生值与
落库精度投影作为显式独立步骤，不能用落库四舍五入掩盖原始接口差异。
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
from datetime import date
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP, localcontext
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
KEY_FIELDS = ("code", "market", "trade_date", "adjustflag")
NUMERIC_FIELDS = ("open", "high", "low", "close", "preclose", "volume", "amount",
                  "turnover", "pct_chg", "pe_ttm", "pb_mrq", "ps_ttm", "pcf_ncf_ttm")
STATUS_FIELDS = ("tradestatus", "is_st")
VENDOR = dict(zip(NUMERIC_FIELDS + STATUS_FIELDS,
                  ("open", "high", "low", "close", "preclose", "volume", "amount",
                   "turn", "pctChg", "peTTM", "pbMRQ", "psTTM", "pcfNcfTTM", "tradestatus", "isST")))
JAVA = dict(zip(NUMERIC_FIELDS + STATUS_FIELDS,
                ("open", "high", "low", "close", "preclose", "volume", "amount",
                 "turnover", "pctChg", "peTtm", "pbMrq", "psTtm", "pcfNcfTtm", "tradeStatus", "isSt")))
STOCK_ONLY = {"is_st", "pe_ttm", "pb_mrq", "ps_ttm", "pcf_ncf_ttm"}
SCALES = {**dict.fromkeys(("open", "high", "low", "close", "preclose", "amplitude", "pct_chg", "chg_amt", "turnover"), 4),
          **dict.fromkeys(("pe_ttm", "pb_mrq", "ps_ttm", "pcf_ncf_ttm"), 6), "amount": 2, "volume": 2}
# 仅概率数值已有获审查容差；其他模块和原始行情默认精确比较，不能自动扩大门槛。
TOLERANCES = {"exact": (Decimal(0), Decimal(0)),
              "probability-memory": (Decimal("1e-8"), Decimal("1e-7")),
              "probability-persisted": (Decimal("1e-6"), Decimal(0))}
METADATA_FIELDS = {"run_id", "runId", "task_id", "taskId", "timestamp", "created_at", "createdAt",
                   "started_at", "finished_at", "artifact_path", "artifactPath", "model_version", "modelVersion"}


def decimal_value(value, field):
    """空字符串与 JSON null 都代表真实空值；缺字段在上层单独拒绝，非数值不得降级为 0。"""
    if value is None or value == "":
        return None
    if isinstance(value, bool):
        raise ValueError(f"{field} 不允许布尔值代替数值")
    try:
        result = Decimal(str(value))
    except (InvalidOperation, ValueError):
        raise ValueError(f"{field} 不是合法十进制数") from None
    if not result.is_finite():
        raise ValueError(f"{field} 不允许 NaN 或 Infinity")
    return result


def required(row, key):
    """字段缺失与字段存在但为空属于不同问题，不能用 dict.get 静默合并。"""
    if key not in row:
        raise ValueError(f"快照缺少必需字段 {key}")
    return row[key]


def canonical_daily_row(row, representation="vendor", *, code=None, market=None, asset="stock"):
    """统一供应方字段、StockDailyVO 和 SQL 导出字段，不改变数值单位或复权标记。

    逐股响应没有 code 时必须显式提供该次请求的 code/market；批量响应通常有 sh.600000
    形式代码。ETF 的股票估值/ST 字段允许供应方不返回，但若非空则报错，不能悄悄丢弃。
    """
    if representation not in {"vendor", "java", "database"} or asset not in {"stock", "etf"}:
        raise ValueError("未知的快照表示或资产类型")
    raw_code = row.get("code", code)
    raw_market = row.get("market", market)
    if not isinstance(raw_code, str):
        raise ValueError("代码必须保留字符串，禁止丢失前导零")
    if re.fullmatch(r"(?i)(sh|sz)\.\d{6}", raw_code):
        prefix, raw_code = raw_code.split(".")
        if raw_market is not None and str(raw_market).upper() != prefix.upper():
            raise ValueError("代码前缀与市场字段冲突")
        raw_market = prefix
    if not re.fullmatch(r"\d{6}", raw_code) or str(raw_market).upper() not in {"SH", "SZ"}:
        raise ValueError("行情代码/市场不完整")
    allowed = raw_code.startswith("58") if asset == "etf" else raw_code.startswith(("00", "30", "60"))
    if not allowed:
        raise ValueError("快照混入本次资产范围之外的证券")
    date_key = {"vendor": "date", "java": "tradeDate", "database": "trade_date"}[representation]
    day = str(required(row, date_key))
    if date.fromisoformat(day).isoformat() != day:
        raise ValueError("交易日期必须为 YYYY-MM-DD")
    flag_key = "adjustFlag" if representation == "java" else "adjustflag"
    flag = decimal_value(required(row, flag_key), flag_key)
    if flag not in {Decimal(1), Decimal(2), Decimal(3)} or (asset == "etf" and flag != 3):
        raise ValueError("复权标记缺失、非法，或 ETF 被误标为复权数据")
    result = dict(code=raw_code, market=str(raw_market).upper(), trade_date=day, adjustflag=int(flag))
    aliases = VENDOR if representation == "vendor" else JAVA if representation == "java" else {f: f for f in NUMERIC_FIELDS + STATUS_FIELDS}
    allowed_fields = set(aliases.values()) | {"code", "market", date_key, flag_key}
    if representation != "vendor":
        allowed_fields |= {"amplitude", "chgAmt", "dataSource"} if representation == "java" else {"amplitude", "chg_amt", "data_source"}
    if set(row) - allowed_fields:
        raise ValueError("行情包含未声明字段，须先审查映射，不能静默丢弃")
    for field, source in aliases.items():
        if asset == "etf" and field in STOCK_ONLY:
            if source in row and decimal_value(row[source], source) is not None:
                raise ValueError("ETF 不允许携带个股估值或 ST 数据")
            result[field] = None
            continue
        value = decimal_value(required(row, source), source)
        if field in STATUS_FIELDS and value not in {None, Decimal(0), Decimal(1)}:
            raise ValueError(f"{source} 状态必须为 0、1 或空")
        if field == "volume" and value is not None and value != value.to_integral_value():
            raise ValueError("成交量不是整数股，不能沿用 Java Long 字段而静默截断")
        result[field] = value
    if representation != "vendor":
        result["amplitude"] = decimal_value(required(row, "amplitude"), "amplitude")
        result["chg_amt"] = decimal_value(required(row, "chgAmt" if representation == "java" else "chg_amt"), "chg_amt")
        result["data_source"] = required(row, "dataSource" if representation == "java" else "data_source")
    return result


def java_projection(row):
    """按现有 CrawlTaskService 的确切顺序派生振幅/涨跌额，不计算任何复权因子。

    Java 先 divide(...,8,HALF_UP) 再乘 100；不能替换为先乘 100 再保留八位。
    此投影仅用于对比预入库 VO，不能当作批量接口与逐股原始字段一致的证明。
    """
    result = dict(row)
    high, low, close, preclose = (row[k] for k in ("high", "low", "close", "preclose"))
    with localcontext() as ctx:
        ctx.prec = 60
        result["amplitude"] = ((high - low) / preclose).quantize(Decimal("1e-8"), rounding=ROUND_HALF_UP) * 100 \
            if high is not None and low is not None and preclose not in {None, 0} else None
        result["chg_amt"] = close - preclose if close is not None and preclose is not None else None
    result["data_source"] = "baostock"
    return result


def storage_projection(row):
    """显式模拟现有 DECIMAL 列精度；报告必须标记落库层，原始层仍保留完整小数。"""
    result = dict(row)
    with localcontext() as ctx:
        ctx.prec = 60
        for field, scale in SCALES.items():
            if field in result and result[field] is not None:
                result[field] = result[field].quantize(Decimal(1).scaleb(-scale), rounding=ROUND_HALF_UP)
    return result


def audit_factor_coverage(factors, price_keys, *, history_start, asof):
    """检查因子初始化证据，不推断供应商的前/后复权公式或乘法方向。

    因子输入须由采集适配层显式映射为 code/market/effective_date/forward_factor/
    backward_factor。每只证券必须有不晚于窗口开始日的正因子；后续事件也保留原日期。
    缺因子一律待验，不能补成 1。分红当天边界和价格精度仍需逐股复权接口实测对照。
    """
    start, end = date.fromisoformat(history_start), date.fromisoformat(asof)
    if start > end:
        raise ValueError("因子检查日期范围倒置")
    indexed = _index(factors, ("code", "market", "effective_date"))
    securities = {(required(row, "code"), required(row, "market")) for row in price_keys}
    if not securities:
        raise ValueError("缺少待验证证券集合")
    initialized, events = set(), 0
    for (code, market, effective_date), row in indexed.items():
        event_day = date.fromisoformat(effective_date)
        if event_day > end:
            raise ValueError("因子快照包含基准日之后的事件")
        for field in ("forward_factor", "backward_factor"):
            value = decimal_value(required(row, field), field)
            if value is None or value <= 0:
                raise ValueError("因子必须为明确的正数，缺失不能默认 1")
        if (code, market) in securities:
            if event_day <= start:
                initialized.add((code, market))
            else:
                events += 1
    missing = securities - initialized
    return dict(status="INCOMPLETE" if missing else "PASS", securities=len(securities),
                initialized=len(initialized), missingInitialFactor=sorted(missing), windowEvents=events,
                priceEquivalence="NOT_CHECKED", formula="NOT_ASSUMED",
                limitation="PASS 仅表示因子边界证据存在；前后复权与逐股结果数值等效必须另行比较。")


def _index(rows, keys):
    """按完整业务主键索引；分页重复不能被字典覆盖后伪装成完整数据。"""
    result = {}
    for row in rows:
        key = tuple(required(row, field) for field in keys)
        if any(value is None or value == "" for value in key):
            raise ValueError("业务主键含空值")
        if key in result:
            raise ValueError("快照包含重复业务主键，需检查分页重发或重复导出")
        result[key] = row
    return result


def compare_values(reference, candidate, *, numeric_paths=(), tolerance="exact", metadata_mappings=None):
    """递归比较完整 JSON：数组顺序保留，字段缺失与 null 区分，离散字段默认精确比较。

    数值容差只用于显式路径（数组统一 []）。元数据不允许通配忽略：每个路径必须给出
    两侧期望值与理由；模型版本只能在确认连接元数据差异后逐项映射。
    """
    if tolerance not in TOLERANCES:
        raise ValueError("未知容差档位")
    abs_tol, rel_tol = TOLERANCES[tolerance]
    mappings = metadata_mappings or {}
    for path, mapping in mappings.items():
        if path.rsplit(".", 1)[-1] not in METADATA_FIELDS or not mapping.get("reason"):
            raise ValueError("只能显式映射有理由的任务/时间/路径/版本元数据")
        required(mapping, "reference")
        required(mapping, "candidate")
    differences, examples, used = 0, [], set()

    def record(path, kind):
        nonlocal differences
        differences += 1
        if len(examples) < 50:
            # 报告仅记录路径与差异类型，避免结果对象内的配置/路径/凭据进入公开输出。
            examples.append(dict(path=path, kind=kind))

    def walk(left, right, path):
        if path in mappings:
            used.add(path)
            mapping = mappings[path]
            if left != mapping["reference"] or right != mapping["candidate"]:
                record(path, "metadata_mapping_mismatch")
        elif isinstance(left, dict) and isinstance(right, dict):
            for field in sorted(set(left) | set(right)):
                if field not in left or field not in right:
                    record(path + "." + field, "missing_field")
                else:
                    walk(left[field], right[field], path + "." + field)
        elif isinstance(left, list) and isinstance(right, list):
            if len(left) != len(right):
                record(path, "array_length")
            for a, b in zip(left, right):
                walk(a, b, path + "[]")
        elif path in numeric_paths:
            a, b = decimal_value(left, path), decimal_value(right, path)
            if (a is None) != (b is None) or (a is not None and abs(a - b) > max(abs_tol, rel_tol * max(abs(a), abs(b)))):
                record(path, "numeric_value")
        elif type(left) is not type(right) or left != right:
            record(path, "exact_value")
    walk(reference, candidate, "$")
    unused = set(mappings) - used
    if unused:
        raise ValueError("元数据映射路径未出现，不能把错误映射视为通过")
    return dict(status="PASS" if differences == 0 else "FAIL", differences=differences,
                examples=examples, tolerance=tolerance, mappedPaths=sorted(used))


def compare_daily(reference, candidate, *, expected_keys=None, allow_empty=False):
    """比较已 canonical 的完整日线；expected_keys 应由独立股票池/交易日核对提供。

    两侧同样漏行仍可能相等，因此缺少独立期望键时返回 INCOMPLETE，而不是完整性 PASS。
    非交易日可显式 allow_empty=True，但必须同时提供独立核实的空 expected_keys。
    """
    left, right = _index(reference, KEY_FIELDS), _index(candidate, KEY_FIELDS)
    if not left and not allow_empty:
        raise ValueError("参考样本为空，不能验证字段与数值等效")
    missing, extra = set(left) - set(right), set(right) - set(left)
    differences, examples = len(missing) + len(extra), []
    for key in sorted(set(left) & set(right)):
        paths = {"$." + f for f in NUMERIC_FIELDS + STATUS_FIELDS + ("amplitude", "chg_amt")}
        delta = compare_values(left[key], right[key], numeric_paths=paths)
        differences += delta["differences"]
        if delta["differences"] and len(examples) < 50:
            examples.append(dict(key=key, differences=delta["examples"]))
    coverage = {"status": "NOT_CHECKED"}
    if expected_keys is not None:
        expected = set(_index(expected_keys, KEY_FIELDS))
        coverage = dict(status="PASS" if set(left) == expected == set(right) else "FAIL",
                        expected=len(expected), referenceMissing=len(expected - set(left)),
                        candidateMissing=len(expected - set(right)),
                        referenceUnexpected=len(set(left) - expected), candidateUnexpected=len(set(right) - expected))
    status = "FAIL" if differences or coverage["status"] == "FAIL" else "PASS" if coverage["status"] == "PASS" else "INCOMPLETE"
    return dict(status=status, values="PASS" if differences == 0 else "FAIL", completeness=coverage,
                referenceRows=len(left), candidateRows=len(right), missingRows=len(missing), extraRows=len(extra),
                differences=differences, examples=examples,
                limitation="仅验证指定快照与期望键，不自动证明全市场三年覆盖、复权公式或模型重算通过。")


def load_snapshot(path):
    """支持 UTF-8 CSV 或 JSON 数组/对象；JSON 浮点直接解析为 Decimal，避免二进制舍入。"""
    path = Path(path)
    if path.suffix.lower() == ".csv":
        with path.open(encoding="utf-8-sig", newline="") as handle:
            reader = csv.DictReader(handle)
            if not reader.fieldnames or len(set(reader.fieldnames)) != len(reader.fieldnames):
                raise ValueError("CSV 表头为空或重复")
            rows = list(reader)
            if any(None in row or any(v is None for v in row.values()) for row in rows):
                raise ValueError("CSV 行列数与表头不一致")
            return rows
    def invalid_constant(_):
        raise ValueError("JSON 不允许 NaN 或 Infinity")
    def unique_object(items):
        # JSON 解析默认会覆盖重复字段；验收输入必须拒绝该歧义，保留原始证据。
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("JSON 对象包含重复字段")
            result[key] = value
        return result
    return json.loads(path.read_text(encoding="utf-8-sig"), parse_float=Decimal,
                      parse_constant=invalid_constant, object_pairs_hook=unique_object)


def run_plan(plan, base):
    """计划只声明本地文件与比较规则；不包含 SQL、URL、脚本执行或自动模型运行能力。"""
    reports = []
    for check in required(plan, "checks"):
        name = required(check, "name")
        if check.get("deferredReason"):
            reports.append(dict(name=name, status="NOT_RUN", reason=check["deferredReason"]))
            continue
        paths = {side: (base / required(check, side)).resolve() for side in ("reference", "candidate")}
        snapshots = {side: load_snapshot(path) for side, path in paths.items()}
        if check["kind"] == "daily":
            canonical = {}
            for side in paths:
                options = check.get(side + "Options", {})
                canonical[side] = [canonical_daily_row(row, **options) for row in snapshots[side]]
                for projection in check.get(side + "Projections", []):
                    functions = {"java": java_projection, "storage": storage_projection}
                    if projection not in functions:
                        raise ValueError("未知的显式投影")
                    canonical[side] = [functions[projection](row) for row in canonical[side]]
            expected = load_snapshot(base / check["expectedKeys"]) if check.get("expectedKeys") else None
            # CSV 键的复权字段是字符串，统一成整数；日期/代码/市场仍要求调用方提供完整 canonical 键。
            if expected is not None:
                for row in expected:
                    value = decimal_value(required(row, "adjustflag"), "adjustflag")
                    if value not in {Decimal(1), Decimal(2), Decimal(3)}:
                        raise ValueError("期望键含非法复权标记")
                    row["adjustflag"] = int(value)
            report = compare_daily(canonical["reference"], canonical["candidate"], expected_keys=expected,
                                   allow_empty=check.get("allowEmpty", False))
        elif check["kind"] == "result":
            report = compare_values(snapshots["reference"], snapshots["candidate"],
                                    numeric_paths=check.get("numericPaths", []), tolerance=check.get("tolerance", "exact"),
                                    metadata_mappings=check.get("metadataMappings"))
        else:
            raise ValueError("未知比较类型")
        report.update(name=name, kind=check["kind"], stage=check.get("stage", "snapshot-only"),
                      inputSha256={side: hashlib.sha256(path.read_bytes()).hexdigest() for side, path in paths.items()})
        reports.append(report)
    if not reports or len({r["name"] for r in reports}) != len(reports):
        raise ValueError("验收计划必须非空且检查名称唯一")
    status = "FAIL" if any(r["status"] == "FAIL" for r in reports) else "INCOMPLETE" if any(r["status"] != "PASS" for r in reports) else "PASS"
    return dict(status=status, checks=reports, offline=True, recomputedByThisTool=False,
                limitation="PASS 仅覆盖计划中已提供的快照；本工具不会采集、写库、启动服务或训练模型。")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    target = args.output.resolve()
    if not target.is_relative_to((ROOT / ".local").resolve()):
        parser.error("真实验收报告只允许写入本仓库 .local 目录")
    try:
        plan = load_snapshot(args.plan)
        base = args.plan.resolve().parent
        inputs = {args.plan.resolve()}
        for check in required(plan, "checks"):
            inputs.update((base / check[field]).resolve() for field in ("reference", "candidate", "expectedKeys") if field in check)
        if target in inputs:
            parser.error("报告路径不能覆盖计划或原始输入快照")
        report = run_plan(plan, base)
    except (ValueError, KeyError, TypeError, OSError):
        # 错误报告不回显原始行、连接配置或完整路径；调试时可直接调用纯函数定位字段。
        report = dict(status="INVALID_INPUT", offline=True, recomputedByThisTool=False,
                      message="输入文件、必需字段、主键或比较规则未通过严格校验。")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    print(json.dumps({"status": report["status"], "checks": len(report.get("checks", [])), "offline": True}))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
