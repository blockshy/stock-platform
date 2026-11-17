# 股票研究平台

将行情采集、量化评分、短期预测、概率预测及策略研究整合到同一个网页和源码工程中。项目由 React 前端、Java API 和 Python 概率引擎组成，使用独立 MySQL 数据库保存行情与计算结果。

**当前可用的部署方式是 Windows 原生运行。远端 Linux / Docker 部署仍处于规划阶段，因资源限制暂缓实施。** 仓库不包含现成行情、训练模型、数据库备份或机器凭据；克隆代码后需要先准备依赖和独立数据库。

## 功能与边界

| 模块 | 功能 | 使用前提与边界 |
| --- | --- | --- |
| 行情与证券 | 股票列表、详情、K 线、行情采集和任务进度 | 默认支持 00、30、60 前缀股票；历史可用范围以实际入库数据为准 |
| 58 ETF | 采集以 58 开头的 ETF 日线 | 独立存储，成交量单位为“份”；不自动进入个股评分与预测 |
| 量化评分 | 评分榜单、指标与连续性分析 | 行情准备好后执行评分；导入行情不会自动保证榜单已更新 |
| 短期预测 | 逐日预测、历史批次与结果查看 | 与概率引擎属于不同模块，需分别生成结果 |
| 概率预测 | 1 / 3 / 5 / 10 日周期、榜单、个股、评估和任务管理 | “全市场预测”仍受有效历史与模型资格限制，不保证每只证券都有结果 |
| 策略研究 | 策略回测、遗传算法及做 T 分析 | 需相应行情与计算结果；空结果不等于任务故障 |

`stock-fetch` 未纳入工程；旧预测页面依赖的额外 `stock-predict` 服务也未部署。前端构建时应设置 `VITE_ENABLE_LEGACY_FORECAST=false`，参见[配置说明](docs/configuration.md)。

## 环境与目录

当前验收基线：Windows PowerShell 5.1、Node.js 24、JDK 21、Maven、Python 3.10.11、MySQL 8.0.35。前端依赖以 `apps/web/package-lock.json` 为准，Python 精确依赖以 `engines/probability/requirements.lock.txt` 为准。其他版本不能直接视为已通过等效性验证。

```text
stock-platform/
  apps/web/             网页与统一 UI 组件
  apps/api/             Java API、采集、评分和策略计算
  engines/probability/  Python 概率引擎
  contracts/            受保护的算法、参数和数值契约
  deployment/           Windows 进程管理、配置、备份与回退
  scripts/              统一命令入口
  tests/                离线及隔离环境验证
  docs/                 公开使用、配置、测试和规划文档
  .local/               仅本地的运行产物、报告与私有资料（Git 忽略）
```

## 开始使用

先阅读[安装与配置](docs/configuration.md)，按其中的前置清单准备数据库配置、应用账号、Python 虚拟环境和依赖缓存。当前脚本管理的是**已准备好的独立实例**，不会替新机器自动安装 MySQL、初始化数据目录或创建全部账号。

在仓库根目录执行：

```powershell
# 第一次配置时复制通用模板，并在本地文件中填写实际安装路径。
# 如本地配置已存在，请直接编辑，不能覆盖已有实例的配置。
if (!(Test-Path deployment/platform.local.json)) {
    Copy-Item deployment/platform.config.json deployment/platform.local.json
}

# 完成依赖及数据库准备后，先检查环境，再构建和启动。
npm.cmd run check
npm.cmd run build
npm.cmd start

# 查看状态；停止命令会拒绝中断仍在运行的受管任务。
npm.cmd run status
npm.cmd stop
```

默认网页为 `http://127.0.0.1:5173`，API 为 `http://127.0.0.1:8080`。端口和运行目录可通过本地配置调整。当前本地启动入口不适合作为公网发布入口，远端方案见[部署规划](docs/remote-deployment-plan.md)。

通常按“行情采集 → 评分 / 短期预测 → 概率任务 → 查看结果”的顺序操作。概率模块中的“一键运行日常任务”与“全市场预测”不是同一个命令；采集成功也不代表所有计算已完成。具体操作与任务进度解释见[用户手册](docs/user-guide.md)。

## 测试与功能保持

```powershell
# 完整离线检查：不启动服务，不触发真实采集或训练。
# Java 使用离线依赖缓存，因此应先完成安装准备。
npm.cmd test

# 按改动范围单独运行前端和部署脚本检查。
npm.cmd run test:web
npm.cmd run check:ui
npm.cmd run test:deployment
```

数据库、真实 Java → Python 调用和浏览器验证不属于全部离线检查的覆盖范围，须按[测试说明](docs/testing.md)使用隔离环境执行。生产与验收实例必须使用不同数据库、账号、应用端口、运行目录；仅更换端口不能构成隔离。

评分、预测参数、复权、成交量单位、ETF 隔离、页面状态和任务状态都属于兼容边界。规则见[功能等效性](docs/functional-parity.md)和[前端规范](docs/frontend-guidelines.md)。

## 文档与资料管理

- [文档导航](docs/README.md)：按使用场景选择说明。
- [安装与配置](docs/configuration.md)：依赖、配置优先级和私有文件。
- [用户手册](docs/user-guide.md)：采集、评分、预测与策略操作。
- [测试说明](docs/testing.md)：离线、隔离数据库和浏览器验证。
- [常见问题](docs/troubleshooting.md)：任务进度、榜单数量及环境问题。
- [远端部署规划](docs/remote-deployment-plan.md)：已分析的方案与尚未实施的工作。
- [资料公开边界](docs/publication-policy.md)：公开源码与仅本地资料的分工。
- [来源说明](docs/sources.md)：原项目和版本追溯。

本机开发过程、真实部署清单、服务器信息、运行截图及历史验收证据保存在 Git 忽略的 `.local/private-docs/`。公共仓库不会提供这些资料，也不能从公共源码还原某台机器的运行状态。

## 来源与许可状态

项目整合自 `kunkun0521/stock-web`、`kunkun0521/stock` 和 `kunkun0521/stock-probability-engine`，保留来源追溯与算法契约。当前源码基线未包含可确认的整体许可证，因此本仓库没有擅自添加 MIT 等再授权声明。公开可见不等于授予任意使用、分发或商业授权；详见[来源说明](docs/sources.md)。
