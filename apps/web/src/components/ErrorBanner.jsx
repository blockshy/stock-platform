import { describeApiBase } from '../api/client.js';

/**
 * 请求失败时的一级提示条。
 * 之所以单独做成横幅：接口挂掉时页面骨架仍然完整，小字提示很容易被忽略，
 * 看起来就像「页面正常但没有数据」。横幅会直接给出失败原因和排查步骤。
 */
export default function ErrorBanner({ message, onRetry }) {
    if (!message) return null;

    const base = describeApiBase();

    return (
        <div className="error-banner">
            <div className="error-banner-head">
                <strong>数据加载失败</strong>
                {onRetry ? (
                    <button type="button" className="ghost" onClick={onRetry}>重试</button>
                ) : null}
            </div>
            <div className="error-banner-msg">{message}</div>
            {/* 通用提示兼容本机与域名部署；连接细节折叠显示，不把开发命令当作用户操作流程。 */}
            <p className="error-banner-tips">请确认网络和服务状态后重试。若提示提交结果未知，请先查看任务或结果记录，避免重复提交。</p>
            <details className="error-banner-tips">
                <summary>连接诊断</summary>
                <p>当前接口：<code>{base.primary}</code></p>
                {base.configurationError ? <p>接口地址配置无效，请联系部署维护人员检查配置。</p> : null}
            </details>
        </div>
    );
}
