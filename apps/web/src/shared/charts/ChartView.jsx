import { useEffect, useRef } from 'react';
import * as echarts from 'echarts';
import { EmptyState } from '../ui/UI.jsx';
import { useTheme } from '../../theme/ThemeProvider.jsx';
import { applyChartTheme, createChartThemePatch, readChartPalette } from './chartTheme.js';
import styles from './ChartView.module.css';

/**
 * 全站图表生命周期容器。
 * 保留完整 ECharts 注册（原 GA 已使用整包），避免统一时漏掉饼图、散点或标记类型。
 * 数据、series、tooltip formatter、数值单位和涨跌色完全由原 option 决定。
 * 主题仅适配文字、坐标与提示层外观，不重建实例，也不覆盖用户的图例和缩放选择。
 * 容器常驻，ResizeObserver 支持 TLab 的 hidden 页签重新显示，点击回调始终取最新值。
 */
export default function ChartView({ option, height = 280, onClick, empty = false,
    emptyText = '暂无数据', loading = false, className = '' }) {
    const elementRef = useRef(null);
    const chartRef = useRef(null);
    const optionRef = useRef(null);
    const clickRef = useRef(onClick);
    const { resolvedTheme } = useTheme();
    clickRef.current = onClick;

    useEffect(() => {
        if (!elementRef.current) return undefined;
        const chart = echarts.init(elementRef.current);
        chartRef.current = chart;
        chart.on('click', (event) => clickRef.current?.(event));
        const observer = new ResizeObserver(() => chart.resize());
        observer.observe(elementRef.current);
        return () => {
            observer.disconnect();
            chart.dispose();
            chartRef.current = null;
            optionRef.current = null;
        };
    }, []);

    useEffect(() => {
        if (!chartRef.current || !option) return;
        // Provider 在通知订阅组件之前已同步根节点主题，因此读取到的是新主题变量。
        // 数据选项变化仍完整替换；只有主题变化时发送不含数据/范围/图例选择的局部补丁。
        const computed = window.getComputedStyle(elementRef.current);
        const palette = readChartPalette(computed);
        if (optionRef.current === option) {
            chartRef.current.setOption(createChartThemePatch(option, palette), { notMerge: false });
        } else {
            chartRef.current.setOption(applyChartTheme(option, palette), { notMerge: true });
            optionRef.current = option;
        }
    }, [option, resolvedTheme]);

    return <div className={`${styles.chart} ${className}`} style={{ height }} aria-busy={loading || undefined}>
        <div ref={elementRef} className={styles.canvas} style={{ height }}/>
        {empty || loading ? <div className={styles.mask}>
            <EmptyState text={empty ? emptyText : '加载中…'} loading={!empty && loading}/>
        </div> : null}
    </div>;
}
