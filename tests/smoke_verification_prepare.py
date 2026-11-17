"""只在隔离环境执行一次 Java → Python PREPARE 最小链路验收。

允许的唯一写请求是 127.0.0.1:18080/api/prob/runs，固定 type=PREPARE、
asof=2026-11-16、limitStocks=1。提交前校验进程记录、监听 PID、命令路径、
数据库实际身份与账户授权；不训练、不预测、不调用数据源，也不创建生产任务。
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote, urlsplit
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "engines/probability"
RUNTIME = ROOT / ".local/runtime/verification"
API = "http://127.0.0.1:18080"
ASOF = "2026-11-16"
sys.path.insert(0, str(ENGINE))
from engine.config import redact_text
from engine.db import Db
from verify_probability_database import require_verification_url


def same_path(left, right) -> bool:
    return os.path.normcase(str(Path(left).resolve())) == os.path.normcase(str(Path(right).resolve()))


def utc_identity(value: str) -> tuple[str, str]:
    """保留 Windows 七位小数的 100ns 身份精度，不交给仅支持六位的 Python 3.10 解析。

    两侧均来自 Get-Process 的 UTC round-trip 格式，只接受 Z 或 +00:00；
    小数不足七位时右补零，比对仍是精确时刻而非秒级近似。
    """
    match = re.fullmatch(r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,7}))?(?:Z|\+00:00)", value)
    if not match:
        raise ValueError("进程启动时间不是预期 UTC round-trip 格式")
    return match.group(1), (match.group(2) or "").ljust(7, "0")


def powershell_json(command: str):
    """仅执行本文件内固定的只读进程诊断，隐藏子窗口且限制等待时间。"""
    result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command],
                            capture_output=True, timeout=20, creationflags=subprocess.CREATE_NO_WINDOW)
    if result.returncode:
        raise RuntimeError("只读 Windows 进程身份核验失败")
    return json.loads(result.stdout.decode("utf-8-sig").strip())


def verify_backend() -> dict:
    """记录中的声明必须与真实进程和监听者一致，拒绝借用旧生产进程做验收。"""
    record = json.loads((RUNTIME / "backend-process.json").read_text("utf-8-sig"))
    if (record.get("profile") != "verification" or record.get("database") != "stock_platform_verify"
            or record.get("port") != 18080 or record.get("mode") != "native"
            or not same_path(record["sourceRoot"], ROOT)
            or not same_path(record["engineDirectory"], ENGINE)):
        raise ValueError("后端记录不是新项目原生隔离服务，禁止提交任务")
    pid = int(record["id"])
    actual = powershell_json(
        "[Console]::OutputEncoding=New-Object Text.UTF8Encoding($false);"
        f"$p=Get-Process -Id {pid} -ErrorAction Stop;"
        f"$c=Get-CimInstance Win32_Process -Filter 'ProcessId={pid}';"
        "$owners=@(Get-NetTCPConnection -State Listen -LocalPort 18080 -ErrorAction Stop|"
        "Select-Object -ExpandProperty OwningProcess -Unique);"
        "[pscustomobject]@{pid=$p.Id;path=$p.Path;started=$p.StartTime.ToUniversalTime().ToString('o');"
        "command=$c.CommandLine;owners=$owners}|ConvertTo-Json -Compress")
    expected_start = utc_identity(record["startedAt"])
    actual_start = utc_identity(actual["started"])
    if (not same_path(actual["path"], record["executable"]) or actual_start != expected_start
            or actual["owners"] != [pid]
            or record["commandMarker"].casefold() not in actual["command"].casefold()):
        raise ValueError("实际后端 PID/启动时间/监听端口/命令路径与隔离记录不一致")
    return dict(pid=pid, port=18080, engineDirectory=str(ENGINE), executable=actual["path"])


def verify_database_account() -> Db:
    """凭据只在内存使用；检查实际账户和授权范围，报告只给布尔结果，不输出账户信息。"""
    secrets = json.loads((RUNTIME / "secrets.json").read_text("utf-8-sig"))
    username = str(secrets["dbUser"])
    url = (f"mysql+pymysql://{quote(username, safe='')}:{quote(str(secrets['dbPassword']), safe='')}"
           "@127.0.0.1:13306/stock_platform_verify?charset=utf8mb4")
    require_verification_url(url)
    db = Db({"db": {"url": url}})
    identity = db.query("SELECT DATABASE() AS db, CURRENT_USER() AS authenticated, @@port AS port")[0]
    if (identity["db"] != "stock_platform_verify" or int(identity["port"]) != 13306
            or identity["authenticated"].split("@", 1)[0] != username):
        raise ValueError("隔离数据库实际账户或数据库身份不匹配")
    grants = [str(next(iter(row.values()))) for row in db.query("SHOW GRANTS FOR CURRENT_USER")]
    for grant in grants:
        normalized = grant.upper().replace("`", "")
        if "GRANT USAGE ON *.*" not in normalized and " ON STOCK_PLATFORM_VERIFY.* " not in normalized:
            raise ValueError("验收账户存在隔离库之外授权，禁止自动提交任务")
    return db


def request_json(route: str, *, submit=False):
    """只放行固定 PREPARE 写请求和任务状态 GET，禁止接收任意 URL 或任务类型。"""
    if submit:
        if route != "/api/prob/runs":
            raise ValueError("写请求路径未获准")
        payload = json.dumps(dict(type="PREPARE", asof=ASOF, limitStocks=1)).encode("utf-8")
        request = Request(API + route, data=payload, method="POST",
                          headers={"Content-Type": "application/json", "Accept": "application/json"})
    else:
        if not route.startswith("/api/prob/runs/") or not route.rsplit("/", 1)[1].isdigit():
            raise ValueError("只允许读取本次任务状态")
        request = Request(API + route, method="GET", headers={"Accept": "application/json"})
    with urlopen(request, timeout=30) as response:
        body = json.loads(response.read().decode("utf-8"))
    if body.get("code") != 0:
        raise RuntimeError("验收任务接口返回业务错误")
    return body["data"]


def python_descendants(parent_pid: int) -> list[dict]:
    """捕获 Java 启动的 venv 启动器及其实际 Python 子进程，不操作这些进程。"""
    return powershell_json(
        "[Console]::OutputEncoding=New-Object Text.UTF8Encoding($false);"
        "$all=@(Get-CimInstance Win32_Process -Filter \"Name='python.exe' OR Name='pythonw.exe'\");"
        f"$parents=@({parent_pid});$found=@();"
        "for($depth=0;$depth -lt 3;$depth++){"
        "$children=@($all|Where-Object{$parents -contains $_.ParentProcessId});"
        "$found+=$children;$parents=@($children|Select-Object -ExpandProperty ProcessId)};"
        "ConvertTo-Json -InputObject @($found|ForEach-Object{[pscustomobject]@{"
        "pid=$_.ProcessId;parentPid=$_.ParentProcessId;path=$_.ExecutablePath;command=$_.CommandLine}}) -Compress")


def run() -> dict:
    backend = verify_backend()
    db = verify_database_account()
    # 若已有验收计算，禁止另开任务；不接管、停止或等待其他人的工作。
    if int(db.scalar("SELECT COUNT(*) FROM prob_run WHERE status='RUNNING'") or 0):
        raise ValueError("隔离库已有运行中概率任务，禁止并发 smoke")
    prediction_rows = int(db.scalar("SELECT COUNT(*) FROM prob_prediction") or 0)
    before_models = {str(path) for path in (RUNTIME / "engine").rglob("*.pkl")}
    started = time.time()
    submitted = request_json("/api/prob/runs", submit=True)
    run_id = int(submitted["runId"])
    print(json.dumps(dict(stage="SUBMITTED", runId=run_id, type="PREPARE", limitStocks=1)), flush=True)
    observed = {}
    deadline = time.monotonic() + 240
    while True:
        for process in python_descendants(backend["pid"]):
            observed[process["pid"]] = process
        run_info = request_json(f"/api/prob/runs/{run_id}")
        if run_info.get("status") != "RUNNING":
            break
        if time.monotonic() >= deadline:
            raise TimeoutError("PREPARE 验收超过四分钟；未停止任务，请由协调者检查")
        time.sleep(1)
    expected_python = ENGINE / ".venv/Scripts/python.exe"
    launchers = [process for process in observed.values()
                 if process.get("path") and same_path(process["path"], expected_python)
                 and "engine.cli prepare" in process.get("command", "")
                 and f"--run-id {run_id}" in process.get("command", "")]
    if not launchers:
        raise ValueError("未捕获新 venv 的 Java 子进程证据，不能仅据任务成功判定链路正确")
    snapshot = db.query("SELECT run_id,run_type,status,asof_date,n_universe,n_ok,n_fail,config_json "
                        "FROM prob_run WHERE run_id=%s", (run_id,))[0]
    config = snapshot.pop("config_json")
    config = json.loads(config) if isinstance(config, str) else config
    if urlsplit(config["db"]["url"]).path != "/stock_platform_verify":
        raise ValueError("Python 实际配置快照没有指向验收库")
    if (snapshot["status"] != "SUCCESS" or str(snapshot["asof_date"]) != ASOF
            or snapshot["n_universe"] != 1 or snapshot["n_ok"] != 1 or snapshot["n_fail"] != 0):
        raise ValueError("PREPARE 终态、基准日或一只股票的计数不符")
    log_files = [path for path in (RUNTIME / "engine/logs").glob("engine-*.log")
                 if path.stat().st_mtime >= started - 2]
    if not log_files or not any(f"PREPARE asof={ASOF}" in path.read_text("utf-8") for path in log_files):
        raise ValueError("未找到验证运行目录内本次 PREPARE 日志")
    after_models = {str(path) for path in (RUNTIME / "engine").rglob("*.pkl")}
    if after_models != before_models or int(db.scalar("SELECT COUNT(*) FROM prob_prediction") or 0) != prediction_rows:
        raise ValueError("PREPARE 意外改变模型文件集合或预测明细数量")
    return dict(status="PASS", backend=backend, database="stock_platform_verify", accountScopeVerified=True,
                run=snapshot, elapsedSeconds=round(time.time() - started, 2),
                pythonLaunchers=launchers, engineRuntime=str(RUNTIME / "engine"),
                logFiles=[str(path) for path in log_files], newModelFiles=0, predictionRowsUnchanged=prediction_rows,
                writeRequest=dict(method="POST", url=API + "/api/prob/runs", type="PREPARE", asof=ASOF, limitStocks=1))


if __name__ == "__main__":
    destination = RUNTIME / "reports/java-python-prepare-smoke.json"
    try:
        report = run()
    except Exception as error:
        report = dict(status="FAIL", error=redact_text(str(error)), productionWrites=False)
        raise
    finally:
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, default=str), flush=True)
