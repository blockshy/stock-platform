import * as UI from '../shared/ui/UI.jsx';
/** 概率页基础 UI：复用 ga.css 的变量与类名，不依赖 GA 模块的上下文。 */
import { subscribeApiPending } from '../hooks/useApi.js';
import { useEffect, useState } from 'react';

export function PCard(props) {
    return <UI.Card {...props} className={`ga-card ${props.className || ''}`}/>;
}

export function PKpi(props) {
    return <UI.Metric {...props} />;
}

export function PBadge(props) {
    return <UI.Badge {...props} />;
}

export function PSpinner(props) {
    return <UI.Spinner {...props} />;
}

export function PBtn(props) {
    return <UI.Button {...props} />;
}

export function PBar(props) {
    return <UI.FilterBar {...props} />;
}

export function PField(props) {
    return <UI.Field {...props} />;
}

export function PSelect(props) {
    return <UI.Select {...props} />;
}

export function PInput(props) {
    return <UI.Input {...props} />;
}

export function PEmpty({ text = '暂无数据' }) {
    return <UI.EmptyState text={text}/>;
}

export function PLoading({ text = '加载中…' }) {
    return <UI.EmptyState text={text} loading/>;
}

export function PError({ message, onRetry }) {
    if (!message) return null;
    return <UI.Alert tone="danger" title="加载失败"
        action={onRetry ? <UI.Button sm variant="ghost" onClick={onRetry}>重试</UI.Button> : null}>
        {String(message)}
    </UI.Alert>;
}

export function PAsync({ state, isEmpty, emptyText = '暂无数据', children }) {
    const { data, loading, error, reload } = state || {};
    if (error) return <PError message={error} onRetry={reload}/>;
    if (loading && data === undefined) return <PLoading/>;
    const blank = isEmpty ? isEmpty(data) : (data === undefined || data === null);
    if (blank) return <PEmpty text={emptyText}/>;
    return (
        <div className={`ga-busy${loading ? ' is-refreshing' : ''}`}>
            {typeof children === 'function' ? children(data) : children}
        </div>
    );
}

export function PPager({ page = 1, size = 20, total = 0, pages = 1, onPage, onSize,
                           sizes = [20, 50, 100] }) {
    // 保留模块原有页码、总数文本和首末页开关，只共享展示结构。
    const p = Number(page) || 1;
    const ps = Math.max(1, Number(pages) || 1);
    return <UI.Pager page={p} pages={ps} size={size} onPage={onPage} onSize={onSize}
        sizes={sizes} summary={`共 ${Number(total || 0).toLocaleString()} 条 · 第 ${p}/${ps} 页`} showEdges={false}/>;
}

/** 顶部全局在途指示：后端慢的时候明确告诉用户「请求还在跑」。 */
export function PActivityBar() {
    const [pending, setPending] = useState(0);
    useEffect(() => subscribeApiPending(setPending), []);
    return (
        <div className={`prob-activity${pending > 0 ? ' is-on' : ''}`}
             title={pending > 0 ? `当前有 ${pending} 个接口请求在途` : '接口空闲'}>
            {pending > 0 ? <><PSpinner/>正在加载 {pending} 项数据…</> : '数据已就绪'}
        </div>
    );
}
