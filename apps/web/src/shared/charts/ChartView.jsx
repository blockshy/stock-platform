import { useEffect, useRef } from 'react';
import * as echarts from 'echarts';
import { EmptyState } from '../ui/UI.jsx';
import styles from './ChartView.module.css';

/**
 * 全站图表生命周期容器。
 * 保留完整 ECharts 注册（原 GA 已使用整包），避免统一时漏掉饼图、散点或标记类型。
 * 数据、series、tooltip、数值单位和涨跌色完全由原 option 决定；这里只统一容器与字体。
 * 容器常驻，ResizeObserver 支持 TLab 的 hidden 页签重新显示，点击回调始终取最新值。
 */
export default function ChartView({ option, height = 280, onClick, empty = false,
    emptyText = '暂无数据', loading = false, className = '' }) {
    const elementRef = useRef(null);
    const chartRef = useRef(null);
    const clickRef = useRef(onClick);
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
        };
    }, []);

    useEffect(() => {
        if (!chartRef.current || !option) return;
        // 从真实容器读取继承的设计变量，字体与主题色只有 tokens.css 一个来源。
        // option 的显式文本字段优先；不修改图表数据、坐标区间或 tooltip formatter。
        const computed = window.getComputedStyle(elementRef.current);
        const fontFamily = computed.getPropertyValue('--ui-font').trim() || computed.fontFamily;
        const fontSize = Number.parseFloat(computed.getPropertyValue('--ui-text-sm') || computed.fontSize);
        const color = computed.getPropertyValue('--ui-muted').trim() || computed.color;
        chartRef.current.setOption({
            ...option,
            textStyle: { fontFamily, fontSize, color, ...option.textStyle },
        }, true);
    }, [option]);

    return <div className={`${styles.chart} ${className}`} style={{ height }} aria-busy={loading || undefined}>
        <div ref={elementRef} className={styles.canvas} style={{ height }}/>
        {empty || loading ? <div className={styles.mask}>
            <EmptyState text={empty ? emptyText : '加载中…'} loading={!empty && loading}/>
        </div> : null}
    </div>;
}
