/**
 * 概率任务页的状态与轮询辅助函数。
 * 不依赖 React、不发起计算任务；仅由调用方传入只读查询，便于离线验证恢复与重试行为。
 */
const ACTIVE_STATUSES = new Set(['RUNNING', 'PENDING']);
const FINISHED_STATUSES = new Set(['SUCCESS', 'FAILED', 'CANCELLED', 'CANCELED', 'SETTLED', 'SKIPPED']);

export const isRunActive = (run) => ACTIVE_STATUSES.has(run?.status);
export const isRunFinished = (run) => FINISHED_STATUSES.has(run?.status);

/** 未选任务时优先恢复运行中的 DAILY；已有选择（包括历史任务）绝不被轮询抢走。 */
export function selectRunToRestore(records, selectedId) {
    if (selectedId !== null && selectedId !== undefined) return selectedId;
    const active = records.filter(isRunActive);
    const next = active.find((run) => run.run_type === 'DAILY') || active[0];
    return next?.run_id ?? null;
}

/** 过滤 useApi 切换参数时暂留的旧详情，避免把上一任务的状态显示到新编号下。 */
export function selectedRunDetail(detail, selectedId) {
    return detail && String(detail.run_id) === String(selectedId) ? detail : null;
}

/** 网络失败、尚未读取和真实终态分别处理；缺少详情不能推断为“任务已结束”。 */
export function runViewState(detail, { loading = false, error = null } = {}) {
    if (error) return 'error';
    if (!detail) return loading ? 'loading' : 'unknown';
    if (isRunActive(detail)) return 'running';
    if (isRunFinished(detail)) return 'finished';
    return 'unknown';
}

/**
 * 运行接口没有状态筛选，因此逐页读取所有记录，再由页面分页展示。
 * 这样用户查看历史页时，也能发现其它页正在执行的任务；任何一页读取失败都向上抛错，
 * 不能把不完整的列表当成“没有任务运行”，尤其用于提交前的只读核验。
 */
export async function readRunRecords(fetchPage) {
    const records = new Map();
    let pages = 1;
    for (let page = 1; page <= pages; page += 1) {
        const response = await fetchPage({ page, size: 100 });
        if (Array.isArray(response)) return response;
        if (!response || !Array.isArray(response.items) || !Number.isFinite(Number(response.pages))) {
            throw new Error('运行记录响应不完整，暂时无法确认后台任务状态');
        }
        pages = Math.max(1, Number(response.pages));
        response.items.forEach((run) => records.set(String(run.run_id), run));
    }
    return [...records.values()].sort((a, b) => Number(b.run_id) - Number(a.run_id));
}

/**
 * 串行只读轮询：每次查询完成后再等待下一周期，失败后仍安排重试。
 * shouldContinue 可在任务进入终态时停止；isLoading 避免与手动刷新或首次查询重叠。
 * 注入时钟仅用于离线验证，生产环境默认使用浏览器定时器；取消不会终止后端任务。
 */
export function startRunPolling(refresh, {
    intervalMs = 2500,
    shouldContinue = () => true,
    isLoading = () => false,
    schedule = setTimeout,
    cancel = clearTimeout,
} = {}) {
    let stopped = false;
    let timer;
    const arm = () => {
        if (!stopped) timer = schedule(tick, intervalMs);
    };
    const tick = async () => {
        if (stopped || !shouldContinue()) return;
        try {
            if (!isLoading()) await refresh();
        } catch {
            // 查询错误由页面状态展示；一次网络失败不应中断后续状态观察。
        } finally {
            if (!stopped && shouldContinue()) arm();
        }
    };
    arm();
    return () => {
        stopped = true;
        cancel(timer);
    };
}

/** 只用接口实际开始/结束时间计算耗时；无时间或时间无效时不编造计时值。 */
export function runElapsedText(run, now = Date.now()) {
    const parse = (value) => value ? Date.parse(String(value).replace(' ', 'T')) : NaN;
    const start = parse(run?.started_at);
    const end = run?.finished_at ? parse(run.finished_at) : now;
    if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return '—';
    const seconds = Math.floor((end - start) / 1000);
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    return `${hours ? `${hours} 小时 ` : ''}${minutes} 分 ${seconds % 60} 秒`;
}
