# 配置与环境准备

当前统一启动和验收脚本面向 Windows 原生环境。Linux、Docker Compose、HTTPS 和公网认证处于[部署规划](remote-deployment-plan.md)阶段，仓库不包含已经交付的远端一键部署方案。

## 运行依赖

| 组件 | 当前基线 | 说明 |
| --- | --- | --- |
| PowerShell | Windows PowerShell 5.1 | 根目录 npm 命令通过 `powershell.exe` 调用脚本 |
| Java | JDK 21 | 需要 `java` 与同安装目录的 `jcmd` |
| Maven | 命令行可用 | 使用项目指定配置和独立缓存 |
| Node.js | 24 | 前端与脚本检查保持同一主版本 |
| Python | 3.10.11 | 项目虚拟环境；契约测试核对精确补丁版本 |
| MySQL | 8.0.35 | 既有独立实例，数据目录与端口需匹配配置 |

前端依赖以 `apps/web/package-lock.json` 为准，Python 依赖以 `engines/probability/requirements.lock.txt` 为准。仓库不携带 `node_modules`、虚拟环境、Maven 缓存或数据库文件。

```powershell
# 在项目根目录安装前端锁定依赖。
npm.cmd --prefix apps/web ci

# 确保 py -3.10 指向 Python 3.10.11，再创建独立虚拟环境。
py -3.10 -m venv engines/probability/.venv

# 安装精确依赖；不同系统是否具备对应包需单独验证。
.\engines\probability\.venv\Scripts\python.exe -m pip install -r engines/probability/requirements.lock.txt
```

以上命令只准备部分依赖，不会创建 MySQL 数据库、账号、业务表或行情。首次使用仍需准备独立实例及配置，并完成构建与验收。

## 平台配置

统一入口读取部署配置。未显式传入 `-ConfigPath` 时，优先使用仅本地的 `deployment/platform.local.json`，不存在时使用公共 `deployment/platform.config.json`。本地配置是完整替代文件，不是只填差异的合并配置；公共配置只作为示例，需按机器环境建立本地配置后才能运行。

相对路径均以项目根目录解析，不随命令执行目录改变。

| 字段 | 含义 |
| --- | --- |
| `dataRoot` | 独立实例的数据根，包含 MySQL 配置、数据及 Maven 缓存等 |
| `legacyRoot` | 旧工程根；仅兼容接管、模型迁移或应用回退场景使用 |
| `mysqlBase` | MySQL 安装目录，需含 `bin/mysql.exe` 等程序 |
| `databasePort` | 独立 MySQL 监听端口 |
| `profiles.*.database` | 此配置使用的数据库名 |
| `profiles.*.apiPort` / `webPort` | 后端与网页端口 |
| `profiles.*.runtimeRoot` | 进程记录、日志、模型等运行产物根 |
| `profiles.*.applicationSecretsFile` | 仅本地的应用凭据文件路径 |
| `profiles.*.javaMaxHeap` | Java 最大堆内存；它不等于整个应用的总内存上限 |
| `profiles.*.scoreScheduleEnabled` | 是否启用此配置允许的评分调度 |

`production` 和 `verification` 应使用不同数据库、账号、应用端口及运行目录。验收账号只授予验收库权限，不能共用生产凭据，也不能通过换端口启动第二个后端连接同一可写库。

## 数据库与本地秘密

启动器期望 `dataRoot/mysql/my.ini`、`dataRoot/mysql/client.ini` 以及所选配置的应用凭据文件已经存在。应用凭据 JSON 使用 `dbUser` 与 `dbPassword` 两个字段；真实内容只放在被 Git 忽略的本地位置，不复制到文档、命令行或公共示例中。

MySQL 端口、实际数据目录和库名会在启动及管理操作前核对。`Setup-Database.ps1` 是既有实例核验入口，不是新机器安装器。`Initialize-Schema.ps1` 会对明确选定的既有库执行表结构 SQL，默认选验收配置；在完成库身份、权限及备份检查前不要执行。

独立库、应用账号、视图以及行情表必须按实际需求准备。公共仓库不包含数据库快照，首次安装不能依赖原机器已有的历史数据和模型。

## 前端变量

以下变量在前端启动或构建时生效；`VITE_*` 会进入浏览器代码，不能保存密码或令牌。

| 变量 | 用途 |
| --- | --- |
| `VITE_BACKEND` | 开发代理及本地备用后端地址，默认回环地址上的 8080 端口 |
| `VITE_API_BASE` | 显式 API 基址；未来远端部署建议使用同源 `/api` 并验证备用基址行为 |
| `VITE_ENABLE_LEGACY_FORECAST` | 旧预测服务开关；未部署额外服务时设为 `false` |

可在 `apps/web/.env.local` 保存本机变量，该文件不提交 Git。修改构建变量后需重新构建，不会自动改变已生成的 `dist`。

## Java 与 Python 环境

本地启动器根据平台配置和凭据文件注入 `LOCAL_STOCK_DB_*`、API 端口及 Python 路径，并加载 `deployment/application-local.yaml`。日常使用不需要把数据库密码写入 Java 的公共配置。

| Python 变量 | 用途与边界 |
| --- | --- |
| `PROB_DB_URL` | 实际数据库连接，由进程环境传递；不要放入命令参数或公开文件 |
| `PROB_CONFIG` | 概率参数配置，与 Java 的 `config-path` 保持一致 |
| `PROB_FEATURES` | 特征注册表，与 Java 的 `features-path` 保持一致 |
| `PROB_RUNTIME_ROOT` | 相对模型、日志和断点路径的根目录；绝对路径保持原语义 |
| `PROB_MAX_STOCKS` | 引擎股票数限制；设为调试值后不再代表无限量 |
| `PROB_N_PATHS` | 模拟路径数；修改会改变统计计算配置 |

`config.yaml` 与 `features.yaml` 使用 JSON 兼容的 YAML 内容和项目支持的注释形式，不能随意改成需要额外 YAML 解析器的格式。CLI 显式 `--config` 优先于环境变量，且必须放在子命令之前。

当前模型版本哈希包含有效配置，连接 URL 也在其中：更换数据库地址或凭据可能改变版本，不能据此认定算法发生变化，也不能直接改写历史结果。`PROB_RUNTIME_ROOT` 不进入该哈希；完整迁移仍需处理模型路径与榜单版本选择。

## 配置完成后的检查

```powershell
# 只核对依赖和文件，不自动安装，不启动模型任务。
npm.cmd run check

# 在依赖缓存准备完整后执行离线验收。
npm.cmd test
```

继续构建和启动前，按[测试与验收](testing.md)确认运行中任务与产物占用状态。个人服务器地址、运维记录、截图和真实配置放在本地文档目录，不加入 Git。
