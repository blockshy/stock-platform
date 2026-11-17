# 股票研究平台前端

React、Vite 与 ECharts 前端，提供行情、评分、短期预测、概率预测与策略研究页面。它通过 `/api` 使用 Java 后端；概率计算由后端调用 Python 引擎完成。

完整功能与日常操作见[使用指南](../../docs/user-guide.md)，环境参数见[配置说明](../../docs/configuration.md)。本目录命令只管理前端，不会创建数据库或启动后端。

## 本地开发

先准备 Node.js 24、锁定依赖，以及指向独立验收数据库的后端。在本目录执行：

```powershell
# 按锁文件安装依赖。
npm.cmd ci

# 使用已经单独启动的验收后端；端口按实际配置填写。
$env:VITE_BACKEND = 'http://127.0.0.1:18080'

# 关闭未集成的旧版预测服务，并使用独立开发端口。
$env:VITE_ENABLE_LEGACY_FORECAST = 'false'
npm.cmd run dev -- --port 15174
```

Vite 默认只监听 `127.0.0.1`，端口冲突时退出，不会自动换端口。开发代理保留 `/api` 前缀。不要让修改中的前端误连生产后端执行写操作。

```powershell
# 执行前端任务轮询测试和界面规则检查。
npm.cmd test
npm.cmd run check:ui

# 生成 dist；不要覆盖运行中的前端正在使用的产物。
npm.cmd run build
```

`npm.cmd run preview` 仅用于构建产物的本地预览。公网部署需要正式静态服务器、SPA 路由回退、HTTPS 与正确的 API 反向代理，不能直接复用开发服务器。

## 配置边界

- `VITE_BACKEND` 指定开发代理和本地备用地址。
- `VITE_API_BASE` 指定浏览器请求基址，远端方案拟采用同源 `/api`。
- `VITE_ENABLE_LEGACY_FORECAST=false` 让旧预测页面说明该服务未接入；短期预测和概率预测不依赖这个旧服务。
- `VITE_*` 变量会被编译进浏览器代码，禁止放置数据库密码、令牌或其他秘密。
- 本机变量放入被忽略的 `.env.local`。调整后需要重启开发服务或重新构建。

## 目录

| 目录 | 职责 |
| --- | --- |
| `src/app` | 导航、工作区布局 |
| `src/api` | 统一请求封装与业务 API |
| `src/pages` | 行情、采集、评分、短期预测等页面 |
| `src/prob` / `src/quant` / `src/ga` / `src/tlab` | 概率、策略、遗传算法与做 T 页面 |
| `src/shared/ui` / `src/shared/charts` | 共享界面与图表组件 |
| `src/styles` | 全局样式和设计变量 |
| `src/dev` | 仅开发环境开放的 `/dev/ui` 组件示例 |

`t-lab/` 是上游遗留独立示例，根工程不会启动它。当前“做 T 模型 2”的实现位于 `src/tlab`。

新增页面遵守[前端规范](../../docs/frontend-guidelines.md)，复用统一组件、状态及格式化规则。API、统计口径和计算公式变更不能混在纯样式调整中。
