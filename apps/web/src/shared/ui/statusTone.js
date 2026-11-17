/**
 * 旧任务页面曾用 up/down 表示失败/成功；在明确的任务状态调用处转为独立视觉语义。
 * 此函数不接受数值、不判断任务是否成功，也不可用于收益率、方向或风险等级。
 * 原业务 formatter 与状态映射文件保持不变，迁移仅影响渲染颜色。
 */
export const taskVisualTone = (legacyTone) => legacyTone === 'up' ? 'danger'
    : legacyTone === 'down' ? 'success' : legacyTone;
