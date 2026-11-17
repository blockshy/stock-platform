# 股票概率计算引擎

本模块读取已入库的股票历史行情，执行数据准备、训练、样本外评估、概率预测、结算和回测。Java 后端以子进程调用 `python -m engine.cli`，网页通过后端查看结果和任务状态。

日常使用优先走[平台使用指南](../../docs/user-guide.md)中的“概率预测 → 任务与参数”，避免与网页任务重复运行。引擎不负责下载行情，当前股票池也不包含 58 ETF。

## 环境与运行前提

当前等效验收基线为 Python 3.10.11，依赖版本记录在 `requirements.lock.txt`。创建项目虚拟环境并按锁文件安装依赖；不能复制其他机器或操作系统的 `.venv`。完整步骤见[配置说明](../../docs/configuration.md)。

实际执行前需要准备数据库结构、股票历史数据、数据库连接与独立运行目录。模型文件、断点和凭据不随 Git 分发。Java 与手工 CLI 必须指向同一份参数及特征配置，测试实例则应使用独立数据和产物目录。

## 命令参考

在本目录使用 `.venv/Scripts/python.exe -m engine.cli <子命令>`。以下表格用于解释功能，写入命令应在确认目标库及任务空闲后执行。

| 子命令 | 作用 |
| --- | --- |
| `prepare` | 读取窗口、检查数据完整性、确定股票池并记录运行 |
| `walkforward` | 生成滚动样本外预测，支持匹配身份的断点续跑 |
| `train` | 训练合并模型并登记模型产物 |
| `predict` | 生成当前合格股票池的 LIVE 预测 |
| `settle` | 为到期预测补充真实结果和命中标记 |
| `evaluate` | 计算指标、校准、方法选择和可靠性 |
| `backtest` | 使用已有数据执行概率策略回测 |
| `daily` | 编排准备、样本外评估、训练、预测、结算、评估和回测 |
| `status` | 输出表计数、最新日期与运行记录；需要数据库环境 |
| `config` | 输出脱敏的生效配置及模型版本 |

```powershell
# 查看 CLI 参数；此命令不会启动采集或计算。
.\.venv\Scripts\python.exe -X utf8 -m engine.cli --help

# 显式配置位于子命令之前；config 输出经过脱敏。
.\.venv\Scripts\python.exe -X utf8 -m engine.cli --config config.yaml config

# 执行本模块单元测试；平台完整验收另从项目根目录运行 npm test。
.\.venv\Scripts\python.exe -X utf8 -m unittest discover -s tests -v
```

通用参数包括 `--asof YYYY-MM-DD|latest`、`--limit-stocks N`、`--run-id N`、`--fast`、`--force` 和 `--no-resume`。`--run-id` 用于复用后端创建的运行记录，手工任务不要随意指定其他任务编号。数量限制只用于受控调试；快速模式会采用不同计算路径，不应作为保持结果不变的通用优化。

## 配置与可复现边界

参数在 `config.yaml`，特征注册表在 `features.yaml`。读取的是项目支持的 JSON 兼容内容与注释形式。

- `PROB_DB_URL` 注入实际连接，凭据只在本地或进程环境保存。
- `PROB_CONFIG`、`PROB_FEATURES` 分别选择参数和特征文件。
- `PROB_RUNTIME_ROOT` 决定相对模型、日志、断点路径，不改变配置哈希；已有绝对路径保持原样。
- `PROB_MAX_STOCKS`、`PROB_N_PATHS` 会影响计算范围或参数，修改后应重新验收。

模型版本由代码标识和有效配置生成。当前连接 URL 也参与配置哈希，因此换库地址或账号会产生不同版本；迁移时要保留历史版本，检查断点身份及榜单选择，而不是改写已有预测来消除差异。

固定 `--asof` 不是完整的数据快照。复现实验还要固定源数据、已结算 OOS、配置、依赖和种子；跨系统的数值差异按[测试约定](../../docs/testing.md)验收。

## 模块与数据

| 路径 | 职责 |
| --- | --- |
| `engine/config.py` | 配置、版本与脱敏 |
| `engine/data.py` / `features.py` / `labels.py` | 数据、特征与标签 |
| `engine/formulas.py` / `models/` | 数学公式及模型 |
| `engine/predictor.py` / `pipeline.py` | 预测与任务编排 |
| `engine/store.py` / `evaluation.py` / `backtest.py` | 持久化、评估与回测 |
| `engine/schema.sql` | 与 Java 一致的概率表结构 |
| `tests/` | 单元测试；平台额外契约位于根目录 `tests/` |

概率结果区分 LIVE、OOS 和相应状态。已排除的 OOS 记录应保留审计状态，不能混入已结算训练样本。备份必须同时覆盖数据库、模型文件与元数据；仅备份行情表不足以恢复已有模型行为。

输出是基于历史数据的统计估计。解释结果时应保留基准日、预测周期、版本、样本覆盖与风险指标。
