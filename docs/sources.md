# 项目来源与许可状态

本工程整合三个原项目，并保留整合前已存在的修复。上游提交用于追溯来源；实际算法一致性由 `contracts/` 中的文件摘要与固定数值基线约束，不能将本工程视为对应上游提交的未修改副本。

| 来源 | 工程目录 | 追溯提交 |
| --- | --- | --- |
| [stock-web](https://github.com/kunkun0521/stock-web) | `apps/web` | `08db05580b1fd350f97d771503f3c6b1501fa9fa` |
| [stock](https://github.com/kunkun0521/stock) | `apps/api` | `5801df9691741ac354f4a72aa084b4a463f2c88a` |
| [stock-probability-engine](https://github.com/kunkun0521/stock-probability-engine) | `engines/probability` | `ca7507bc9087969d57066c1e26a3aa24d82f91ef` |

机器可读清单见 [source-baseline.json](source-baseline.json)。`stock-fetch` 不在整合范围内，旧模型页面所需的额外 `stock-predict` 服务也未纳入。

## 许可说明

当前纳入的源码基线中未发现整体 LICENSE、NOTICE 或 COPYING 文件。本仓库不为这些上游代码擅自添加新的整体开源许可证，也不宣称拥有额外授权。若计划再分发、商业使用或以新的许可证发布，应先确认各来源的适用授权。

依赖库继续遵循各自许可证。前端依赖见 `apps/web/package-lock.json`，Java 依赖见 `apps/api/pom.xml`，Python 依赖见 `engines/probability/requirements.lock.txt`；它们的许可证不自动成为整个整合项目的许可证。

原开发过程文档及文档渲染工具二进制未随本公共快照发布。功能行为以源码和公开用户文档为准，不能把未发布的历史文档当作公共接口承诺。
