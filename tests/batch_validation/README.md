# 同源批量行情的离线等效验收

本目录只比较已经保存的本地 CSV/JSON。工具没有网络、数据库、进程启动或模型训练入口；不能把单元测试通过解释成 BaoStock 批量接口、复权公式或全市场模型结果已验证。

## 分层门槛

| 层级 | 必须固定和比较的内容 | 未完成时的结论 |
| --- | --- | --- |
| 原始字段 | 同一基准日下的日期、代码、市场、复权标记、OHLC、昨收、成交量/额、换手、涨跌幅、交易状态、ST、四类估值；空值与零不同 | 不允许切换采集入口 |
| 集合与分页 | 独立核对的期望业务键、逐页数量/首尾键、重复页、漏页、股票与 ETF 分离；首尾日期和总条数相同不足以证明完整 | `INCOMPLETE` 或 `FAIL` |
| 因子与复权 | 窗口起点有效因子、窗口内事件、固定末端基准、除权前/当天/次日；前复权和后复权均与逐股接口完整历史对照 | 不计算猜测公式，不默认因子为 1 |
| Java 与落库 | 独立比较原始值、预入库 VO、实际 DECIMAL 精度，不以数据库四舍五入掩盖源差异 | 分层报告，不合并成一个“相同” |
| 既有结果 | 冻结快照里评分/短期/概率/GA/策略/做 T 的完整输出与离散状态 | 只能说明已有结果未变 |
| 同输入重新计算 | 原始输入 A/B、相同历史 OOS/模型/配置/种子/排序，在隔离环境运行实际算法 | 未运行的模块必须 `NOT_RUN` |

`compare_daily` 只在两侧行键与独立 `expected_keys` 完全相同且字段全部相同后给出 `PASS`。两侧都漏一行的情况会被期望键检测；没有期望键时最多为 `INCOMPLETE`。期望键不能直接从候选批量返回结果生成。样本证券采用逐股独立查询和交易日/上市信息交叉核对；不能简单以全市场交易日与所有证券做笛卡尔积，也不能把晚起始日自动解释为新股。

原始行采用 Decimal 精确比较，数值 `10.0` 与 `10.000` 视为相同十进制值。缺字段、非法数值、NaN、重复键、证券范围错误、非法复权标记均拒绝。字符串代码必须保留前导零。非交易日只有显式空期望键与 `allowEmpty` 同时提供才允许零行通过。

## 可复用函数

`compare.py` 提供以下纯函数，Java 探针导出的 JSON 可通过 `load_snapshot` 读取后传入：

- `canonical_daily_row(row, representation="vendor", code=None, market=None, asset="stock")`：供应方字段映射为统一键。`representation` 也支持 `java`（StockDailyVO 字段）和 `database`（SQL snake_case 字段）。逐股响应没有 `code` 时必须显式传入本次请求的代码与市场；不能猜股票代码。ETF 用 `asset="etf"`。
- `compare_daily(reference, candidate, expected_keys=None, allow_empty=False)`：输入为 canonical 行列表，逐键逐字段比较。默认不忽略额外字段。
- `java_projection(row)`：按现有 Java 顺序计算 `(high-low)/preclose`，先 HALF_UP 到八位再乘 100；涨跌额为 `close-preclose`。不做复权。
- `storage_projection(row)`：显式投影现有 Schema 的价格四位、金额两位、换手/涨跌幅/振幅四位和估值六位。应与真正隔离库回读结果另行比较；此投影不能替代真实 MySQL 舍入与溢出检查。
- `audit_factor_coverage(factors, price_keys, history_start=..., asof=...)`：检查每只证券的期初正因子、事件日期和重复事件。输入字段为 `code/market/effective_date/forward_factor/backward_factor`，需要采集适配层明确映射。报告的 `priceEquivalence` 始终是 `NOT_CHECKED`，因子齐全不等于价格公式已验证。
- `compare_values(reference, candidate, numeric_paths=(), tolerance="exact", metadata_mappings=None)`：比较完整 JSON，保留数组顺序、字段集合与空值。调用方须按已冻结的稳定业务排序导出，不能自动重排掩盖榜单次序变化。

结果数值默认精确比较。只有概率明确数值路径可选择 `probability-memory`（绝对 `1e-8`、相对 `1e-7`）或 `probability-persisted`（绝对 `1e-6`）。字段路径例如 `$.data.items[].p_up`；股票集合、周期、计数、标签、风险/可靠性等级及交易决策使用精确比较，不得加入容差路径。门槛来自 `contracts/probability-engine.json`，没有为其他模块新增宽松容差。

元数据不提供“忽略任意字段”开关。必须指定允许的任务 ID、时戳、产物路径或模型版本的路径、两侧精确期望值与原因；映射未命中、值不符都不能通过。基准日、交易日期、股票代码、状态与参数不可映射。例如：

```python
mapping = {"$.runId": {"reference": 101, "candidate": 202, "reason": "同一输入的隔离重放任务身份"}}
report = compare_values(old_result, new_result, metadata_mappings=mapping)
```

## 执行命令与计划格式

从仓库根运行合成反例测试，不需要联网：

```powershell
engines\probability\.venv\Scripts\python.exe -m unittest discover -s tests/batch_validation -p "test_*.py" -v
```

真实快照、计划和报告放在 `.local`，不提交公共仓库。JSON 日线文件使用行数组，CSV 保留原始字段名称。下面计划中的路径相对计划所在目录；所有示例证券/任务编号只是格式演示。

```json
{
  "checks": [
    {
      "name": "raw-stock-sample",
      "kind": "daily",
      "stage": "raw-fields-and-independent-keys",
      "reference": "per-stock-raw.json",
      "candidate": "batch-raw.json",
      "referenceOptions": {"representation": "vendor", "asset": "stock"},
      "candidateOptions": {"representation": "vendor", "asset": "stock"},
      "expectedKeys": "expected-keys.json"
    },
    {
      "name": "java-projection",
      "kind": "daily",
      "stage": "pre-storage-vo",
      "reference": "per-stock-raw.json",
      "candidate": "batch-java-vo.json",
      "referenceOptions": {"representation": "vendor", "asset": "stock"},
      "candidateOptions": {"representation": "java", "asset": "stock"},
      "referenceProjections": ["java"],
      "expectedKeys": "expected-keys.json"
    },
    {
      "name": "stored-score-result",
      "kind": "result",
      "stage": "stored-results-only",
      "reference": "score-reference.json",
      "candidate": "score-candidate.json"
    },
    {"name": "recomputed-probability", "deferredReason": "尚未准备冻结模型与隔离计算输出"},
    {"name": "ga-trained-prediction", "deferredReason": "若无既有冠军模型，不运行新训练，不把空结果当作通过"}
  ]
}
```

期望键示例：`[{"code":"000001","market":"SZ","trade_date":"2026-01-05","adjustflag":3}]`。真正验收需覆盖全部选定证券与日期，示例单行不能证明全市场覆盖。前复权/后复权候选计算后另存独立文件，以真实标记 2/1 做相同逐键比较，禁止将原始行改标为 2 来获得通过。

```powershell
engines\probability\.venv\Scripts\python.exe tests\batch_validation\compare.py --plan .local\batch-validation\plan.json --output .local\batch-validation\report.json
```

CLI 仅允许报告写入本仓库 `.local`。`PASS` 退出码为 0；`FAIL/INCOMPLETE/INVALID_INPUT` 为 1。报告记录输入 SHA-256、分层状态和差异路径，最多展示 50 个差异样本但差异总数不截断。含 `deferredReason` 的检查为 `NOT_RUN`，计划整体不会因此显示全部通过。`recomputedByThisTool` 永远为 false。

## 独立采样程序的快照适配

`evaluate_probe.py` 直接读取 `BaoStockBatchProbe` 的 `capture-report.json` 及同目录请求快照：

```powershell
engines\probability\.venv\Scripts\python.exe tests\batch_validation\evaluate_probe.py --capture .local\runtime\verification\batch-probe\capture-report.json --output .local\runtime\verification\batch-probe\source-evaluation.json
```

它会重新计算请求身份 SHA-256 和行数组摘要，核对请求字段、实际日期与复权标记、逐日证券列表与批量证券集合，再分别比较代表股票和 58 ETF 的不复权行。额外返回的 ETF `isST` 只作为供应方附加列记录数量：既有逐股 ETF 查询没有该列，Java 也不保存它，不将它伪装成个股 ST 等效比较。该可选列允许 `0`、`1` 或空字符串；空字符串另记 `etfOptionalSourceIsSTEmpty`，不补零、不转成个股 ST。没有独立 ETF 列表证据时，ETF 全集完整性仍待验。

实际 ETF 批量还可能返回 `peTTM/pbMRQ/psTTM/pcfNcfTTM` 四个附加列。离线适配只接受四列同时存在且全部为空的已观测结构，逐列计数后排除 ETF 业务映射；`isST` 也记录源值分布，不推断基金风险标签。任何新增非空估值或部分列结构均拒绝并要求重新审查，不能静默丢值或伪造股票估值。原始快照始终保留这些附带字段。

因子同时按观察日和本轮 `adjustedCaptureId` 核对，不把行情截止日与复权锚点观察日混为一谈；报告只核对期初正因子和代表日期的事件是否一致，不生成前复权/后复权价格。观察日不代表可信的上游复权版本，同一天的基准也可能更新。`baostock-batch-v2` 的前后复权及因子快照身份包含每次执行新生成的 UUID；即使文件摘要正确，只要不属于报告的本轮 UUID 就拒绝。每次重启均须重新取得这些响应，只有原始数据缓存经完整性和身份校验后可跨执行复用，跨观察日复用原始数据另行记录。

日历采用严格分页查询返回的完整自然日记录 `date/is_trading_day`：闭区间每一个自然日必须唯一且完整，只有明确的 `1` 加入交易日集合，`0` 表示休市。超过 2000 条的完整窗口正常验证；缺行、重复休市日、未知状态及旧的仅交易日格式都拒绝，不能以缺行推测休市。

探针可在源调用前依据完整日历跳过休市日，并记录 `skippedDates:[{"date":"YYYY-MM-DD","reason":"CALENDAR_NON_TRADING"}]`。离线适配仅接受计划内、日历明确为 `0`、不重复且没有任何同日成功名单/日线/因子请求的声明，独立报告 `SKIPPED_NON_TRADING`；不会生成或宣称成功空快照。交易日被跳过、缺完整日历、声明与已有请求冲突都拒绝。缺少明确声明的旧报告仍记录缺请求，不能自动把失败响应改称休市。

此入口的最高状态始终为 `PARTIAL`，退出码 0 只表示已完成本次有限取样证据整理；`FAILED/INVALID_INPUT` 为 1。`modelParity=NOT_RUN`、`adjustmentFormula=NOT_VALIDATED`、`cutoverAllowed=false` 不会自动改变。缺请求快照、没有有效行情、无初始因子等情况留在 `issues/checks`，不联网补齐。方法耗时与缓存原耗时分别列出，因样本范围和等待口径不同，不计算提速倍数。

整日批量请求未取得与成功响应里缺少证券分别处理：前者仅比较已有日期，将其余参考行计入 `deferredReferenceRows`，保持 `INCOMPLETE/NOT_RUN`；后者仍由独立名单判为 `FAIL`。尚无逐股参考请求时，现有批量行记为 `deferredCandidateRows`，不伪报成多余行情。每日或逐股因子请求缺失时，不能因两侧事件数组都为空就宣称通过，必须保留 `NOT_RUN/INCOMPLETE` 及缺失日期、证券。

每日因子报告分别列出源事件总数、代表证券参考事件数、实际比较事件数和代表证券范围外事件数。如果采样日的事件全部落在代表证券之外，双方零事件一致只能说明这些代表证券当日无事件；不能把范围外源事件也称为已逐股验证。

## 复权公式候选的离线诊断

`reconstruct_adjusted.py` 用同一轮探针保存的原始行情、前后复权参考和因子诊断候选公式，没有联网补齐或生产写入能力。需要等采样停止写文件后执行：

```powershell
engines\probability\.venv\Scripts\python.exe tests\batch_validation\reconstruct_adjusted.py --capture .local\runtime\verification\batch-probe\capture-report.json --output .local\runtime\verification\batch-probe\adjustment-diagnostics.json
```

候选依据是已保存的[官方本地复权教程](https://baostock.com/mainContent?file=localdatafactorInfo.md)和[因子说明](https://baostock.com/mainContent?file=factorInfo.md)：取小于等于行情日期的最近事件因子，乘 OHLC 与昨收，保留成交量、成交额。**这只是需要用逐股接口证实的候选，并非本项目已接受的公式。** 工具不会照搬教程的缺因子填 1，也不会静默将因子先截到六位；缺少窗口前有效因子就记录缺口。当前工具没有证明接口空因子可安全视为 1 的规则。

它按预先固定的五个候选分别报告：完整 Decimal 精度乘法；仅最终价格六位 HALF_UP；仅最终价格六位 HALF_EVEN；因子与价格均六位 HALF_EVEN；昨收使用上一交易日有效因子的诊断候选。HALF_EVEN 候选不冒充 pandas 二进制浮点的全部舍入行为。工具不搜索最有利容差，不自动选择最吻合的公式，不改变 `adjustflag` 的含义。前复权使用 `foreAdjustFactor`，后复权使用 `backAdjustFactor`，`adjustFactor` 保留在严格因子输入中但不猜测其递推作用。

输出分开记录两条证据路径：逐股不复权完整历史用于检查公式；批量代表日用于检查真实候选来源。后一条的期望键只能来自完整自然日历与独立逐日证券列表；前一条没有逐日独立完整键时保持完整性待验。原始来源已失败时，整体诊断为 `FAILED`。58 ETF 不进入股票复权公式，其原始字段继续由 `evaluate_probe.py` 独立验收。

每种候选保留以下门槛：

- OHLC、昨收分别统计精确差异、最大绝对误差、源空值及实际因子生效日；不把空值补零。`volume/amount` 原样保留，但必须与逐股复权接口逐值核对，不能假设已相同。
- 已实测且被严格客户端接受的停牌例外单独记录为 `matchedSourceNulls`：原始与目标双方 `tradestatus=0`，且 `volume/amount` 同时为空并精确一致。该例外不阻断样本数值匹配；正常交易双空、停牌单空、任何关键价格空值仍为 `INCOMPLETE`。所有源空值原样保留，不填零。
- 单独统计“源级不同，但 DECIMAL 落库投影后相同”的数量。这个统计只定位精度掩盖，原始差异仍然失败；投影不能代替真实 MySQL 回读。
- 每个窗口内除权事件必须覆盖前一交易日、生效交易日、后一交易日的双侧行情。邻日从完整交易日历确定，不能以相邻已返回记录替代。没有事件为 `NOT_EXERCISED`，边界邻日缺失为 `INCOMPLETE`，窗口外邻日须另扩展取样。
- 空因子、缺期初因子、重复事件、未来事件、错误复权标记、ETF 混入、两侧同时缺行及跨执行批次复权快照均不能成为通过证据。

候选 `MATCHED_SAMPLE` 只表示已提供的行在该假设下匹配，不意味着独立完整性或除权边界已经通过。全局最高状态仍为 `PARTIAL`，并固定 `selectedFormula=null`、`adjustmentFormula=NOT_ACCEPTED`、`modelParity=NOT_RUN`、`cutoverAllowed=false`。无有效行情/因子快照时为 `NOT_RUN`；`PARTIAL` 退出码 0 只表示诊断已完成，其他状态为 1。合成反例测试只能证明工具发现错误的能力，不能证明真实复权等效或任一模型已重新运行。

## 隔离快照与实际执行顺序

以下是部署负责人执行的步骤，本目录工具不会自动执行：

1. 等待当前采集自然结束，确认所有写任务和自动调度空闲。BaoStock 只允许一条受控连接，探针与生产采集不能并行。再记录同一供应方基准日、窗口、字段列表、SDK/Java 版本和逐页首尾键；先做代表日期与除权/ST/停牌/新股/ETF/非交易日样本，后扩大范围。
2. 复用 `deployment/Platform.Operations.ps1` 的 `Backup-Platform` 一致性备份模式：`mysqldump --single-transaction`，连同配置、完整历史 OOS/EXCLUDED、任务和模型登记、模型文件及断点一起冻结。备份包含凭据，只保存在本地；复制活动 InnoDB 文件不能替代一致性快照。对模型清单逐文件记录 SHA-256，保留数据库 artifact_path 到隔离文件的明确映射。
3. 沿用现有 `stock_platform_verify` 隔离库与专用账户、独立 API 18080/网页 15173、独立 runtime。现有 `verify_probability_database.py` 会拒绝其他库名/端口，不修改它绕过守卫。可在同一验证库**顺序**执行 A 与 B：每轮从同一完整只读备份恢复，再分别导入参考/候选行情，避免两个服务共享结果表。只有数据来源不同，业务代码/配置/OOS/模型不变。
4. 启动验证服务之前确认所有自动采集、评分启动/定时、GA 日链路/周进化、概率调度均关闭；仅 `verification` 名称或不同后端端口不证明隔离。检查视图没有生产库限定引用、账户没有生产写权限、模型输出/日志/断点不指向生产路径。配置显式绝对产物路径不会被 `PROB_RUNTIME_ROOT` 自动改写，必须核对。
5. 先读取已存结果，复用 `verify_readonly_api_parity.py` 的完整分页与包装时戳处理思路。但该旧脚本固定日期、端口和部分结果接口，不能未经核对直接运行或声称覆盖本次全部模块。导出结果保持业务字段、完整分页与稳定排序，任务 ID/时戳/路径/连接导致的版本差异用明确映射处理。
6. 再在隔离环境执行获授权的同输入计算，将 A/B 结果交给本工具。概率 CLI `predict` 内部调用 `train_fusion_from_oos` 和 `_train_all`，`daily` 也会训练，**它们不是“只加载现有模型”的验收入口**。禁新训练阶段只能验证现有结果、已有离线特征/标签/FHS 分支，或另行实现冻结 PooledModels 的专用推理驱动；不能直接运行 CLI 后宣称未训练。

## 各模块结果出口与边界

| 模块 | 可导出的结果/已存接口 | 同输入重算时必须检查 |
| --- | --- | --- |
| 评分 | `stock_score_daily`；`GET /api/rankings/scores` | 全市场资格集合、各分项、总分、排名及排序；横截面分位不能只用几只股票代替全市场 |
| 短期预测 | `stock_forecast_daily/run/prediction`；`GET /api/short-term/forecasts`、history 各入口 | 批次、股票/日期/周期键、概率/收益/置信度、实际回填及误差；关联 run_id 明确映射 |
| 概率 | 11 张 `prob_*` 表；predictions、stocks、eval、models 的 GET | 原始特征、有效历史资格、1/3/5/10 周期、预测分位/风险/等级、OOS SETTLED/EXCLUDED、固定种子和已有模型文件摘要 |
| GA | `stock_feature_daily`、`ga_prediction`、`ga_stock_pool`、冠军/个体/指标表；`/api/ga/predictions`、pool、stock、factors | 特征归一化、标签、入池/淘汰、冠军与影子预测；无训练结果时仅能验证实际可用分支 |
| 量化策略 | `quant_backtest_*`、`quant_event_study`、`quant_signal_daily`；对应 GET | 固定行情快照/策略参数、逐笔成交顺序、费用、净值、分段指标与今日信号 |
| 做 T 模型 | 现有 `/api/t-trading/simulate` 返回完整结果（该服务为内存回放） | 初始持仓/资金/日期/复权/费用相同，比较逐日资产、成交与下一步建议 |
| 做 T 模型 2 | `/api/t/tasks/{id}`；隔离的 simulate/scan-entry/next-plan 输出 | 此模块会创建任务并保存结果，不能把这些 POST 当作只读；比较日线、交易、扫描、计划、警告和基准 |

可复用的离线测试包括 `tests/probability_fixture.py`、`ScoreCalculationServicePercentileTest`、`GaCoreAcceptanceTest`、`QuantMathTest`、`TLabEngineTest`。它们验证各自算法边界，不能替代本次真实行情输入的完整计算对照。任何未采集日期、缺失因子、缺失模型或未运行模块都单列待验，不自动训练或放宽容差来得到通过。
