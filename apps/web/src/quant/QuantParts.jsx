import * as UI from '../shared/ui/UI.jsx';
/**
 * 量化页面的基础 UI：复用 ga.css 的样式变量与类名（.ga-card/.ga-btn/...），
 * 但不依赖 GA 的术语弹窗上下文，避免把两个模块耦在一起。
 */
export function QCard(props) {
    return <UI.Card {...props} className={`ga-card ${props.className || ''}`}/>;
}

export function QKpi(props) {
    return <UI.Metric {...props} />;
}

export function QBadge(props) {
    return <UI.Badge {...props} />;
}

export function QSpinner(props) {
    return <UI.Spinner {...props} />;
}

export function QBtn(props) {
    return <UI.Button {...props} />;
}

export function QBar(props) {
    return <UI.FilterBar {...props} />;
}

export function QField(props) {
    return <UI.Field {...props} />;
}

export function QSelect(props) {
    return <UI.Select {...props} />;
}

export function QInput(props) {
    return <UI.Input {...props} />;
}

export function QEmpty({ text = '暂无数据' }) {
    return <UI.EmptyState text={text}/>;
}

export function QLoading({ text = '加载中…' }) {
    return <UI.EmptyState text={text} loading/>;
}

export function QError({ message, onRetry }) {
    if (!message) return null;
    return <UI.Alert tone="danger" title="加载失败"
        action={onRetry ? <UI.Button sm variant="ghost" onClick={onRetry}>重试</UI.Button> : null}>
        {String(message)}
    </UI.Alert>;
}

/** 与 GA 的 AsyncPanel 一致的加载/空/错误三态，刷新时保留旧内容并降低透明度。 */
export function QAsync({ state, isEmpty, emptyText = '暂无数据', children }) {
    const { data, loading, error, reload } = state || {};
    if (error) return <QError message={error} onRetry={reload}/>;
    if (loading && data === undefined) return <QLoading/>;
    const blank = isEmpty ? isEmpty(data) : (data === undefined || data === null);
    if (blank) return <QEmpty text={emptyText}/>;
    return (
        <div className={`ga-busy${loading ? ' is-refreshing' : ''}`}>
            {typeof children === 'function' ? children(data) : children}
        </div>
    );
}

export function QPager({ page = 1, size = 20, total = 0, pages = 1, onPage, onSize,
                           sizes = [20, 50, 100] }) {
    // 保留模块原有页码、总数文本和首末页开关，只共享展示结构。
    const p = Number(page) || 1;
    const ps = Math.max(1, Number(pages) || 1);
    return <UI.Pager page={p} pages={ps} size={size} onPage={onPage} onSize={onSize}
        sizes={sizes} summary={`共 ${total} 条 / ${ps} 页`}/>;
}

export function QTabs(props) {
    return <UI.Tabs {...props} />;
}
