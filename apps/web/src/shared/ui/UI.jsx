import { Children } from 'react';
import styles from './UI.module.css';

/** 拼接局部样式与旧页面的业务类名；旧类仅用于保留布局钩子。 */
const cx = (...values) => values.filter(Boolean).join(' ');

/** 中性面板：不读取模块上下文，不发请求；标题节点、操作区与 flush 行为原样保留。 */
export function Card({ title, extra, children, className = '', flush = false, titleAs: Title = 'h3' }) {
    return <section data-ui="card" className={cx(styles.card, className)}>
        {title !== undefined && title !== null && title !== '' ? <header className={styles.cardHead}>
            <Title className={styles.cardTitle}>{title}</Title>
            {extra ? <div className={styles.cardExtra}>{extra}</div> : null}
        </header> : null}
        {Children.toArray(children).length ? <div className={cx(styles.cardBody, flush && styles.flush)}>{children}</div> : null}
    </section>;
}

/** 数值只接收展示节点，禁止在公共组件中进行百分比、金额或成交量换算。 */
export function Metric({ label, value, hint, tone = 'plain', className = '', title }) {
    return <div data-ui="metric" className={cx(styles.metric, className)} title={title}>
        <div className={styles.metricLabel}>{label}</div>
        <div className={styles.metricValue} data-tone={tone}>{value}</div>
        {hint ? <div className={styles.metricHint}>{hint}</div> : null}
    </div>;
}

/** up/down 只代表市场方向；success/danger 专用于操作与任务结果。 */
export function Badge({ tone = 'plain', children, title, className = '' }) {
    return <span data-ui="badge" data-tone={tone} className={cx(styles.badge, className)} title={title}>{children}</span>;
}

export function Spinner({ className = '' }) {
    return <i className={cx(styles.spinner, className)} aria-hidden="true"/>;
}

/** 保留调用方事件与 type；加载期间同步禁用，避免视觉统一改变重复提交保护。 */
export function Button({ variant = '', sm = false, loading = false, loadingText, disabled = false,
    children, className = '', type = 'button', ...rest }) {
    return <button {...rest} type={type} data-ui="button" data-variant={variant || 'secondary'}
        data-size={sm ? 'small' : 'normal'} className={cx(styles.button, className)}
        disabled={disabled || loading} aria-busy={loading || undefined}>
        {loading ? <><Spinner/>{loadingText || children}</> : children}
    </button>;
}

export function FilterBar({ children, className = '' }) {
    return <div className={cx(styles.filterBar, className)}>{children}</div>;
}

export function Field({ label, hint, children, className = '' }) {
    return <label className={cx(styles.field, className)}>
        <span className={styles.fieldLabel}>{label}{hint ? ` · ${hint}` : ''}</span>{children}
    </label>;
}

/** onChange 与原模块一致回传字符串；数字解析仍由各业务表单负责。 */
export function Select({ value, onChange, options = [], className = '', ...rest }) {
    const normalized = options.map((option) => option && typeof option === 'object'
        ? option : { value: option, label: String(option) });
    return <select {...rest} data-ui="control" className={cx(styles.control, className)}
        value={value ?? ''} onChange={(event) => onChange(event.target.value)}>
        {normalized.map((option) => <option key={String(option.value)} value={option.value}>{option.label}</option>)}
    </select>;
}

export function Input({ value, onChange, type = 'text', className = '', ...rest }) {
    return <input {...rest} data-ui="control" className={cx(styles.control, className)}
        type={type} value={value ?? ''} onChange={(event) => onChange(event.target.value)}/>;
}

export function TextArea({ value, onChange, className = '', ...rest }) {
    return <textarea {...rest} data-ui="control" className={cx(styles.control, styles.textArea, className)}
        value={value ?? ''} onChange={(event) => onChange(event.target.value)}/>;
}

/** 状态外观统一；是否隐藏旧数据、何时重试等异步语义留在原模块。 */
export function EmptyState({ text = '暂无数据', loading = false, className = '' }) {
    return <div className={cx(styles.empty, className)} role={loading ? 'status' : undefined}>
        {loading ? <Spinner/> : null}<span>{text}</span>
    </div>;
}

export function Alert({ title, children, tone = 'info', action, className = '' }) {
    return <div data-ui="alert" data-tone={tone} className={cx(styles.alert, className)}
        role={tone === 'danger' ? 'alert' : 'status'}>
        {title || action ? <div className={styles.alertHead}><strong>{title}</strong>{action}</div> : null}
        {children}
    </div>;
}

/** 只统一标签外观；路由跳转、URL 状态和常驻挂载由上层保持。 */
export function Tabs({ items, value, onChange, className = '' }) {
    return <div data-ui="tabs" className={cx(styles.tabs, className)}>
        {items.map((item) => <button key={item.value} type="button" aria-pressed={item.value === value}
            className={cx(styles.tab, item.value === value && 'active')}
            onClick={() => onChange(item.value)}>{item.label}</button>)}
    </div>;
}

/** 分页不推算总页数：保留各接口自己的页数口径、摘要文本和是否提供首末页按钮。 */
export function Pager({ page, pages, size, onPage, onSize, sizes = [20, 50, 100],
    summary, showEdges = true, className = '' }) {
    return <div className={cx(styles.pager, className)}>
        <span className={styles.pagerSummary}>{summary}</span>
        <div className={styles.pagerButtons}>
            {showEdges ? <Button sm disabled={page <= 1} onClick={() => onPage(1)}>首页</Button> : null}
            <Button sm disabled={page <= 1} onClick={() => onPage(page - 1)}>上一页</Button>
            <span className={styles.pagerCurrent}>{page}</span>
            <Button sm disabled={page >= pages} onClick={() => onPage(page + 1)}>下一页</Button>
            {showEdges ? <Button sm disabled={page >= pages} onClick={() => onPage(pages)}>末页</Button> : null}
        </div>
        {onSize ? <label className={styles.pagerSize}>每页
            <Select value={size} onChange={(value) => onSize(Number(value))}
                options={sizes.map((count) => ({ value: count, label: String(count) }))}/>
        </label> : null}
    </div>;
}

/** 模块页头承载标题、说明和原有操作，不改变操作按钮的业务处理函数。 */
export function PageHeader({ title, description, actions, children }) {
    return <header className={styles.pageHeader}>
        <div className={styles.pageHeaderRow}>
            <div><h1>{title}</h1>{description ? <p>{description}</p> : null}</div>
            {actions ? <div className={styles.headerActions}>{actions}</div> : null}
        </div>{children}
    </header>;
}
