import test from 'node:test';
import assert from 'node:assert/strict';
import { applyChartTheme, createChartThemePatch, readChartPalette } from './chartTheme.js';

// 合成色值只用于验证字段流向，不读取浏览器、不访问接口，也不修改项目设计变量。
const light = {
    text: 'light-text', muted: 'light-muted', surface: 'light-surface', subtle: 'light-subtle',
    border: 'light-border', grid: 'light-grid', axis: 'light-axis', tooltip: 'light-tooltip',
    primary: 'light-primary', primarySoft: 'light-primary-soft', fontFamily: 'test-font', fontSize: 12,
};
const dark = Object.fromEntries(Object.entries(light).map(([key, value]) =>
    [key, typeof value === 'string' ? value.replace('light-', 'dark-') : value]));

/** 冻结原 option 的全部普通结构，保证主题适配无法偷偷修改业务输入。 */
function freezeTree(value) {
    if (value && typeof value === 'object') {
        Object.values(value).forEach(freezeTree);
        Object.freeze(value);
    }
    return value;
}

test('浅暗适配只改外观，保留价格、涨跌色、周期、formatter 与轴范围', () => {
    const formatter = (value) => `${value}份`;
    const values = [[1, 2, 0.5, 2.5], [2, 1.5, 1, 2]];
    const series = [{ type: 'candlestick', data: values,
        itemStyle: { color: 'rise', color0: 'fall', borderColor: 'rise', borderColor0: 'fall' } }];
    const original = freezeTree({
        animation: false, series, dataset: { source: [[1, 2]] },
        tooltip: { trigger: 'axis', valueFormatter: formatter, textStyle: { color: 'old', fontSize: 11 } },
        xAxis: { type: 'category', data: ['a', 'b'], axisLabel: { formatter, color: 'old' } },
        yAxis: [{ min: 0, max: 10, scale: true, splitLine: { show: false, lineStyle: { opacity: 0.35 } } }],
        legend: { selected: { '日K': false }, textStyle: { fontSize: 10 } },
        dataZoom: [{ type: 'inside', start: 55, end: 100 }, { type: 'slider', id: 'slider', height: 14 }],
    });
    const output = applyChartTheme(original, dark);
    assert.equal(output.series, series);
    assert.equal(output.series[0].data, values);
    assert.equal(output.dataset, original.dataset);
    assert.equal(output.tooltip.valueFormatter, formatter);
    assert.equal(output.xAxis.axisLabel.formatter, formatter);
    assert.equal(output.tooltip.textStyle.fontSize, 11);
    assert.equal(output.xAxis.data, original.xAxis.data);
    assert.equal(output.yAxis[0].min, 0);
    assert.equal(output.yAxis[0].max, 10);
    assert.equal(output.yAxis[0].splitLine.show, false);
    assert.equal(output.yAxis[0].splitLine.lineStyle.opacity, 0.35);
    assert.equal(output.yAxis[0].splitLine.lineStyle.color, dark.grid);
    assert.equal(output.legend.selected, original.legend.selected);
    assert.equal(output.dataZoom[0].start, 55);
    assert.equal(output.dataZoom[1].height, 14);
    assert.equal(output.dataZoom[1].id, 'slider');
    assert.equal(output.tooltip.backgroundColor, dark.tooltip);
    assert.equal(original.xAxis.axisLabel.color, 'old');
});

test('纯主题补丁不携带缩放、图例选择或系列，保留组件身份和多轴数组形态', () => {
    const source = {
        series: [{ type: 'line', data: [1, 2] }],
        xAxis: [{ id: 'first', data: [1, 2] }, { id: 'second', min: 0, max: 5 }],
        legend: [{ id: 'legend', data: ['a'], selected: { a: true } }],
        dataZoom: [{ id: 'zoom', type: 'slider', start: 0, end: 100, startValue: 0, endValue: 2 }],
    };
    const patch = createChartThemePatch(source, dark);
    assert.equal(Object.hasOwn(patch, 'series'), false);
    assert.equal(patch.xAxis.length, 2);
    assert.equal(patch.xAxis[0].id, 'first');
    assert.equal(Object.hasOwn(patch.xAxis[0], 'data'), false);
    assert.equal(Object.hasOwn(patch.xAxis[1], 'min'), false);
    assert.equal(Object.hasOwn(patch.legend[0], 'selected'), false);
    assert.equal(Object.hasOwn(patch.legend[0], 'data'), false);
    for (const key of ['start', 'end', 'startValue', 'endValue', 'rangeMode']) {
        assert.equal(Object.hasOwn(patch.dataZoom[0], key), false, `补丁不能重置 ${key}`);
    }
    assert.equal(patch.dataZoom[0].id, 'zoom');
});

test('连续切换主题更新文字、提示层和网格，不污染原选项或颜色回调', () => {
    const valueColor = (value) => value < 0 ? 'fall' : 'rise';
    const source = freezeTree({
        xAxis: { axisLabel: { color: valueColor } }, yAxis: { axisLabel: { color: 'legacy' } },
        tooltip: { axisPointer: { type: 'cross' } }, title: { text: '价格' },
    });
    const lightOption = applyChartTheme(source, light);
    const darkOption = applyChartTheme(source, dark);
    assert.equal(lightOption.xAxis.axisLabel.color, valueColor);
    assert.equal(darkOption.xAxis.axisLabel.color, valueColor);
    assert.equal(lightOption.yAxis.axisLabel.color, light.muted);
    assert.equal(darkOption.yAxis.axisLabel.color, dark.muted);
    assert.equal(darkOption.tooltip.axisPointer.type, 'cross');
    assert.equal(darkOption.tooltip.axisPointer.label.backgroundColor, dark.subtle);
    assert.equal(darkOption.title.text, '价格');
    assert.equal(darkOption.title.textStyle.color, dark.text);
    assert.equal(lightOption.yAxis.axisLabel.color, light.muted);
});

test('不为饼图等无坐标图新增坐标轴，visualMap 数据映射和 toolbox 动作保持原样', () => {
    const action = () => undefined;
    const source = freezeTree({
        series: [{ type: 'pie', data: [{ name: 'a', value: 1 }] }],
        visualMap: { min: 0, max: 1, inRange: { color: ['low', 'high'] } },
        toolbox: { feature: { custom: { onclick: action } } },
    });
    const output = applyChartTheme(source, dark);
    assert.equal(Object.hasOwn(output, 'xAxis'), false);
    assert.equal(Object.hasOwn(output, 'yAxis'), false);
    assert.equal(output.visualMap.inRange, source.visualMap.inRange);
    assert.equal(output.toolbox.feature.custom.onclick, action);
    assert.equal(output.toolbox.iconStyle.borderColor, dark.muted);
});

test('调色板从容器 CSS 读取，专用图表变量缺失时回退到统一语义色', () => {
    const values = { '--ui-text': 'text', '--ui-muted': 'muted', '--ui-border': 'border',
        '--ui-surface': 'surface', '--ui-chart-grid': 'grid', '--ui-text-sm': '13px' };
    const palette = readChartPalette({ color: 'fallback', fontSize: '12px', fontFamily: 'font',
        getPropertyValue: (key) => values[key] ?? '' });
    assert.equal(palette.grid, 'grid');
    assert.equal(palette.axis, 'border');
    assert.equal(palette.tooltip, 'surface');
    assert.equal(palette.text, 'text');
    assert.equal(palette.fontSize, 13);
    assert.equal(palette.fontFamily, 'font');
});
