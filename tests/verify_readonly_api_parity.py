"""比较原服务与隔离服务的已存结果，只发送 GET，不触发采集、重算或外部数据源。

固定 2026-11-16 结果日期；保存脱敏后的完整响应、字段集合、规范化摘要及逐字段差异。
响应包装层的请求时间戳单独保留，但不作为业务结果差异。活动采集、动态总览和任务列表不参与比较。
"""
from __future__ import annotations

import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "engines/probability"))
from engine.config import redacted_copy

ASOF = "2026-11-16"
BASES = {"legacy": "http://127.0.0.1:8080", "verification": "http://127.0.0.1:18080"}
OUTPUT = ROOT / ".local/runtime/verification/reports/api-parity"


def get_response(side: str, route: str) -> dict:
    """目标限定为两个已授权本机服务；请求方法在代码中固定，不允许传入 POST。"""
    url = BASES[side] + route
    parsed = urlsplit(url)
    if parsed.hostname != "127.0.0.1" or parsed.port not in {8080, 18080} or not route.startswith("/api/"):
        raise ValueError("只允许已授权本机 API 的 GET 验收")
    with urlopen(Request(url, headers={"Accept": "application/json"}, method="GET"), timeout=60) as response:
        body = redacted_copy(json.loads(response.read().decode("utf-8")))
    if body.get("code") != 0:
        raise RuntimeError(f"只读接口业务失败：{route}，code={body.get('code')}")
    return body


def collect(side: str, route: str, parameters: dict | None = None, paginated=False) -> dict:
    """分页结果完整收集，保留每页响应；各页总数变化则拒绝把动态数据声称为固定快照。"""
    params = dict(parameters or {})
    if paginated:
        params.update(page=1, size=200)
    responses = []
    first = get_response(side, route + ("?" + urlencode(params) if params else ""))
    responses.append(first)
    data = first.get("data")
    if paginated:
        items = list(data.get("items", []))
        for page in range(2, int(data.get("pages", 0)) + 1):
            params["page"] = page
            response = get_response(side, route + "?" + urlencode(params))
            if response["data"].get("total") != data.get("total"):
                raise RuntimeError("分页期间结果总数变化，不能作为固定快照验收")
            responses.append(response)
            items.extend(response["data"].get("items", []))
        data = dict(data, items=items)
        if len(items) != int(data.get("total", 0)):
            raise RuntimeError("分页返回行数与总数不一致")
    # 只忽略包装层请求 timestamp；created_at、指标日期、任务归属等业务字段仍逐项比较。
    business = {key: value for key, value in first.items() if key not in {"data", "timestamp"}}
    business["data"] = data
    return dict(responses=responses, business=business)


def canonical(value) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def fields(value, path="$", result=None):
    """记录所有字段路径，数组统一使用 []，避免报告被成千上万个行号淹没。"""
    result = result if result is not None else set()
    if isinstance(value, dict):
        for key, item in value.items():
            fields(item, path + "." + key, result)
    elif isinstance(value, list):
        result.add(path + "[]")
        for item in value:
            fields(item, path + "[]", result)
    else:
        result.add(path)
    return sorted(result)


def differences(left, right) -> dict:
    """完整计数差异，样本最多保留 100 项；不会因样本截断漏记总差异量。"""
    result = {"count": 0, "examples": []}

    def record(path, before, after):
        result["count"] += 1
        if len(result["examples"]) < 100:
            result["examples"].append(dict(path=path, legacy=before, verification=after))

    def visit(a, b, path):
        if isinstance(a, dict) and isinstance(b, dict):
            for key in sorted(set(a) | set(b)):
                if key not in a or key not in b:
                    record(path + "." + key, a.get(key, "<missing>"), b.get(key, "<missing>"))
                else:
                    visit(a[key], b[key], path + "." + key)
        elif isinstance(a, list) and isinstance(b, list):
            if len(a) != len(b):
                record(path + ".length", len(a), len(b))
            for index, (item_a, item_b) in enumerate(zip(a, b)):
                visit(item_a, item_b, path + f"[{index}]")
        elif a != b:
            record(path, a, b)
    visit(left, right, "$")
    return result


def compare(name: str, route: str, parameters=None, paginated=False) -> dict:
    results = {side: collect(side, route, parameters, paginated) for side in BASES}
    for side, response in results.items():
        (OUTPUT / f"{name}.{side}.json").write_text(
            json.dumps(response, ensure_ascii=False, indent=2), encoding="utf-8")
    before, after = results["legacy"]["business"], results["verification"]["business"]
    delta = differences(before, after)
    summary = dict(name=name, route=route, parameters=parameters or {}, status="PASS" if delta["count"] == 0 else "DIFFERENT",
                   legacySha256=hashlib.sha256(canonical(before).encode("utf-8")).hexdigest(),
                   verificationSha256=hashlib.sha256(canonical(after).encode("utf-8")).hexdigest(),
                   legacyFields=fields(before), verificationFields=fields(after), differences=delta)
    data = after.get("data")
    summary["records"] = (len(data["items"]) if isinstance(data, dict) and "items" in data
                          else len(data["predictions"]) if isinstance(data, dict) and "predictions" in data
                          else len(data) if isinstance(data, list) else None)
    if isinstance(data, dict) and "total" in data:
        summary["total"] = data["total"]
    print(json.dumps({key: summary[key] for key in ("name", "status", "records")}, ensure_ascii=False), flush=True)
    return summary


def stored_observations() -> dict:
    """补充已保存响应的解释，不再发送请求，也不把无文件模型误报为文件丢失。"""
    stock = json.loads((OUTPUT / "prob_stock.verification.json").read_text("utf-8"))["business"]["data"]
    if stock.get("asof") != ASOF:
        raise ValueError("个股接口返回日期偏离固定基准，不能作为本次等效证据")
    models = json.loads((OUTPUT / "prob_models.verification.json").read_text("utf-8"))["business"]["data"]["items"]
    with_files = [row for row in models if row.get("artifact_path")]
    metadata_only = [row["model_name"] for row in models if not row.get("artifact_path")]
    missing = [row["artifact_path"] for row in with_files if not Path(row["artifact_path"]).is_file()]
    return dict(stockAsOf=stock["asof"], stockPredictions=len(stock["predictions"]),
                stockPricePoints=len(stock.get("priceSeries", [])),
                registeredArtifactFiles=len(with_files), missingArtifactFiles=missing,
                metadataOnlyModels=metadata_only,
                artifactPathNote="历史 registry 仍保留旧引擎绝对路径；本次只读验收不重写，旧目录保留期间文件可用。",
                configNote="/prob/config 展示文件模板，不是进程环境覆盖后的配置；验证库真实身份由独立只读 DB 守卫核验。")


if __name__ == "__main__":
    OUTPUT.mkdir(parents=True, exist_ok=True)
    checks = []
    checks.append(compare("scores", "/api/rankings/scores", dict(date=ASOF, adjustFlag=2), True))
    checks.append(compare("scores_stock", "/api/rankings/scores", dict(date=ASOF, adjustFlag=2, keyword="000001"), True))
    checks.append(compare("short_term", "/api/short-term/forecasts", dict(date=ASOF, adjustFlag=2), True))
    runs = get_response("verification", "/api/short-term/history/runs?adjustFlag=2&limit=100")["data"]
    fixed_runs = [run for run in runs if run.get("forecastDate") == ASOF]
    for run in fixed_runs:
        run_id = run["id"]
        checks.append(compare(f"short_history_{run_id}", f"/api/short-term/history/runs/{run_id}/summary", {}, True))
        checks.append(compare(f"short_stock_{run_id}", f"/api/short-term/history/runs/{run_id}/stocks/000001"))
    for horizon in (1, 3, 5, 10):
        checks.append(compare(f"prob_h{horizon}", "/api/prob/predictions", dict(asof=ASOF, horizon=horizon), True))
    checks.append(compare("prob_stock", "/api/prob/stocks/SZ/000001"))
    checks.append(compare("prob_stock_history", "/api/prob/stocks/SZ/000001/history", dict(horizon=1, **{"from": ASOF, "to": ASOF})))
    for name in ("metrics", "calibration", "methods"):
        checks.append(compare(f"prob_eval_{name}", f"/api/prob/eval/{name}"))
    checks.append(compare("prob_config", "/api/prob/config"))
    checks.append(compare("prob_features", "/api/prob/features"))
    checks.append(compare("prob_models", "/api/prob/models"))
    report = dict(generatedAt=datetime.now(timezone.utc).isoformat(), asof=ASOF,
                  status="PASS" if all(check["status"] == "PASS" for check in checks) else "DIFFERENT",
                  comparison="业务响应逐字段精确比较；仅忽略包装层请求 timestamp。",
                  methods=["GET"], checks=checks,
                  observations=stored_observations(),
                  limitations=["比较已有结果，不触发重算。", "概率个股接口不支持asof参数，需确认返回asof仍为固定日期。",
                               "完整响应已脱敏；不包含活动采集/任务列表。"])
    (OUTPUT / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(dict(status=report["status"], checks=len(checks), report=str(OUTPUT / "summary.json")), ensure_ascii=False))
    raise SystemExit(0 if report["status"] == "PASS" else 1)
