import * as UI from '../../shared/ui/UI.jsx';
/** 卡片容器：标题 + 右侧操作区 */
export function Panel(props) {
    return <UI.Card {...props} titleAs="h2" className={`panel ${props.className || ''}`}/>;
}

/** 概览数字卡片 */
export function StatCard(props) {
    return <UI.Metric {...props} className="stat-card" title={props.hint || undefined}/>;
}

/** 表单标签 + 悬停说明：问号气泡绝对定位，不占布局高度 */
export function FieldLabel({ children, tip }) {
    return (
        <span className="field-label">
            <span>{children}</span>
            {tip ? (
                <span className="field-hint" tabIndex={0} role="note" aria-label={tip}
                      onMouseDown={(e) => e.preventDefault()}>
                    <span className="field-hint-icon">?</span>
                    <span className="field-hint-pop" role="tooltip">{tip}</span>
                </span>
            ) : null}
        </span>
    );
}

export function ErrorBanner({ message, onClose }) {
    if (!message) return null;
    return <UI.Alert tone="danger" title="操作未完成"
        action={onClose ? <UI.Button sm variant="ghost" onClick={onClose} aria-label="关闭提示">×</UI.Button> : null}>
        {message}
    </UI.Alert>;
}

export function Loading({ show, text = '计算中…' }) {
    if (!show) return null;
    return <div className="loading-mask">{text}</div>;
}
