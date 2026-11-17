/**
 * 数据抓取页专用的只读任务观察器。
 * 不依赖 React，不创建采集任务；列表、详情和手动刷新共用串行队列，
 * 请求结束后才安排下一次轮询，避免慢请求被固定间隔反复作废。
 */
export const isCrawlerRunning = (task) => task?.status === 'running';
export const isCrawlerFinished = (task) => ['done', 'failed'].includes(task?.status);

/** 首次进入优先恢复后台运行任务；本次手选历史之后，不再自动改变选择。 */
export function selectCrawlerTask(records, selectedId, manuallySelected = false) {
    if (manuallySelected) return selectedId;
    const running = records.filter(isCrawlerRunning);
    return String(running.find((task) => String(task.id) === String(selectedId))?.id
        ?? running[0]?.id ?? selectedId ?? '');
}

/** 旧任务的详情不能充当新任务的结果，尤其不能借用旧终态停止查询。 */
export function matchingCrawlerDetail(detail, selectedId) {
    return detail && String(detail.id) === String(selectedId) ? detail : null;
}

export function createCrawlerMonitor({
    readTasks, readTask, getSelectedId, restoreSelection = () => {},
    onTasks = () => {}, onDetail = () => {}, intervalMs = 3000,
    schedule = setTimeout, cancel = clearTimeout, now = Date.now,
}) {
    let stopped = false;
    let running = false;
    let timer;
    let requested = 0;
    let forceDetail = false;
    let waiters = [];
    let tasks = { data: [], loading: false, error: null, updatedAt: 0 };
    let detail = { id: '', data: null, loading: false, error: null, updatedAt: 0 };
    const publishTasks = (value) => { tasks = value; if (!stopped) onTasks(value); };
    const publishDetail = (value) => { detail = value; if (!stopped) onDetail(value); };

    async function queryTasks() {
        publishTasks({ ...tasks, loading: true, error: null });
        try {
            const records = await readTasks();
            if (stopped) return null;
            if (!Array.isArray(records)) throw new Error('抓取任务列表响应不完整');
            publishTasks({ data: records, loading: false, error: null, updatedAt: now() });
            return records;
        } catch (error) {
            if (!stopped) publishTasks({ ...tasks, loading: false, error: error.message || '任务列表读取失败' });
            return null;
        }
    }

    async function query(force) {
        const records = await queryTasks();
        if (stopped) return;
        // 使用请求返回时的最新选择，防止列表晚到后抢走用户刚选中的历史任务。
        if (records) restoreSelection(records);
        const id = String(getSelectedId() || '');
        if (!id) return;
        const previous = matchingCrawlerDetail(detail.data, id);
        if (!force && previous && isCrawlerFinished(previous) && !detail.error) return;
        publishDetail({ id, data: previous, loading: true, error: null,
            updatedAt: previous ? detail.updatedAt : 0 });
        try {
            const result = await readTask(id);
            // 切换任务或卸载后，只丢弃本次页面响应，不取消后台采集。
            if (stopped || String(getSelectedId()) !== id) return;
            if (!matchingCrawlerDetail(result, id)) throw new Error('抓取任务详情响应不匹配');
            publishDetail({ id, data: result, loading: false, error: null, updatedAt: now() });
            if (isCrawlerFinished(result) && !isCrawlerFinished(previous)) {
                // 详情可能比本轮列表先看见终态，再读取一次列表，消除残留“运行中”。
                await queryTasks();
            }
        } catch (error) {
            if (!stopped && String(getSelectedId()) === id) {
                publishDetail({ ...detail, loading: false, error: error.message || '任务详情读取失败' });
            }
        }
    }

    async function drain() {
        if (stopped || running) return;
        running = true;
        const version = requested;
        const force = forceDetail;
        forceDetail = false;
        try {
            await query(force);
        } finally {
            running = false;
            const completed = waiters.filter((item) => item.version <= version);
            waiters = waiters.filter((item) => item.version > version);
            completed.forEach((item) => item.resolve({ tasks, detail, stopped }));
            if (stopped) return;
            // 在途期间的同 ID 刷新或新选择合并为下一轮，不能被旧请求的完成信号吞掉。
            if (requested > version) void drain();
            else timer = schedule(() => { requested += 1; void drain(); }, intervalMs);
        }
    }

    return {
        /** 手动刷新也进入同一队列；返回的是至少本次请求之后一轮查询的结果。 */
        refresh() {
            if (stopped) return Promise.resolve({ tasks, detail, stopped });
            cancel(timer);
            requested += 1;
            forceDetail = true;
            const promise = new Promise((resolve) => waiters.push({ version: requested, resolve }));
            void drain();
            return promise;
        },
        /** 每个挂载周期拥有独立观察器，旧请求不能在 StrictMode 重新挂载后写回状态。 */
        stop() {
            stopped = true;
            cancel(timer);
            waiters.forEach((item) => item.resolve({ tasks, detail, stopped: true }));
            waiters = [];
        },
    };
}
