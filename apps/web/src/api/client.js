/**
 * 统一 JSON API 入口，保持既有调用签名与 {code,message,data} 解包语义。
 * 新增可选 options：signal、timeoutMs、totalTimeoutMs、maxAttempts 和 headers。
 * GET 按有界策略重试，写操作永不自动重发；策略详情及离线测试见 requestPolicy.js。
 */
import { ApiError, buildApiUrl, isLoopbackHost, performJsonRequest, resolveApiBaseCandidates } from './requestPolicy.js';

const CONFIGURED = (import.meta.env?.VITE_API_BASE || '').trim();
const DEV_BACKEND = typeof __BACKEND__ !== 'undefined' ? (__BACKEND__ || '') : '';
const STORE_KEY = 'stock.api.base';
export { ApiError };

function readStoredBase() {
    try { return window.localStorage.getItem(STORE_KEY) || ''; }
    catch { return ''; }
}

function rememberBase(base) {
    try {
        if (base && base !== '/api') window.localStorage.setItem(STORE_KEY, base);
        else window.localStorage.removeItem(STORE_KEY);
    } catch { /* 禁止存储或容量不足不应让已成功的接口请求失败。 */ }
}

/** 显式配置具有唯一权威性；本机开发保留兼容探测，公开域名只使用同源 /api。 */
export function apiBaseCandidates() {
    return resolveApiBaseCandidates({ configured: CONFIGURED, storedBase: readStoredBase(),
        backend: DEV_BACKEND, origin: window.location.origin, hostname: window.location.hostname });
}

function request(path, params = {}, init = {}, options = {}) {
    return performJsonRequest({ path, params, init, options, bases: apiBaseCandidates(),
        origin: window.location.origin,
        allowLocalFallback: !CONFIGURED && isLoopbackHost(window.location.hostname),
        onBaseSuccess: rememberBase,
    });
}

export const apiGet = async (path, params = {}, options = {}) => request(path, params, {}, options);

/** 同步计算可能耗时较长，写操作默认允许等待 15 分钟，但绝不自动补发第二次。 */
export const apiPost = async (path, data = {}, options = {}) => request(path, {}, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data),
}, options);

export const apiPut = async (path, data = {}, options = {}) => request(path, {}, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data),
}, options);

export const apiDelete = async (path, options = {}) => request(path, {}, { method: 'DELETE' }, options);

/** 文件流入口仍交给浏览器打开；这里只构建首选地址，不冒充 JSON 请求自动重试。 */
export function apiUrl(path, params = {}) {
    return buildApiUrl(apiBaseCandidates()[0] || '/api', path, params, window.location.origin).toString();
}

export function describeApiBase() {
    try {
        const candidates = apiBaseCandidates();
        return { primary: candidates[0], all: candidates, backend: CONFIGURED ||
            (isLoopbackHost(window.location.hostname) ? DEV_BACKEND : window.location.origin) };
    } catch {
        // 此函数由错误提示组件在 render 中调用；配置错误必须保持可诊断，不能再次抛错白屏。
        // 不展示未经验证的原始配置，以免把误填的 URL 凭据直接渲染到页面。
        return { primary: '接口基址配置无效', all: [], backend: '', configurationError: true };
    }
}
