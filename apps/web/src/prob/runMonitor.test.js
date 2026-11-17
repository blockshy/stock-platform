/**
 * 概率任务观察的离线回归验证。
 * 使用虚拟时钟与伪造的只读响应，不连接后端、不启动训练，也不依赖真实时间等待。
 * 运行方式：node --test src/prob/runMonitor.test.js
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { isRunActive, isRunFinished, readRunRecords, runElapsedText, runViewState,
    selectedRunDetail, selectRunToRestore, startRunPolling } from './runMonitor.js';

const runningDaily = { run_id: 3, run_type: 'DAILY', status: 'RUNNING' };
const runningChild = { run_id: 5, run_type: 'WALKFORWARD', status: 'RUNNING' };
const history = { run_id: 1, run_type: 'DAILY', status: 'SUCCESS' };

/** 可手动推进一个定时回调的时钟，用于验证查询失败后确实还有下一次查询。 */
function fakeClock() {
    let nextId = 0;
    const pending = new Map();
    return {
        schedule(callback) {
            const id = ++nextId;
            pending.set(id, callback);
            return id;
        },
        cancel(id) { pending.delete(id); },
        count() { return pending.size; },
        async tick() {
            const next = pending.entries().next().value;
            assert.ok(next, '应存在下一次轮询');
            pending.delete(next[0]);
            await next[1]();
        },
    };
}

test('重新进入默认恢复 DAILY，手动选择历史后轮询不抢回选择', () => {
    const records = [runningChild, runningDaily, history];
    assert.equal(selectRunToRestore(records, null), 3);
    assert.equal(selectRunToRestore(records, 1), 1);
    assert.equal(selectRunToRestore([runningChild, history], null), 5);
    assert.equal(selectRunToRestore([history], null), null);
});

test('切换任务不会借用上一个任务的终态，空详情与查询错误不会显示已结束', () => {
    const detail = selectedRunDetail(history, 3);
    assert.equal(detail, null);
    assert.equal(runViewState(detail, { loading: true }), 'loading');
    assert.equal(runViewState(detail), 'unknown');
    assert.equal(runViewState(runningDaily), 'running');
    assert.equal(runViewState(runningDaily, { error: '网络暂时中断' }), 'error');
    assert.equal(runViewState({ ...runningDaily, status: 'SUCCESS' }), 'finished');
});

test('扫描全部记录页，发现较早页面中的运行任务；翻历史页不能绕过运行检查', async () => {
    const requested = [];
    const records = await readRunRecords(async ({ page, size }) => {
        requested.push({ page, size });
        return page === 1
            ? { pages: 2, items: [{ run_id: 10, status: 'SUCCESS' }] }
            : { pages: 2, items: [runningDaily, history] };
    });
    assert.deepEqual(requested, [{ page: 1, size: 100 }, { page: 2, size: 100 }]);
    assert.ok(records.some(isRunActive), '提交前应检测到旧编号中仍运行的任务');
    assert.equal(selectRunToRestore(records, null), 3);
});

test('任一记录页读取失败都会使核验失败，不把部分记录当成后台空闲', async () => {
    await assert.rejects(() => readRunRecords(async ({ page }) => {
        if (page === 2) throw new Error('第二页网络中断');
        return { pages: 2, items: [history] };
    }), /第二页网络中断/);
});

test('运行中的详情轮询经历一次网络失败后仍恢复查询，进入终态后停止', async () => {
    const clock = fakeClock();
    let run = { ...runningDaily };
    let calls = 0;
    const stop = startRunPolling(async () => {
        calls += 1;
        if (calls === 1) throw new Error('模拟一次网络失败');
        run = { ...run, status: 'SUCCESS' };
    }, { ...clock, shouldContinue: () => !isRunFinished(run) });

    await clock.tick();
    assert.equal(calls, 1);
    assert.equal(clock.count(), 1, '失败后必须安排重试');
    await clock.tick();
    assert.equal(calls, 2);
    assert.equal(run.status, 'SUCCESS');
    assert.equal(clock.count(), 0, '终态不再循环请求');
    stop();
});

test('运行记录轮询在网络失败后继续，首次查询未结束时不并发，卸载后清理计时器', async () => {
    const clock = fakeClock();
    let loading = true;
    let calls = 0;
    const stop = startRunPolling(async () => {
        calls += 1;
        if (calls === 1) throw new Error('模拟列表请求失败');
    }, { ...clock, isLoading: () => loading, intervalMs: 5000 });

    await clock.tick();
    assert.equal(calls, 0, '已有查询时不新增并发请求');
    loading = false;
    await clock.tick();
    await clock.tick();
    assert.equal(calls, 2, '失败后恢复列表查询');
    stop();
    assert.equal(clock.count(), 0);
});

test('耗时只取真实开始与结束时间，缺少时间时不生成虚假计时', () => {
    const start = '2026-11-17T12:00:00+08:00';
    const now = Date.parse('2026-11-17T12:02:03+08:00');
    assert.equal(runElapsedText({ started_at: start }, now), '2 分 3 秒');
    assert.equal(runElapsedText({ started_at: start, finished_at: '2026-11-17T12:01:00+08:00' }, now), '1 分 0 秒');
    assert.equal(runElapsedText({}, now), '—');
});
