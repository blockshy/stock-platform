"""统一配置加载（文档 §0.1、§12.2）。

config.yaml / features.yaml 使用 **JSON 语法的 YAML 1.2**（JSON 是 YAML 的合法子集）：
这样 Python 用标准库 json 即可零依赖读取，Java 端用 Jackson 也能直接 readTree，
不需要引入 PyYAML（本机 WDAC 策略会拦截部分 C 扩展 .pyd）。
文件里允许 // 与 # 行注释，加载时统一剔除，方便人工修改时写备注。
"""
from __future__ import annotations

import hashlib
import json
import os
import re
from pathlib import Path
from typing import Any
from urllib.parse import unquote_plus, urlsplit, urlunsplit

BASE_DIR = Path(__file__).resolve().parent.parent
DEFAULT_CONFIG = BASE_DIR / "config.yaml"
DEFAULT_FEATURES = BASE_DIR / "features.yaml"


def runtime_root() -> Path:
    """取得产物根目录；部署位置只影响文件解析，不进入统计配置或模型版本哈希。

    未设置环境变量时保留原单仓库行为。生产与验收进程可注入不同的绝对目录，
    从而在使用同一份 config.yaml 的情况下隔离模型、日志和滚动断点。
    相对环境路径固定相对引擎根解析，避免工作目录改变导致产物写入意外位置。
    """
    root = Path(os.environ.get("PROB_RUNTIME_ROOT") or BASE_DIR)
    return root if root.is_absolute() else BASE_DIR / root


def resolve_runtime_path(path: str | Path) -> Path:
    """解析运行产物路径，显式绝对路径继续保持旧行为，不改写配置对象。"""
    value = Path(path)
    return value if value.is_absolute() else runtime_root() / value

# 仅用于日志、接口与持久化快照的输出边界，绝不替换运行中的连接配置。
# 数据库 URL 的用户信息以及查询参数里的独立凭据均不能进入公开任务记录。
REDACTED = "[REDACTED]"
_CREDENTIAL_KEYS = frozenset({
    "user", "username", "password", "passwd", "pwd", "secret", "token",
    "access_token", "api_key", "apikey", "client_secret",
})
_DATABASE_URL_PATTERN = re.compile(r"mysql(?:\+pymysql)?://[^\s<>\"']+", re.IGNORECASE)


def redact_database_url(value: str) -> str:
    """保留数据库地址、端口、库名和普通参数，移除连接串中的认证信息。

    使用最后一个 @ 分隔用户信息，可以同时处理百分号编码密码及包含 @ 的旧连接串。
    不在此处校验端口或建立连接，避免脱敏流程因格式异常而重新输出原始凭据。
    """
    try:
        parts = urlsplit(value)
        if parts.scheme.lower() not in {"mysql", "mysql+pymysql"} or not parts.netloc:
            return REDACTED
        endpoint = parts.netloc.rsplit("@", 1)[-1]
        query_parts = []
        for item in parts.query.split("&") if parts.query else []:
            key = item.partition("=")[0]
            if unquote_plus(key).lower() in _CREDENTIAL_KEYS:
                # 保留参数名称用于排障，但认证值统一替换；普通参数保持原有编码。
                item = key + "=" + REDACTED
            query_parts.append(item)
        return urlunsplit((parts.scheme, endpoint, parts.path, "&".join(query_parts), parts.fragment))
    except (TypeError, ValueError):
        # 格式错误的数据库配置同样可能包含密码，无法解析时采用关闭式脱敏。
        return REDACTED


def redact_text(value: str) -> str:
    """清理任意文本中的数据库连接串，适用于异常消息、堆栈和最终日志文本。"""
    return _DATABASE_URL_PATTERN.sub(lambda match: redact_database_url(match.group(0)), value)


def redacted_copy(value: Any) -> Any:
    """递归生成可公开的配置副本，不修改原对象或模型版本计算所用的参数。

    显式凭据字段和字符串中的数据库 URL 都需要清理；列表与元组保持原有容器类型，
    数值及其他对象原样返回，避免影响模型系数、日期等与认证无关的数据。
    """
    if isinstance(value, dict):
        return {key: REDACTED if str(key).lower() in _CREDENTIAL_KEYS else redacted_copy(item)
                for key, item in value.items()}
    if isinstance(value, list):
        return [redacted_copy(item) for item in value]
    if isinstance(value, tuple):
        return tuple(redacted_copy(item) for item in value)
    if isinstance(value, str):
        return redact_text(value)
    return value


def _strip_comments(text: str) -> str:
    """去掉整行注释与行尾 // 注释；字符串里的 //（如 URL）由 http:// 特判保护。"""
    out = []
    for line in text.splitlines():
        s = line.strip()
        if not s or s.startswith("#") or s.startswith("//"):
            continue
        # 去掉行尾 // 注释，但保留 http:// 之类协议串
        pos = line.find("//")
        if pos >= 0 and not line[max(0, pos - 1):pos + 2].endswith(":/"):
            if line[:pos].count('"') % 2 == 0:
                line = line[:pos]
        out.append(line)
    return "\n".join(out)


def read_json_yaml(path: str | Path) -> dict[str, Any]:
    p = Path(path)
    return json.loads(_strip_comments(p.read_text(encoding="utf-8")))


def load_config(path: str | Path | None = None) -> dict[str, Any]:
    p = Path(path or os.environ.get("PROB_CONFIG") or DEFAULT_CONFIG)
    cfg = read_json_yaml(p)
    cfg["__config_path__"] = str(p)
    # 环境变量覆盖数据库连接，方便 Docker / CI / 后端调度时注入
    if os.environ.get("PROB_DB_URL"):
        cfg["db"]["url"] = os.environ["PROB_DB_URL"]
    if os.environ.get("PROB_MAX_STOCKS"):
        cfg["engine"]["max_stocks"] = int(os.environ["PROB_MAX_STOCKS"])
    if os.environ.get("PROB_N_PATHS"):
        cfg["n_paths"] = int(os.environ["PROB_N_PATHS"])
    return cfg


def load_features(path: str | Path | None = None) -> dict[str, Any]:
    # 与 Java 的 features-path 指向同一注册表；显式入参仍具有最高优先级。
    return read_json_yaml(path or os.environ.get("PROB_FEATURES") or DEFAULT_FEATURES)


def model_version(cfg: dict[str, Any]) -> str:
    """代码版本 + 配置哈希，写入 prob_run.model_version / prob_prediction.model_version（§10.2 可复现）。"""
    payload = json.dumps({k: v for k, v in cfg.items() if not k.startswith("__")},
                         sort_keys=True, ensure_ascii=False, default=str)
    h = hashlib.sha1(payload.encode("utf-8")).hexdigest()[:10]
    return f"{cfg.get('engine_code_version', 'spe')}-{h}"


def config_snapshot(cfg: dict[str, Any]) -> str:
    """生成剔除内部字段及认证信息的运行快照，保留可复现统计计算所需参数。"""
    public_cfg = {k: v for k, v in cfg.items() if not k.startswith("__")}
    return json.dumps(redacted_copy(public_cfg),
                      ensure_ascii=False, default=str)
