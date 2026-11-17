import test from 'node:test';
import assert from 'node:assert/strict';
import { apiGet, apiPost, apiPut, apiDelete, apiUrl, apiBaseCandidates } from './client.js';

test('公开 API 调用签名保持兼容，写请求各一次且 URL 导出保持参数', async () => {
    const previousWindow = globalThis.window;
    const previousFetch = globalThis.fetch;
    const calls = [];
    const storage = new Map();
    // 真实模块使用离线替身；测试不会产生 HTTP 流量或连接任何数据库。
    globalThis.window = {
        location: { origin: 'http://127.0.0.1:5173', hostname: '127.0.0.1' },
        localStorage: { getItem: (key) => storage.get(key), setItem: (key, value) => storage.set(key, value), removeItem: (key) => storage.delete(key) },
    };
    globalThis.fetch = async (url, init) => {
        calls.push({ url: String(url), init });
        return new Response(JSON.stringify({ code: 0, data: { accepted: true } }));
    };
    try {
        assert.deepEqual(apiBaseCandidates(), ['/api']);
        await apiGet('/rows', { page: 0 });
        await apiPost('/jobs', { kind: 'SYNTHETIC' });
        await apiPut('/config', { enabled: false });
        await apiDelete('/example');
        assert.deepEqual(calls.map((call) => call.init.method), ['GET', 'POST', 'PUT', 'DELETE']);
        assert.equal(calls[1].init.body, '{"kind":"SYNTHETIC"}');
        assert.equal(calls[2].init.body, '{"enabled":false}');
        assert.equal(calls[3].init.body, undefined);
        assert.equal(apiUrl('/export', { code: '000001' }), 'http://127.0.0.1:5173/api/export?code=000001');
        // 维持旧 async 签名：请求准备阶段出错也应返回拒绝的 Promise，而不是同步抛出。
        const circular = {};
        circular.self = circular;
        const rejected = apiPost('/never-sent', circular);
        assert.ok(rejected instanceof Promise);
        await assert.rejects(rejected, TypeError);
        assert.equal(calls.length, 4);
    } finally {
        globalThis.fetch = previousFetch;
        if (previousWindow === undefined) delete globalThis.window;
        else globalThis.window = previousWindow;
    }
});
