# 前端界面规范与迁移边界

本规范适用于 `apps/web`。统一界面保持原有业务路由、接口、参数和结果口径；不在 UI 层重写评分、预测、复权或交易计算。

## 目录职责

| 目录或文件 | 职责 |
| --- | --- |
| `src/app/navigation.js` | 已有路由的导航分组、名称和面包屑，不定义业务权限或任务逻辑 |
| `src/app/Navigation.module.css` | 侧栏局部样式；移动端开合仍使用 App 原有状态 |
| `src/app/Workspace.module.css` | 限定在工作台根节点内的旧页面兼容样式，统一表格、原生表单和模块页头 |
| `src/styles/tokens.css` | 全站颜色、字号、间距、圆角和控件高度的唯一设计变量来源 |
| `src/shared/ui/UI.jsx` | 无业务上下文的 Card、Metric、Button、Badge、Field、Pager、Tabs、Alert、PageHeader 等 |
| `src/shared/ui/UI.module.css` | 基础组件局部样式 |
| `src/shared/charts/ChartView.jsx` | ECharts 生命周期、容器尺寸、点击转发及加载/空态遮罩 |
| `src/dev/ComponentGallery.jsx` | 开发专用静态组件示例，不发接口请求，不启动任务 |

`components/Panel`、`StatCard`、`Pagination`、`Tabs`，以及 GA、概率、策略、TLab 原有基础组件文件作为兼容适配层保留。新页面优先直接引用 `shared/ui`；业务模块专用组件继续放在自己的目录中。

## 视觉约定

- 工作台使用浅灰画布、白色卡片、蓝色主操作；默认正文与控件为 13px，说明为 12px，模块页标题为 20px。
- 间距使用 4、8、12、16、24px；常规表单高度 32px，紧凑按钮 28px；卡片圆角 12px，控件圆角 6px。
- 数据表采用统一浅色表头、12px 正文、等宽数字；保留业务列的排序、对齐、精度和横向滚动。
- 数值密度较高的页面可以保留专用布局，不通过缩小全站字号解决空间问题。
- 新样式使用 CSS Modules。允许 `tokens.css` 定义全局变量；兼容选择器必须放在 `Workspace.module.css` 的局部 `.root` 下，禁止新增无作用域的 `.panel`、`.app`、`button` 等业务覆盖。
- TLab 内部布局已改用 `tlab-layout`、`tlab-content`，避免与全站 `.app`、`.app-main` 同名；模块不再重定义全站颜色。
- 图表高度、区间条位置和真实进度宽度属于动态数据，可以保留行内样式；固定颜色、字体、间距应使用设计变量。

## 市场方向与任务结果分开

| 语义 | token / tone | 用途 |
| --- | --- | --- |
| 上涨 | `--ui-rise` / `up` | 涨幅、正收益及既有市场方向 |
| 下跌 | `--ui-fall` / `down` | 跌幅、负收益及既有市场方向 |
| 操作成功 | `--ui-success` / `success` | 采集完成、任务成功、提交完成 |
| 操作失败 | `--ui-danger` / `danger` | 任务失败、接口错误、危险操作 |
| 等待或提示 | `--ui-warning` / `warn` | 待确认、缺少条件 |
| 运行或主操作 | `--ui-primary` / `info`、`accent` | 运行中、主要操作 |

不能用“上涨”表示任务成功，也不能根据数值正负推断任务状态。旧任务映射里的 `up/down` 仅在明确的状态展示调用处通过 `taskVisualTone` 转换。行情 formatter 和状态判定逻辑不因此修改。风险等级仍保留原业务定义。

## 组件使用边界

```jsx
// 示例只组织展示；金额、比例、单位由原业务 formatter 提供。
import { Card, Metric, Button } from '../shared/ui/UI.jsx';

<Card title="任务结果" extra={<Button onClick={reload}>刷新</Button>}>
    <Metric label="成功证券" value={confirmedCount} tone="success"/>
</Card>
```

- `Card` 接收标题节点、操作区和 `flush`；不请求数据、不注入模块上下文。业务 `className` 保留为布局钩子。
- `Metric` 仅显示传入节点，不自动乘 100、不将空值改成零、不补金额或成交量单位。
- `Button` 保留原事件函数，`loading` 会禁用按钮并显示加载状态；任务自身的锁和提交前校验仍由业务代码负责。
- `Input`、`Select` 的 `onChange` 继续回传字符串。需要保存小数中间态和中文输入的表单继续使用现有 `DecimalInput`。
- `Pager` 使用调用方提供的 `page/pages`，不自行计算总页数；各模块原有总数文本、空页归一化和首末页按钮差异由适配层保留。
- `Tabs` 只改变选中值和外观，不决定组件卸载、URL 或持久化策略。
- `Alert` 和空态组件只负责展示；GA、概率、策略原有异步分支保留，包括首次加载、错误、空数据及后台刷新时是否阻止交互。

## 图表约定

三套旧图表入口转接到 `ChartView`，保留各自 320/280/260px 默认高度。容器始终挂载，使用 `ResizeObserver` 自适应并在卸载时销毁实例，兼容 TLab 隐藏标签重新显示。点击回调使用最新函数。

原有 `option` 中的数据、指标、系列颜色、tooltip formatter、复权口径和坐标区间保持原样；只提供默认字体。继续使用 `setOption(..., true)` 整体替换，避免旧股票系列残留。市场红涨绿跌由原图表选项决定。

## 不可随界面迁移改变的行为

1. 保留 `/`、`/stocks`、`/stocks/:code`、`/scores`、`/short-term`、`/forecast`、`/t-trading`、`/t-lab`、`/data-crawler`、`/ga/*`、`/quant`、`/prob` 路由及既有查询参数。
2. 全站选股仍使用原 localStorage 键；页面参数仍使用原 sessionStorage 键。更换存储键或持久化机制必须单独设计迁移。
3. TLab 三个功能页继续通过 `hidden` 常驻挂载；GA 二级路由、概率和策略的 URL 状态继续由原模块管理。
4. 主站 `formatPct` 接收已是百分数的值，概率/策略 `pct` 接收 0～1 比例；不可直接合并。空值、精度和符号也保留原口径。
5. 股票成交量单位仍为“股”，58 ETF 为“份”，不将原始值转换成“手”。ETF 行情独立存储、不自动进入个股模型的边界保持不变。
6. `crawlerMonitor`、`runMonitor`、API client 和全局 `useApi` 不因换肤改写。采集计数仍按后端实际上报，最近查询时间不能称为后台心跳。
7. 旧模型预测仍受 `VITE_ENABLE_LEGACY_FORECAST` 控制。本次整体项目不因此引入额外的 `stock-predict` 或 `stock-fetch`。

## 开发示例与验收

开发服务器访问 `/dev/ui`，可以查看主要/次要/危险/禁用/加载按钮、表单、指标、语义色、表格分页、图表以及空态和错误提示。该入口仅在 `import.meta.env.DEV` 为真时注册；生产构建没有此路由和导航。

验收应覆盖桌面宽屏、960px 附近和390px窄屏：导航开合、表格滚动、弹层不被裁剪、键盘可达、焦点可见、加载和错误反馈清楚。视觉改版不要求与旧站像素相同，但字段、数据、单位、操作能力和状态恢复应等效。

业务回归对照 `docs/source-baseline.json`。先用固定响应与静态数据验证过滤、排序、分页、选股、前进后退及任务状态转换；浏览器验收不得误触真实采集、训练、清理或晋升。真实运算验收必须在根项目管理的隔离测试数据库中进行。

构建和测试通过根项目统一脚本执行。组件变更先验证编译，再运行与变化相关的离线任务测试和浏览器回归；不因外观改动擅自升级 React、Vite、Router 或 ECharts。
