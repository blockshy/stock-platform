/**
 * 图表主题只负责展示层：数据、系列颜色、formatter、坐标范围与交互状态不属于主题。
 * 补丁刻意不携带 legend.selected、dataZoom.start/end 等字段，切换浅暗模式时可以
 * 直接合并到现有 ECharts 实例，从而保留用户已经选择的图例和缩放窗口。
 */

/** 从图表容器继承的 CSS 变量读取调色板，避免 JavaScript 再维护一份主题色。 */
export function readChartPalette(computed) {
    const read = (name, fallback = '') => computed.getPropertyValue(name).trim() || fallback;
    const text = read('--ui-text', computed.color);
    const muted = read('--ui-muted', text);
    const surface = read('--ui-surface');
    const border = read('--ui-border', muted);
    return {
        fontFamily: read('--ui-font', computed.fontFamily),
        fontSize: Number.parseFloat(read('--ui-text-sm', computed.fontSize)) || 12,
        text,
        muted,
        surface,
        subtle: read('--ui-surface-subtle', surface),
        border,
        grid: read('--ui-chart-grid', border),
        axis: read('--ui-chart-axis', border),
        tooltip: read('--ui-chart-tooltip-bg', surface),
        primary: read('--ui-primary', text),
        primarySoft: read('--ui-primary-soft', surface),
    };
}

/**
 * 只为原选项中存在的组件生成补丁，并保留 id 以便 ECharts 按稳定身份合并。
 * 不把单组件对象强制转换为数组；null 与未提供的组件也不会被主题创建出来。
 */
function componentPatch(value, build) {
    if (Array.isArray(value)) return value.map((item) => componentPatch(item, build));
    if (!value || typeof value !== 'object') return value;
    return { ...(value.id === undefined ? {} : { id: value.id }), ...build(value) };
}

function pointerStyle(palette) {
    return {
        lineStyle: { color: palette.muted },
        crossStyle: { color: palette.muted },
        shadowStyle: { color: palette.primarySoft },
        label: { color: palette.text, backgroundColor: palette.subtle, borderColor: palette.border },
    };
}

/** 轴标签只替换固定文字色；回调颜色可能表达业务含义，必须保留。 */
function axisStyle(axis, palette) {
    return {
        axisLabel: typeof axis.axisLabel?.color === 'function' ? {} : { color: palette.muted },
        nameTextStyle: { color: palette.muted },
        axisLine: { lineStyle: { color: palette.axis } },
        axisTick: { lineStyle: { color: palette.axis } },
        minorTick: { lineStyle: { color: palette.axis } },
        splitLine: { lineStyle: { color: palette.grid } },
        minorSplitLine: { lineStyle: { color: palette.grid } },
        axisPointer: pointerStyle(palette),
        ...(axis.splitArea ? { splitArea: { areaStyle: { color: [palette.surface, palette.subtle] } } } : {}),
    };
}

/**
 * 构造可以独立 setOption 的外观补丁。这里不复制整个业务 option：否则旧 start/end
 * 或 selected 默认值会在切换主题时覆盖用户刚刚缩放、隐藏曲线后的实时状态。
 */
export function createChartThemePatch(option, palette) {
    const patch = {
        backgroundColor: 'transparent',
        textStyle: { color: palette.muted, fontFamily: palette.fontFamily, fontSize: palette.fontSize },
    };
    for (const key of ['xAxis', 'yAxis', 'angleAxis', 'radiusAxis']) {
        if (option[key] !== undefined) patch[key] = componentPatch(option[key], (axis) => axisStyle(axis, palette));
    }
    if (option.title !== undefined) patch.title = componentPatch(option.title, () => ({
        textStyle: { color: palette.text }, subtextStyle: { color: palette.muted },
    }));
    if (option.legend !== undefined) patch.legend = componentPatch(option.legend, () => ({
        textStyle: { color: palette.muted }, inactiveColor: palette.muted,
        pageTextStyle: { color: palette.muted }, pageIconColor: palette.primary,
        pageIconInactiveColor: palette.border,
    }));
    if (option.tooltip !== undefined) patch.tooltip = componentPatch(option.tooltip, () => ({
        backgroundColor: palette.tooltip, borderColor: palette.border,
        textStyle: { color: palette.text }, axisPointer: pointerStyle(palette),
    }));
    if (option.axisPointer !== undefined) patch.axisPointer = componentPatch(option.axisPointer, () => pointerStyle(palette));
    if (option.dataZoom !== undefined) patch.dataZoom = componentPatch(option.dataZoom, () => ({
        backgroundColor: palette.subtle, borderColor: palette.border, fillerColor: palette.primarySoft,
        textStyle: { color: palette.muted },
        handleStyle: { color: palette.primary, borderColor: palette.surface },
        moveHandleStyle: { color: palette.primary },
        dataBackground: { lineStyle: { color: palette.muted }, areaStyle: { color: palette.border } },
        selectedDataBackground: { lineStyle: { color: palette.primary }, areaStyle: { color: palette.primarySoft } },
        emphasis: { handleStyle: { color: palette.primary }, moveHandleStyle: { color: palette.primary } },
    }));
    if (option.toolbox !== undefined) patch.toolbox = componentPatch(option.toolbox, () => ({
        iconStyle: { borderColor: palette.muted }, emphasis: { iconStyle: { borderColor: palette.primary } },
    }));
    if (option.visualMap !== undefined) patch.visualMap = componentPatch(option.visualMap, () => ({
        textStyle: { color: palette.muted }, borderColor: palette.border,
    }));
    if (option.radar !== undefined) patch.radar = componentPatch(option.radar, (radar) => ({
        axisName: { color: palette.muted }, axisLine: { lineStyle: { color: palette.axis } },
        splitLine: { lineStyle: { color: palette.grid } },
        ...(radar.splitArea ? { splitArea: { areaStyle: { color: [palette.surface, palette.subtle] } } } : {}),
    }));
    return patch;
}

/**
 * 对实际变化的 option 仍保持原先整体替换的行为，只在替换前合并外观字段。
 * 此处采用结构共享，不深拷贝 series/data，也不通过 JSON 序列化丢失 formatter 函数。
 */
function mergePresentation(original, patch) {
    if (Array.isArray(patch)) return patch.map((item, index) => mergePresentation(original?.[index], item));
    if (!patch || typeof patch !== 'object') return patch;
    const merged = { ...original };
    for (const [key, value] of Object.entries(patch)) merged[key] = mergePresentation(original?.[key], value);
    return merged;
}

export function applyChartTheme(option, palette) {
    return mergePresentation(option, createChartThemePatch(option, palette));
}
