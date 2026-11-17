/**
 * 采集监控器的离线回归测试。
 * 所有列表与详情查询均为内存伪造响应，使用可控 Promise 和假时钟验证竞态；
 * 不连接后端、不提交采集任务，也不等待真实时间。运行：
 * node --test src/pages/crawlerMonitor.test.js
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createCrawlerMonitor, matchingCrawlerDetail, selectCrawlerTask } from './crawlerMonitor.js';

const running = { id: 5, status: 'running', successCount: 10 };
const history = { id: 1, status: 'done', successCount: 5 };

/** 暴露完成与失败控制权，模拟列表或详情长时间未返回，而不使用真实定时器。 */
function deferred() {
    let resolve;
    let reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}

/** 清空本测试产生的有限微任务链；尚未手动完成的 deferred 不会因此结束。 */
async function flush() {
    for (let index = 0; index < 20; index += 1) await Promise.resolve();
}

/** 每次只推进一个虚拟轮询周期，记录间隔以验证“请求完成后再等待”。 */
function fakeClock() {
    let sequence = 0;
    const pending = new Map();
    return {
        schedule(callback, delay) {
            const id = ++sequence;
            pending.set(id, { callback, delay });
            return id;
        },
        cancel(id) { pending.delete(id); },
        count() { return pending.size; },
        delays() { return [...pending.values()].map((item) => item.delay); },
        async tick() {
            const entry = pending.entries().next().value;
            assert.ok(entry, '应存在下一次只读轮询');
            pending.delete(entry[0]);
            entry[1].callback();
            await flush();
        },
    };
}

/** 模拟 React 引用保存的最新选择，手选状态在列表返回时即时读取，避免闭包旧值。 */
function harness({ readTasks = async () => [running, history],
    readTask = async (id) => String(id) === '5' ? running : history,
    selectedId = '1', manuallySelected = false } = {}) {
    const clock = fakeClock();
    const taskStates = [];
    const detailStates = [];
    const monitor = createCrawlerMonitor({
        readTasks, readTask, ...clock, intervalMs: 3000, now: () => 123,
        getSelectedId: () => selectedId,
        restoreSelection: (records) => {
            selectedId = selectCrawlerTask(records, selectedId, manuallySelected);
        },
        onTasks: (value) => taskStates.push(value),
        onDetail: (value) => detailStates.push(value),
    });
    return { monitor, clock, taskStates, detailStates,
        selected: () => selectedId,
        select(id) { selectedId = String(id); manuallySelected = true; },
    };
}

test('首次恢复后台运行任务，覆盖持久化旧终态；明确手选历史时保留选择', async (t) => {
    const auto = harness();
    t.after(() => auto.monitor.stop());
    await auto.monitor.refresh();
    assert.equal(auto.selected(), '5');
    assert.equal(auto.detailStates.at(-1).data.id, 5);

    const manual = harness({ manuallySelected: true });
    t.after(() => manual.monitor.stop());
    await manual.monitor.refresh();
    assert.equal(manual.selected(), '1');
    assert.equal(manual.detailStates.at(-1).data.id, 1);
    assert.equal(matchingCrawlerDetail(history, '5'), null);
    assert.equal(matchingCrawlerDetail(running, '5'), running);
});

test('同一终态编号手动刷新仍查询详情，不依赖编号发生变化', async (t) => {
    let detailCalls = 0;
    const state = harness({ manuallySelected: true,
        readTask: async () => { detailCalls += 1; return history; } });
    t.after(() => state.monitor.stop());
    await state.monitor.refresh();
    await state.monitor.refresh();
    assert.equal(detailCalls, 2);
    assert.equal(state.clock.count(), 1, '多次手动刷新不能遗留重复定时器');
});

test('慢请求在途时不安排轮询，手动刷新排队且只在查询结束后等待下一周期', async (t) => {
    const firstList = deferred();
    let listCalls = 0;
    let detailCalls = 0;
    let active = 0;
    let maximumActive = 0;
    const state = harness({ selectedId: '5',
        readTasks: async () => {
            listCalls += 1;
            active += 1;
            maximumActive = Math.max(maximumActive, active);
            try { return listCalls === 1 ? await firstList.promise : [running]; }
            finally { active -= 1; }
        },
        readTask: async () => {
            detailCalls += 1;
            active += 1;
            maximumActive = Math.max(maximumActive, active);
            await Promise.resolve();
            active -= 1;
            return running;
        },
    });
    t.after(() => state.monitor.stop());
    const initial = state.monitor.refresh();
    const manual = state.monitor.refresh();
    let manualFinished = false;
    manual.then(() => { manualFinished = true; });
    await flush();
    assert.equal(listCalls, 1);
    assert.equal(state.clock.count(), 0);
    assert.equal(manualFinished, false);
    firstList.resolve([running]);
    await Promise.all([initial, manual]);
    assert.equal(listCalls, 2);
    assert.equal(detailCalls, 2);
    assert.equal(maximumActive, 1);
    assert.deepEqual(state.clock.delays(), [3000]);
});

test('首次列表读取失败后自动恢复，不把失败当空闲终态', async (t) => {
    let listCalls = 0;
    const state = harness({ selectedId: '', readTasks: async () => {
        if (++listCalls === 1) throw new Error('模拟首次列表网络失败');
        return [running];
    } });
    t.after(() => state.monitor.stop());
    await state.monitor.refresh();
    assert.match(state.taskStates.at(-1).error, /列表网络失败/);
    assert.equal(state.clock.count(), 1);
    await state.clock.tick();
    assert.equal(state.taskStates.at(-1).error, null);
    assert.equal(state.selected(), '5');
    assert.equal(state.detailStates.at(-1).data.status, 'running');
});

test('首次详情失败后继续轮询，同编号随后返回真实进度', async (t) => {
    let calls = 0;
    const state = harness({ selectedId: '5', readTask: async () => {
        if (++calls === 1) throw new Error('模拟首次详情网络失败');
        return { ...running, successCount: 11 };
    } });
    t.after(() => state.monitor.stop());
    await state.monitor.refresh();
    assert.match(state.detailStates.at(-1).error, /详情网络失败/);
    assert.equal(state.detailStates.at(-1).data, null);
    await state.clock.tick();
    assert.equal(calls, 2);
    assert.equal(state.detailStates.at(-1).error, null);
    assert.equal(state.detailStates.at(-1).data.successCount, 11);
});

test('A 详情晚于 B 选择返回时丢弃旧响应，并保留 B 的待刷新请求', async (t) => {
    const oldDetail = deferred();
    const newDetail = deferred();
    const requestedIds = [];
    const state = harness({ selectedId: '5', readTask: (id) => {
        requestedIds.push(id);
        return id === '5' ? oldDetail.promise : newDetail.promise;
    } });
    t.after(() => state.monitor.stop());
    const first = state.monitor.refresh();
    await flush();
    state.select('1');
    const next = state.monitor.refresh();
    oldDetail.resolve({ ...running, status: 'done' });
    await first;
    await flush();
    assert.deepEqual(requestedIds, ['5', '1']);
    assert.ok(state.detailStates.every((value) => value.data?.id !== 5));
    assert.equal(state.detailStates.at(-1).id, '1');
    assert.equal(state.detailStates.at(-1).data, null);
    newDetail.resolve(history);
    await next;
    assert.equal(state.selected(), '1');
    assert.equal(state.detailStates.at(-1).data.id, 1);
});

test('列表返回前手选历史，恢复逻辑读取最新选择且不抢回运行任务', async (t) => {
    const list = deferred();
    const state = harness({ readTasks: () => list.promise });
    t.after(() => state.monitor.stop());
    const first = state.monitor.refresh();
    state.select('1');
    list.resolve([running, history]);
    await first;
    assert.equal(state.selected(), '1');
    assert.equal(state.detailStates.at(-1).data.id, 1);
});

test('详情进入终态立即补列表，后续自动周期仅更新列表不重查终态详情', async (t) => {
    let listCalls = 0;
    let detailCalls = 0;
    const done = { ...running, status: 'done', successCount: 20 };
    const state = harness({ selectedId: '5',
        readTasks: async () => { listCalls += 1; return [listCalls < 3 ? running : done]; },
        readTask: async () => ++detailCalls === 1 ? running : done,
    });
    t.after(() => state.monitor.stop());
    await state.monitor.refresh();
    assert.equal(listCalls, 1);
    await state.clock.tick();
    assert.equal(detailCalls, 2);
    assert.equal(listCalls, 3, '发现终态后必须补一次列表查询');
    assert.equal(state.taskStates.at(-1).data[0].status, 'done');
    await state.clock.tick();
    assert.equal(listCalls, 4);
    assert.equal(detailCalls, 2, '已确认终态的详情不应被自动轮询重复读取');
});

test('停止旧生命周期后迟到响应不写页面、不恢复定时器，也不影响新观察器', async () => {
    const oldResponse = deferred();
    const old = harness({ readTasks: () => oldResponse.promise });
    const pending = old.monitor.refresh();
    const writesBeforeStop = old.taskStates.length + old.detailStates.length;
    old.monitor.stop();
    assert.equal((await pending).stopped, true);
    const current = harness({ selectedId: '5' });
    try {
        await current.monitor.refresh();
        oldResponse.resolve([history]);
        await flush();
        assert.equal(old.taskStates.length + old.detailStates.length, writesBeforeStop);
        assert.equal(old.clock.count(), 0);
        assert.equal(current.detailStates.at(-1).data.id, 5);
        assert.equal(current.clock.count(), 1);
    } finally {
        current.monitor.stop();
    }
});
