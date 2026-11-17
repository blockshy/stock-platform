# 文档导航

本目录提供可公开、可复用的说明。机器地址、个人账号、内部开发过程、数据快照和现场验收记录只保存在本地私有目录，不是本目录的一部分。

| 你的目标 | 阅读顺序 |
| --- | --- |
| 了解项目能做什么 | [项目介绍](../README.md) → [用户手册](user-guide.md) |
| 在 Windows 准备环境 | [安装与配置](configuration.md) → [常见问题](troubleshooting.md) |
| 采集历史行情或运行预测 | [用户手册](user-guide.md) → [任务与榜单排查](troubleshooting.md) |
| 评估免费行情源和采集提速 | [免费数据源方案](free-data-source-plan.md)，尚未切换生产采集 |
| 断点续采与批量采样验证 | [采集断点与批量验证](data-collection-validation.md) → [比较器说明](../tests/batch_validation/README.md) |
| 验证修改是否影响功能 | [测试说明](testing.md) → [功能等效性](functional-parity.md) |
| 保持页面一致 | [前端规范](frontend-guidelines.md) |
| 切换浅色、暗色或跟随系统 | [用户手册](user-guide.md) → [主题配置](configuration.md) |
| 评估远端部署 | [远端部署规划](remote-deployment-plan.md) |
| 评估概率引擎改为 Java | [Java 引擎迁移评估](java-engine-migration-plan.md) |
| 提交或公开资料 | [公开边界](publication-policy.md) → [来源说明](sources.md) |

## 状态约定

- **已实现**：仓库已有对应代码或脚本；不意味着任意机器、任意数据规模均已验证。
- **需准备**：依赖、独立数据库、账号、模型和数据等由使用者配置。
- **规划中**：尚无完整验证后的生产交付。Linux / Docker 远端部署当前暂缓实施；免费数据源方案 A 正在验证，尚未切换生产；Python → Java 引擎迁移暂时搁置，保留评估文档，没有替换现有模型实现。
- **测试通过**：只能指具体提交、环境和范围下的新报告，不能引用历史机器记录替代本次测试。

文档代码块中的安装路径和示例值应按实际环境替换。不要将日志、配置快照、数据库导出或生产截图直接附在公共 Issue 中。
