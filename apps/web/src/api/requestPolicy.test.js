import test from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, REQUEST_DEFAULTS, buildApiUrl, parseRetryAfter, performJsonRequest,
    resolveApiBaseCandidates, waitForRetry } from './requestPolicy.js';

const origin = 'http://127.0.0.1:5173';
const ok = (data = { value: 1 }) => new Response(JSON.stringify({ code: 0, data }), { status: 200 });

/** 离线替身只记录请求，不启动浏览器、连接服务器或提交真实任务。 */
function harness(sequence, overrides = {}) {
    const calls = [], waits = [], remembered = [];
    let time = 0;
    const config = {
        path: '/test', origin, bases: ['/api'], random: () => 0.5, now: () => time,
        wait: async (duration) => { waits.push(duration); time += duration; },
        onBaseSuccess: (base) => remembered.push(base),
        fetchImpl: async (url, init) => {
            calls.push({ url: String(url), init });
            const next = sequence.shift();
            if (next instanceof Error) throw next;
            return typeof next === 'function' ? next(url, init) : next;
        },
        ...overrides,
    };
    return { run: () => performJsonRequest(config), calls, waits, remembered };
}

test('显式 API 基址是唯一来源，公网页面不回退缓存或 localhost', () => {
    assert.deepEqual(resolveApiBaseCandidates({ configured: 'https://service.example/api/',
        storedBase: 'http://localhost:8080/api', backend: 'http://127.0.0.1:8080', origin, hostname: '127.0.0.1' }),
    ['https://service.example/api']);
    assert.deepEqual(resolveApiBaseCandidates({ storedBase: 'http://localhost:8080/api',
        backend: 'http://127.0.0.1:8080', origin: 'https://site.example', hostname: 'site.example' }), ['/api']);
    assert.throws(() => resolveApiBaseCandidates({ configured: 'javascript:alert(1)', origin }),
        (error) => error instanceof ApiError && error.kind === 'configuration');
});

test('本机探测保留可信缓存、同源和回环直连，排除任意外部缓存及重复地址', () => {
    assert.deepEqual(resolveApiBaseCandidates({ storedBase: 'http://localhost:8080/api/', backend: 'http://localhost:8080',
        origin, hostname: '127.0.0.1' }), ['http://localhost:8080/api', '/api']);
    assert.deepEqual(resolveApiBaseCandidates({ storedBase: 'https://unrelated.example/api', backend: 'http://localhost:8080',
        origin, hostname: '127.0.0.1' }), ['/api', 'http://localhost:8080/api']);
});

test('查询参数保留数字零、false、已有查询与中文，忽略空值', () => {
    const url = buildApiUrl('/api', '/list?horizon=3', { page: 0, enabled: false, name: '测试', absent: null, empty: '' }, origin);
    assert.equal(url.searchParams.get('horizon'), '3');
    assert.equal(url.searchParams.get('page'), '0');
    assert.equal(url.searchParams.get('enabled'), 'false');
    assert.equal(url.searchParams.get('name'), '测试');
    assert.equal(url.searchParams.has('absent'), false);
    assert.equal(url.searchParams.has('empty'), false);
});

test('GET 网络错误与网关故障有限重试，只有成功地址被记住', async () => {
    const h = harness([new TypeError('network'), new Response('gateway', { status: 503 }), ok({ count: 8 })]);
    assert.deepEqual(await h.run(), { count: 8 });
    assert.equal(h.calls.length, 3);
    assert.deepEqual(h.waits, [400, 800]);
    assert.deepEqual(h.remembered, ['/api']);
    assert.ok(h.calls.every((call) => call.init.headers.get('Accept') === 'application/json'));
});

test('普通 4xx、HTTP 500 与后端业务错误立即失败，不换地址', async () => {
    for (const response of [new Response('missing', { status: 404 }), new Response('bad', { status: 400 }),
        new Response('server', { status: 500 }), new Response(JSON.stringify({ code: 12, message: '参数不满足条件' })),
        new Response(JSON.stringify({ code: 503, message: '后端明确拒绝' }), { status: 503 })]) {
        const h = harness([response, ok()], { bases: ['/api', 'http://localhost:8080/api'], allowLocalFallback: true });
        await assert.rejects(h.run(), (error) => error instanceof ApiError && !error.retryable);
        assert.equal(h.calls.length, 1);
        assert.equal(h.waits.length, 0);
    }
});

test('允许的瞬态 HTTP 状态可重试，其余状态不隐式加入白名单', async () => {
    for (const status of [408, 429, 502, 503, 504]) {
        const h = harness([new Response('temporary', { status }), ok()]);
        await h.run();
        assert.equal(h.calls.length, 2, String(status));
    }
});

test('Retry-After 支持秒数和日期，且不会为了总预算提前重发', async () => {
    assert.equal(parseRetryAfter('2'), 2000);
    assert.equal(parseRetryAfter('Thu, 01 Jan 1970 00:00:08 GMT', 1000), 7000);
    assert.equal(parseRetryAfter('invalid'), 0);
    const normal = harness([new Response('wait', { status: 429, headers: { 'Retry-After': '3' } }), ok()]);
    await normal.run();
    assert.deepEqual(normal.waits, [3000]);
    const tooLong = harness([new Response('wait', { status: 429, headers: { 'Retry-After': '3600' } }), ok()]);
    await assert.rejects(tooLong.run(), (error) => error.status === 429 && error.budgetExhausted === true);
    assert.equal(tooLong.calls.length, 1);
    assert.equal(tooLong.waits.length, 0);
});

test('所有候选地址共用三次上限，不能按地址倍增重试', async () => {
    const h = harness(Array.from({ length: 10 }, () => new TypeError('offline')), {
        bases: ['/api', 'http://localhost:8080/api', 'http://127.0.0.1:8080/api'],
        allowLocalFallback: true, options: { maxAttempts: 99 },
    });
    await assert.rejects(h.run(), (error) => error.attempts === 3 && error.elapsedMs === 1200);
    assert.equal(h.calls.length, 3);
    assert.equal(new Set(h.calls.map((call) => call.url)).size, 3);
});

test('只有本机 GET 可将 HTML 误路由有限回退，显式或公网基址立即报告格式错误', async () => {
    const local = harness([new Response('<html>preview</html>'), ok()], {
        bases: ['/api', 'http://localhost:8080/api'], allowLocalFallback: true,
    });
    await local.run();
    assert.equal(local.calls.length, 2);
    const explicit = harness([new Response('<html>wrong</html>'), ok()]);
    await assert.rejects(explicit.run(), (error) => error.kind === 'protocol' && !error.retryable);
    assert.equal(explicit.calls.length, 1);
});

test('POST、PUT、DELETE 连接失败或网关超时只发送一次并提示结果未知', async () => {
    for (const method of ['POST', 'PUT', 'DELETE']) {
        for (const response of [new TypeError('offline'), new Response('gateway', { status: 504 })]) {
            const h = harness([response, ok()], { init: { method, body: '{}' },
                bases: ['/api', 'http://localhost:8080/api'], allowLocalFallback: true });
            await assert.rejects(h.run(), (error) => error.resultUnknown === true && !error.retryable && /先查询/.test(error.message));
            assert.equal(h.calls.length, 1);
            assert.equal(h.waits.length, 0);
        }
    }
    assert.equal(REQUEST_DEFAULTS.writeTimeoutMs, 15 * 60_000);
    assert.ok(REQUEST_DEFAULTS.writeTimeoutMs > REQUEST_DEFAULTS.readTimeoutMs);
});

test('响应体读取也受超时保护，写请求超时不会自动补发', async () => {
    const h = harness([() => ({ ok: true, status: 200, text: () => new Promise(() => {}) })],
        { init: { method: 'POST' }, options: { timeoutMs: 5 } });
    await assert.rejects(h.run(), (error) => error.kind === 'timeout' && error.resultUnknown === true);
    assert.equal(h.calls.length, 1);
    assert.equal(h.calls[0].init.signal.aborted, true);
});

test('GET 超时可以有界恢复，最后一个错误协议地址不原地重复探测', async () => {
    const slow = harness([() => new Promise(() => {}), ok()], { options: { timeoutMs: 5 } });
    await slow.run();
    assert.equal(slow.calls.length, 2);
    assert.equal(slow.calls[0].init.signal.aborted, true);
    const wrongProtocol = harness([new Response('<html>preview</html>'), ok()], { allowLocalFallback: true });
    await assert.rejects(wrongProtocol.run(), (error) => error.kind === 'protocol');
    assert.equal(wrongProtocol.calls.length, 1);
});

test('预先取消不发请求，写请求在途取消只停止等待并保留结果未知提示', async () => {
    const before = new AbortController();
    before.abort();
    const h = harness([ok()], { options: { signal: before.signal } });
    await assert.rejects(h.run(), (error) => error.kind === 'aborted' && !error.resultUnknown);
    assert.equal(h.calls.length, 0);
    const during = new AbortController();
    const write = harness([() => { queueMicrotask(() => during.abort()); return new Promise(() => {}); }],
        { init: { method: 'POST' }, options: { signal: during.signal } });
    await assert.rejects(write.run(), (error) => error.kind === 'aborted' && error.resultUnknown);
    assert.equal(write.calls.length, 1);
});

test('退避期间取消会阻止后续请求，总等待预算不足时也不会继续尝试', async () => {
    const controller = new AbortController();
    const h = harness([new TypeError('offline'), ok()], { options: { signal: controller.signal },
        wait: async () => { controller.abort(); await waitForRetry(10, controller.signal); } });
    await assert.rejects(h.run(), (error) => error.kind === 'aborted');
    assert.equal(h.calls.length, 1);
    const budget = harness([new TypeError('offline'), ok()], { options: { totalTimeoutMs: 100 } });
    await assert.rejects(budget.run(), (error) => error.budgetExhausted);
    assert.equal(budget.calls.length, 1);
});

test('第三参数可扩展超时、取消和头字段，不改变原请求方法与 JSON 内容', async () => {
    const h = harness([ok(null)], { init: { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: '{"enabled":false}' },
        options: { timeoutMs: 1000, headers: { 'X-Test': 'offline' } } });
    assert.equal(await h.run(), null);
    assert.equal(h.calls[0].init.method, 'PUT');
    assert.equal(h.calls[0].init.body, '{"enabled":false}');
    assert.equal(h.calls[0].init.headers.get('Content-Type'), 'application/json');
    assert.equal(h.calls[0].init.headers.get('X-Test'), 'offline');
});
