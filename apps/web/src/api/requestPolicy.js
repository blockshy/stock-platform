/**
 * 请求策略与浏览器状态解耦，便于用离线 fetch 替身验证重试边界。
 * GET 最多三次、每次最多 60 秒、总预算 130 秒；同步写操作预留 15 分钟，
 * 但任何 POST/PUT/DELETE 都只发送一次，避免超时后重复创建采集、训练或清理任务。
 */
export const REQUEST_DEFAULTS = Object.freeze({
    readTimeoutMs: 60_000,
    readBudgetMs: 130_000,
    writeTimeoutMs: 15 * 60_000,
    maxReadAttempts: 3,
    backoffMs: 400,
});

const TRANSIENT_HTTP = new Set([408, 429, 502, 503, 504]);
const UNKNOWN_WRITE = '请求结果未知，服务端可能仍在执行；请先查询任务或结果记录，不要直接重复提交。';

export class ApiError extends Error {
    constructor(code, message, status, url, retryable = false, details = {}) {
        super(message || '接口异常');
        this.name = 'ApiError';
        Object.assign(this, { code, status, url, retryable, ...details });
    }
}

export const trimSlash = (value) => String(value || '').trim().replace(/\/+$/, '');
export const isLoopbackHost = (host) => /^(localhost|127(?:\.\d{1,3}){3}|\[?::1\]?)$/i.test(host || '');

/** 显式配置只使用唯一地址；非回环网页不采用缓存地址或编译时的本机后端回退。 */
export function resolveApiBaseCandidates({ configured = '', storedBase = '', backend = '', origin, hostname }) {
    const parse = (value) => {
        try {
            const url = new URL(value, origin);
            return /^https?:$/.test(url.protocol) && !url.username && !url.password ? url : null;
        } catch { return null; }
    };
    const explicit = trimSlash(configured);
    if (explicit) {
        if (!parse(explicit)) throw new ApiError(-1, '接口基址配置无效，请检查 VITE_API_BASE。', 0, '', false, { kind: 'configuration' });
        return [explicit];
    }
    if (!isLoopbackHost(hostname)) return ['/api'];
    const candidates = [];
    const append = (base) => {
        const value = trimSlash(base);
        const parsed = value && parse(value);
        // 本机兼容探测仅限同源或回环目标，旧缓存不能将请求静默引向任意外部域名。
        if (parsed && (parsed.origin === origin || isLoopbackHost(parsed.hostname)) && !candidates.includes(value)) candidates.push(value);
    };
    append(storedBase);
    append('/api');
    if (backend) append(`${trimSlash(backend)}/api`);
    return candidates;
}

export function buildApiUrl(base, path, params = {}, origin) {
    const full = `${base}${path}`;
    const url = /^https?:\/\//i.test(full) ? new URL(full) : new URL(full, origin);
    Object.entries(params || {}).forEach(([key, value]) => {
        if (value !== undefined && value !== null && value !== '') url.searchParams.set(key, value);
    });
    return url;
}

/** Retry-After 支持秒数和 HTTP 日期；未知格式忽略，绝不截短有效服务端等待时间。 */
export function parseRetryAfter(value, now = Date.now()) {
    const text = String(value || '').trim();
    if (!text) return 0;
    if (/^\d+(?:\.\d+)?$/.test(text)) {
        const milliseconds = Number(text) * 1000;
        return Number.isFinite(milliseconds) ? milliseconds : 0;
    }
    const timestamp = Date.parse(text);
    return Number.isFinite(timestamp) ? Math.max(0, timestamp - now) : 0;
}

function cancelled(url, writing = false, dispatched = false) {
    return new ApiError(-1, writing && dispatched ? `已停止等待。${UNKNOWN_WRITE}` : '请求已取消。',
        0, String(url || ''), false, { kind: 'aborted', resultUnknown: writing && dispatched });
}

/** 可取消的退避等待；移除监听和定时器，避免页面退出后仍继续下一次请求。 */
export function waitForRetry(milliseconds, signal) {
    return new Promise((resolve, reject) => {
        if (signal?.aborted) { reject(cancelled()); return; }
        const cleanup = () => signal?.removeEventListener('abort', abort);
        const timer = setTimeout(() => { cleanup(); resolve(); }, milliseconds);
        function abort() { clearTimeout(timer); cleanup(); reject(cancelled()); }
        signal?.addEventListener('abort', abort, { once: true });
    });
}

function positiveDuration(value, fallback, label) {
    if (value === undefined) return fallback;
    if (!Number.isFinite(value) || value <= 0 || value > 2_147_483_647) {
        throw new ApiError(-1, `${label}必须是有效的正毫秒数。`, 0, '', false, { kind: 'configuration' });
    }
    return value;
}

/**
 * 单次尝试的超时覆盖 fetch 和响应体读取；AbortController 尽量终止网络传输，
 * Promise.race 也保证不遵守 signal 的替身或异常实现不会无限占住页面状态。
 */
async function requestOnce(url, init, { timeoutMs, signal, fetchImpl, now, writing, allowLocalFallback }) {
    if (signal?.aborted) throw cancelled(url);
    const controller = new AbortController();
    let timeout;
    let abortListener;
    const cancellation = new Promise((_, reject) => {
        abortListener = () => {
            reject(cancelled(url, writing, true));
            controller.abort();
        };
        signal?.addEventListener('abort', abortListener, { once: true });
        timeout = setTimeout(() => {
            reject(new ApiError(-1, writing ? `等待接口超时。${UNKNOWN_WRITE}` : '读取接口超时，请检查网络及服务状态。',
                0, url.toString(), !writing, { kind: 'timeout', resultUnknown: writing }));
            controller.abort();
        }, timeoutMs);
    });
    const operation = (async () => {
        let response;
        let text;
        try {
            response = await fetchImpl(url, { ...init, signal: controller.signal });
            text = await response.text();
        } catch (error) {
            if (signal?.aborted) throw cancelled(url, writing, true);
            throw new ApiError(-1, writing ? `接口连接中断。${UNKNOWN_WRITE}` : '无法连接接口，请确认网络与后端服务可用。',
                0, url.toString(), !writing, { kind: 'network', resultUnknown: writing });
        }
        let body;
        try { body = JSON.parse(text); } catch { body = null; }
        const envelope = body && typeof body === 'object' && !Array.isArray(body) && Object.hasOwn(body, 'code');
        if (envelope && body.code !== 0) {
            // 后端已经明确返回业务失败时立即结束，不把业务失败包装成网关瞬态错误重发。
            throw new ApiError(body.code, body.message || `HTTP ${response.status}`, response.status,
                url.toString(), false, { kind: 'business' });
        }
        if (!response.ok) {
            const resultUnknown = writing && (response.status === 408 || response.status >= 500);
            throw new ApiError(response.status, `接口返回 HTTP ${response.status}${resultUnknown ? `。${UNKNOWN_WRITE}` : ''}`,
                response.status, url.toString(), !writing && TRANSIENT_HTTP.has(response.status),
                { kind: 'http', retryAfterMs: parseRetryAfter(response.headers.get('Retry-After'), now()),
                    resultUnknown });
        }
        if (!envelope) {
            // 只有本机静态预览误返回 HTML 的 GET 才允许有限换址；公共域名和写操作立即失败。
            throw new ApiError(-1, writing ? `接口未返回可确认的结果。${UNKNOWN_WRITE}` : `接口响应格式错误（HTTP ${response.status}），请检查 API 基址与代理配置。`,
                response.status, url.toString(), !writing && allowLocalFallback,
                { kind: 'protocol', resultUnknown: writing });
        }
        return body.data;
    })();
    try { return await Promise.race([operation, cancellation]); }
    finally { clearTimeout(timeout); signal?.removeEventListener('abort', abortListener); }
}

/**
 * 所有地址共用同一尝试次数和时间预算，防止“每个地址再重试三次”放大流量。
 * 写操作仅使用首选地址；失败不自动回退，不把超时当作后台任务已取消。
 */
export async function performJsonRequest({ path, params = {}, init = {}, options = {}, bases, origin,
    allowLocalFallback = false, onBaseSuccess = () => {}, fetchImpl = globalThis.fetch,
    now = Date.now, random = Math.random, wait = waitForRetry }) {
    const method = String(init.method || 'GET').toUpperCase();
    const writing = method !== 'GET';
    const timeoutMs = positiveDuration(options.timeoutMs, writing ? REQUEST_DEFAULTS.writeTimeoutMs : REQUEST_DEFAULTS.readTimeoutMs, '单次超时');
    const budgetMs = positiveDuration(options.totalTimeoutMs,
        writing ? timeoutMs : Math.max(timeoutMs, REQUEST_DEFAULTS.readBudgetMs), '总等待预算');
    const requestedAttempts = options.maxAttempts ?? REQUEST_DEFAULTS.maxReadAttempts;
    if (!Number.isInteger(requestedAttempts) || requestedAttempts < 1) {
        throw new ApiError(-1, '尝试次数必须是正整数。', 0, '', false, { kind: 'configuration' });
    }
    const attempts = writing ? 1 : Math.min(REQUEST_DEFAULTS.maxReadAttempts, requestedAttempts);
    const signal = options.signal;
    const started = now();
    let baseIndex = 0;
    let lastError;
    const headers = new Headers(init.headers || {});
    headers.set('Accept', 'application/json');
    new Headers(options.headers || {}).forEach((value, key) => headers.set(key, value));
    for (let attempt = 1; attempt <= attempts; attempt++) {
        const base = bases[baseIndex] || '/api';
        const url = buildApiUrl(base, path, params, origin);
        if (signal?.aborted) throw cancelled(url);
        const remaining = budgetMs - (now() - started);
        if (remaining <= 0) break;
        try {
            const data = await requestOnce(url, { ...init, method, headers }, {
                timeoutMs: Math.min(timeoutMs, remaining), signal, fetchImpl, now, writing, allowLocalFallback,
            });
            onBaseSuccess(base);
            return data;
        } catch (error) {
            lastError = error instanceof ApiError ? error : new ApiError(-1, '请求处理异常。', 0, url.toString(), false, { kind: 'unexpected' });
            lastError.attempts = attempt;
            lastError.elapsedMs = Math.max(0, now() - started);
            if (!lastError.retryable || writing || attempt === attempts) break;
            // HTML 误路由只允许换到下一个候选地址；同一静态页面不会因重复读取变成 API。
            if (lastError.kind === 'protocol' && baseIndex >= bases.length - 1) break;
            const delay = Math.max(lastError.retryAfterMs || 0,
                REQUEST_DEFAULTS.backoffMs * (2 ** (attempt - 1)) * (0.8 + Math.min(1, Math.max(0, random())) * 0.4));
            if (delay >= budgetMs - (now() - started)) {
                lastError.message += ' 服务端等待时间或重试退避已超出本次总预算，请稍后手动重试。';
                lastError.budgetExhausted = true;
                break;
            }
            if (allowLocalFallback && ['network', 'timeout', 'protocol'].includes(lastError.kind) && baseIndex < bases.length - 1) baseIndex++;
            try { await wait(delay, signal); }
            catch { throw cancelled(url); }
        }
    }
    if (!lastError) lastError = new ApiError(-1, '请求已超出总等待预算。', 0, '', false, { kind: 'budget', attempts: 0 });
    lastError.elapsedMs = Math.max(0, now() - started);
    if (lastError.elapsedMs >= budgetMs) lastError.budgetExhausted = true;
    if (!writing && lastError.retryable) lastError.message += `（已尝试 ${lastError.attempts} 次）`;
    throw lastError;
}
