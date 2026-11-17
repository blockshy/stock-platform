# 概率引擎迁移等效验收

本次基线是迁移前本机已经修复的三个仓库，不是 GitHub 原始版本。整合允许部署路径适配，
不允许顺带变更概率公式、股票资格、训练规模、模拟路径或统计参数。验收分为离线回归、
Java 进程契约和隔离数据库三层；任一未完成的层级必须标记待验，不能用单元测试替代完整训练证明。

## 已固定的契约

- `contracts/algorithm-baseline.json` 记录 60 个文件摘要。其中 56 个算法、参数和 Schema 文件
  与旧目录逐字节相同，4 个文件仅允许已经审查的运行路径适配，并固定适配后摘要。
- `contracts/probability-engine.json` 记录 CLI、状态、数据源、环境变量和数值容差。
- `contracts/probability-numeric-baseline.json` 由旧引擎代码读取固定合成行情得到，不访问数据库。
  重新生成基线必须单独审查，普通测试不会更新期望值。
- 两份概率 Schema 必须逐字节一致，保留 11 张 `prob_*` 表及预测复合主键。
- `config.yaml` 和 `features.yaml` 保留原内容。关键参数仍为 500 日窗口、250 个有效历史日、
  1/3/5/10 日周期、OOS 300 只及 40 日/步长 5、LIVE 10000 条路径、种子 20260101。
- Python 3.10.11 与 `requirements.lock.txt` 的全部精确版本纳入测试，不能只满足宽版本范围。

## 部署位置与版本兼容

Java 继续以子进程调用 `python -m engine.cli <command> --run-id ...`，Python 包名仍为 `engine`。
含空格的解释器及工作目录必须作为独立参数传递，不能拼接成 shell 命令。

`PROB_CONFIG` 与 Java `config-path` 指向同一文件；`PROB_FEATURES` 与 Java `features-path`
指向同一特征注册表。CLI 显式 `--config` 优先，且位于子命令之前。实际数据库连接只通过
`PROB_DB_URL` 注入，公开快照、日志、命令参数与测试报告不得包含凭据。

新增 `PROB_RUNTIME_ROOT` 只影响相对 artifacts、logs 和 checkpoint 的文件解析，不加入或修改
配置字典，因此仅迁移运行根不会改变 `model_version`。未设置时仍使用引擎根目录；配置中的显式
绝对路径保持原行为。生产、验证启动器应检查解析后的目录属于各自运行根。

现有版本哈希仍包含数据库 URL。因此生产保持同一连接和配置时版本不变；隔离验证库使用不同库名
或用户时版本改变是预期的部署元数据差异。数值比较须明确映射两侧版本，不能把其他统计参数差异
一并忽略。本次没有重构版本算法，也没有改变 Java 选择历史版本的既有规则。

旧 pickle 中的模块路径仍可解析，但 `.venv` 必须按锁文件重建。旧模型文件、JSON 元数据与
`prob_model_registry.artifact_path` 必须一起保留或建立经核对的路径映射。不能因为文件搬到新目录，
就假定数据库中的绝对路径自动生效。生产与验证不能共享模型输出或断点目录。

## 离线验收命令与门槛

在仓库根使用新环境运行：

```powershell
engines\probability\.venv\Scripts\python.exe tests\run_probability_acceptance.py
```

该入口依次运行原引擎 42 个测试和根目录迁移契约测试，报告保存到
`.local/runtime/verification/reports/probability-offline.json`。执行过程不连接数据库、不启动服务。

必须满足：

1. 原引擎 42 个测试全部通过，保留公式性质、脱敏、断点身份、历史资格、失败统计、进度及归属保障。
2. 迁移契约测试全部通过，算法摘要/完整参数、精确依赖和 Schema 均匹配。
3. 固定样本股票集合、ETF 排除、249/250 日边界、计数、标签、种子与旧基线完全一致。
4. 浮点样本满足绝对容差 `1e-8`、相对容差 `1e-7`；概率、收益、分位数、风险及特征探针均比较。
5. EXCLUDED 历史 OOS 不进入融合、评估和回测；三个入口仍只消费 SETTLED。

合成样本覆盖实际清洗、特征、OOS 解析预测和固定种子 FHS 核心，但没有训练 M1/M2/M5/M6/M10。
短历史样本前段为空时出现的 NumPy 空窗口警告属于明确测试边界；结果必须保持有限且比较通过。

## Java CLI 契约

`ProbTaskServiceContractTest` 用 Mockito 构造器拦截替换 ProcessBuilder，进程、数据库、结果仓库
也全部使用 mock，不创建 Spring 上下文，不会真实启动 Python。统一 Maven 验证应包含：

```powershell
cd apps\api
mvn -Dtest=ProbTaskServiceContractTest test
```

验证真实 Java 执行方法的日期、任务 ID、全市场限制、force/noResume/fast 参数、UTF-8、工作目录，
以及退出码失败处理。Python 已写 FAILED 时，Java 不能因进程退出码 0 把失败覆盖为成功。

## 隔离数据库与完整链路验收

隔离库固定为本机 `127.0.0.1:13306/stock_platform_verify`，使用只获授权该库的独立账户。
快照包括行情、股票信息、历史 OOS、任务、指标、EXCLUDED 审计状态及相关模型注册；不能仅复制行情。
视图不得残留指向 `stock_eastmoney` 的限定引用。

部署脚本注入验证环境后，可执行只读检查：

```powershell
engines\probability\.venv\Scripts\python.exe tests\verify_probability_database.py
```

入口在连接前拒绝生产库、远程主机或非 13306 端口，连接后再次核验数据库身份；不构造会自动建表的
Pipeline，只执行 SELECT。输出仅包含表数量、股票/ETF 隔离与预测状态汇总，不包含配置快照。

真实服务与完整模型运行由部署负责人统一调度，不由离线测试自动触发。完整验收需要两份相同输入状态，
固定基准日、依赖、配置、历史 OOS 与排序口径。先测量旧环境自身重复运行差异，再比较迁移结果；
不得因验证失败自动扩大容差。持久化预测列多数为 6 位小数，建议以 `1e-6` 为最大绝对初始比较界限，
离散状态、股票集合与周期数量仍必须完全一致，临界分类差异必须逐项解释。

需要排除或明确映射的仅是任务 ID、时间戳、文件路径及由测试连接产生的模型版本元数据。历史 OOS
是融合训练输入，不能忽略；SQL 部分查询没有完整稳定排序，BLAS/优化器也可能存在数值差异，因而不承诺
跨平台逐位一致。`date.today()` 产生的评估日期必须记录并与数值差异分开审查。

切换前保留旧代码、数据库备份、模型文件与断点。新旧实例不得同时写同一数据库或产物目录。
任一硬门槛失败则停止切换，使用原启动入口恢复；不能通过重置统计参数或删除历史记录让验收“通过”。

## 验收记录与公开边界

本文件描述可复用的验收规则，不包含某台机器的生产数量、任务编号、进程、截图或现场操作记录。
每次运行的真实结果保存在被 Git 忽略的 `.local/runtime/verification/reports/`。
发布说明必须区分离线检查、隔离数据库验证和完整训练；不能把历史通过记录当作当前提交的验收结果。

统一入口、隔离链路与浏览器验证步骤见 [测试说明](testing.md)。
