import { useEffect, useMemo, useRef, useState } from 'react';
import { usePersistedState } from '../hooks/usePersistedState.js';
import { dataCrawlerApi } from '../api/dataCrawler.js';
import { createCrawlerMonitor, isCrawlerRunning, matchingCrawlerDetail, selectCrawlerTask } from './crawlerMonitor.js';
import DecimalInput from '../components/DecimalInput.jsx';
import ErrorBanner from '../components/ErrorBanner.jsx';
import Panel from '../components/Panel.jsx';
import StatCard from '../components/StatCard.jsx';

const defaultForm = {
    limit: 0,
    daysBack: 548,
    fqType: 1,
    storeRawPrices: true,
    // 保持原有股票范围；58 开头 ETF 需用户主动填写，不扩展已有任务或保存的表单。
    codePrefixes: '00,30,60',
    requestDelayMillis: 1500,
    refreshStockList: false,
};

const statusText = {
    running: '运行中',
    done: '已完成',
    failed: '失败',
};

const statusClass = (status) => status === 'running' ? 'crawler-running' : status === 'done' ? 'crawler-done' : 'crawler-failed';

const formatTime = (value) => value ? String(value).replace('T', ' ') : '-';
// 此时间仅表示浏览器成功收到查询结果，不代表后台刚处理了一只证券。
const formatQueryTime = (value) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚未成功查询';

export default function DataCrawler() {
    const [form, setForm] = usePersistedState('crawler:form', defaultForm);
    const [activeTaskId, setActiveTaskId] = usePersistedState('crawler:activeTaskId', '');
    const [starting, setStarting] = useState(false);
    const [message, setMessage] = useState('');
    const [actionError, setActionError] = useState('');

    const [tasks, setTasks] = useState({ data: [], loading: true, error: null, updatedAt: 0 });
    const [activeTask, setActiveTask] = useState({ id: '', data: null, loading: false, error: null, updatedAt: 0 });
    const monitorRef = useRef(null);
    const selectedIdRef = useRef(activeTaskId);
    const manuallySelectedRef = useRef(false);
    const startingRef = useRef(false);
    selectedIdRef.current = activeTaskId;

    useEffect(() => {
        // 本页独立串行查询，不改变其它页面使用的全局 useApi。
        // 自动恢复读取返回时的最新选择；本次手选历史后仍更新列表，但不再抢回任务。
        const monitor = createCrawlerMonitor({
            readTasks: () => dataCrawlerApi.recent(20),
            readTask: (id) => dataCrawlerApi.detail(id),
            getSelectedId: () => selectedIdRef.current,
            restoreSelection(records) {
                const next = selectCrawlerTask(records, selectedIdRef.current, manuallySelectedRef.current);
                if (String(next) !== String(selectedIdRef.current)) {
                    selectedIdRef.current = next;
                    setActiveTaskId(next);
                }
            },
            onTasks: setTasks,
            onDetail: setActiveTask,
        });
        monitorRef.current = monitor;
        void monitor.refresh();
        return () => {
            monitor.stop();
            if (monitorRef.current === monitor) monitorRef.current = null;
        };
    }, [setActiveTaskId]);

    const selectedDetail = matchingCrawlerDetail(activeTask.data, activeTaskId);
    const selectedTask = selectedDetail || tasks.data.find((item) => String(item.id) === String(activeTaskId));
    const currentRunning = tasks.data.filter(isCrawlerRunning);
    const selectedQuery = String(activeTask.id) === String(activeTaskId) ? activeTask : null;
    const startDisabled = starting || !tasks.updatedAt || Boolean(tasks.error)
        || currentRunning.length > 0 || isCrawlerRunning(selectedDetail);
    const progress = useMemo(() => {
        const total = Number(selectedTask?.totalCount || 0);
        const completed = Number(selectedTask?.successCount || 0) + Number(selectedTask?.failCount || 0);
        // 百分比仅由已确认的成功、失败计数计算；保留一位小数，避免每批增加20只仍显示同一整数。
        return { total, completed, percent: total ? Math.min(100, completed * 100 / total) : null };
    }, [selectedTask]);

    const update = (key, value) => setForm((current) => ({ ...current, [key]: value }));

    const start = async () => {
        const monitor = monitorRef.current;
        if (!monitor || startingRef.current || startDisabled) return;
        // 同步锁覆盖只读核验到提交全过程，防止同一帧双击重复发起请求。
        startingRef.current = true;
        setStarting(true);
        setActionError('');
        setMessage('');
        try {
            const snapshot = await monitor.refresh();
            if (snapshot.stopped || monitorRef.current !== monitor) return;
            if (snapshot.tasks.error) throw new Error('暂时无法核实后台任务，请待状态查询恢复后重试');
            const runningTask = snapshot.tasks.data.find(isCrawlerRunning);
            if (runningTask) {
                setMessage(`任务 #${runningTask.id} 正在运行，未重复提交抓取任务。`);
                return;
            }
            const task = await dataCrawlerApi.start({
                ...form,
                limit: Number(form.limit),
                daysBack: Number(form.daysBack),
                fqType: Number(form.fqType),
                requestDelayMillis: Number(form.requestDelayMillis),
            });
            if (monitorRef.current !== monitor) return;
            manuallySelectedRef.current = false;
            selectedIdRef.current = String(task.id);
            setActiveTaskId(String(task.id));
            setMessage(`抓取任务 #${task.id} 已启动，页面会自动刷新进度。`);
            void monitor.refresh();
        } catch (error) {
            if (monitorRef.current === monitor) setActionError(error.message || '启动数据抓取失败');
        } finally {
            startingRef.current = false;
            if (monitorRef.current === monitor) setStarting(false);
        }
    };

    const refresh = () => { void monitorRef.current?.refresh(); };
    const selectTask = (id) => {
        manuallySelectedRef.current = true;
        selectedIdRef.current = String(id);
        setActiveTaskId(String(id));
        // 同一编号也明确刷新；切换时在途旧详情由观察器按编号丢弃。
        refresh();
    };

    return (
        <div className="score-page crawler-page">
            <div className="page-intro">
                <div>
                    <h1>股票与 ETF 数据抓取</h1>
                    <p>支持 00、30、60 开头股票与 58 开头 ETF，按证券增量采集日线；ETF 行情独立存储。</p>
                </div>
                <span className="strategy-badge crawler-badge">独立数据工具</span>
            </div>

            <Panel title="抓取参数">
                <div className="crawler-form">
                    <label className="field">证券代码前缀
                        <input value={form.codePrefixes} onChange={(e) => update('codePrefixes', e.target.value)} placeholder="股票：00,30,60；ETF：58"/>
                    </label>
                    <label className="field">最多证券数
                        <DecimalInput value={form.limit} onChange={(value) => update('limit', value)}/>
                    </label>
                    <label className="field">回看自然日
                        <DecimalInput value={form.daysBack} onChange={(value) => update('daysBack', value)}/>
                    </label>
                    <label className="field">主抓取复权
                        <select value={form.fqType} onChange={(e) => update('fqType', e.target.value)}>
                            <option value={1}>前复权</option>
                            <option value={2}>后复权</option>
                            <option value={0}>不复权</option>
                        </select>
                    </label>
                    <label className="field">请求间隔毫秒
                        <DecimalInput value={form.requestDelayMillis} onChange={(value) => update('requestDelayMillis', value)}/>
                    </label>
                    <label className="crawler-check">
                        <input type="checkbox" checked={form.storeRawPrices} onChange={(e) => update('storeRawPrices', e.target.checked)}/>
                        同时保存不复权价格
                    </label>
                    <label className="crawler-check">
                        <input type="checkbox" checked={form.refreshStockList} onChange={(e) => update('refreshStockList', e.target.checked)}/>
                        强制刷新证券列表
                    </label>
                </div>
                <div className="crawler-actions">
                    <button type="button" className="score-rebuild" onClick={start} disabled={startDisabled}>
                        {starting ? '启动中…' : '开始抓取数据'}
                    </button>
                    <button type="button" className="ghost" onClick={refresh}>刷新任务状态</button>
                    {currentRunning.length > 0 ? <span className="muted">
                        任务 {currentRunning.map((task) => `#${task.id}`).join('、')} 正在运行
                        {!currentRunning.some((task) => String(task.id) === String(activeTaskId))
                            ? <> · <button type="button" className="ghost" onClick={() => selectTask(currentRunning[0].id)}>查看运行任务</button></> : null}
                    </span> : null}
                </div>
                <div className="forecast-note crawler-note">
                    默认采集 00、30、60 开头股票；填写 58 可单独采集该前缀 ETF，也可用逗号组合前缀。
                    回看天数按自然日计算，最多证券数为 0 表示不限数量。ETF 当前仅保存数据源返回的不复权日线，
                    独立存储，不会自动进入现有个股评分或预测；上方复权选项仅对 00、30、60 开头股票生效。
                </div>
            </Panel>

            <ErrorBanner message={[tasks.error, selectedQuery?.error, actionError].filter(Boolean).join('；')} onRetry={refresh}/>
            {(tasks.error || selectedQuery?.error) ? <p className="muted">状态查询失败后会自动重试；查询失败不代表后台采集已经停止。</p> : null}
            {message ? <div className="forecast-success">{message}</div> : null}

            <div className="forecast-note crawler-note">
                成功采集通常每处理 20 只才更新一次计数，失败和最后一只会立即更新。
                两次更新之间数字可能不变；下方百分比按已确认的成功数与失败数计算，不估算尚未上报的进度。
                <div>列表最近成功查询：{formatQueryTime(tasks.updatedAt)}；
                    当前任务最近成功查询：{formatQueryTime(selectedQuery?.updatedAt)}。
                    查询时间仅表示页面收到响应，不是后台活动时间。
                    {tasks.loading || selectedQuery?.loading ? ' 正在查询…' : ''}
                </div>
            </div>

            <div className="stat-grid">
                <StatCard label="当前任务" value={selectedTask ? `#${selectedTask.id}` : '-'} hint={selectedTask ? statusText[selectedTask.status] || selectedTask.status : activeTaskId ? '正在读取任务状态' : tasks.loading ? '正在查询后台任务' : '尚未选择任务'}/>
                <StatCard label="已处理（成功+失败）" value={progress.percent !== null ? `${progress.percent.toFixed(1)}%` : '-'} hint={selectedTask ? `${progress.completed}/${progress.total || '-'} 只` : '等待任务'}/>
                <StatCard tone="success" label="成功证券" value={selectedTask?.successCount ?? '-'} hint="完成日线查询并入库"/>
                <StatCard tone="danger" label="失败证券" value={selectedTask?.failCount ?? '-'} hint="可查看任务备注定位"/>
            </div>

            {selectedTask ? (
                <Panel title={`当前任务 #${selectedTask.id}`}>
                    <div className="crawler-task-head">
                        <span className={`crawler-status ${statusClass(selectedTask.status)}`}>{statusText[selectedTask.status] || selectedTask.status}</span>
                        <span>开始：{formatTime(selectedTask.startTime)}</span>
                        <span>结束：{formatTime(selectedTask.endTime)}</span>
                    </div>
                    <div className="crawler-progress"><span style={{ width: `${progress.percent ?? 0}%` }}/></div>
                    <div className="crawler-remark">{selectedTask.remark || '暂无任务备注'}</div>
                </Panel>
            ) : null}

            <Panel title="最近抓取任务">
                <p className="muted">每轮查询完成后间隔 3 秒继续刷新；点击任务编号可查看详情，再次点击可刷新。手动查看历史任务后会保留选择。</p>
                <div className="table-wrap">
                    <table className="data-table crawler-table">
                        <thead><tr><th>任务</th><th>状态</th><th>计划</th><th>成功</th><th>失败</th><th>开始时间</th><th>结束时间</th><th>备注</th></tr></thead>
                        <tbody>
                        {(tasks.data ?? []).map((task) => (
                            <tr key={task.id} onClick={() => selectTask(task.id)} className={String(task.id) === String(activeTaskId) ? 'selected-row' : ''}>
                                <td>#{task.id} · {task.taskName}</td>
                                <td><span className={`crawler-status ${statusClass(task.status)}`}>{statusText[task.status] || task.status}</span></td>
                                <td>{task.totalCount ?? 0}</td><td>{task.successCount ?? 0}</td><td>{task.failCount ?? 0}</td>
                                <td>{formatTime(task.startTime)}</td><td>{formatTime(task.endTime)}</td>
                                <td className="crawler-remark-cell">{task.remark || '-'}</td>
                            </tr>
                        ))}
                        </tbody>
                    </table>
                    {tasks.loading ? <div className="empty">加载中…</div> : null}
                    {!tasks.loading && !(tasks.data ?? []).length ? <div className="empty">暂无抓取任务</div> : null}
                </div>
            </Panel>

            <Panel title="抓取字段说明">
                <div className="metric-explain-grid crawler-explain-grid">
                    <div><strong>主抓取复权</strong><span>00、30、60 开头股票默认前复权，可切换后复权或不复权；58 开头 ETF 当前仅保存数据源返回的不复权日线。</span></div>
                    <div><strong>不复权价格</strong><span>股票勾选后额外保存原始市场价格；ETF 只采集一次不复权行情，不会因勾选产生额外复权记录。</span></div>
                    <div><strong>增量续抓</strong><span>根据本地行情的最早和最新日期，补齐缺失的历史前段或最新后段；可在任务备注中查看处理结果。</span></div>
                    <div><strong>证券范围</strong><span>00、30、60 对应当前支持的股票范围，58 对应 ETF。默认仍为股票范围，采集 ETF 时请主动填写 58。</span></div>
                    <div><strong>独立存储</strong><span>股票按 00、30、60 分表保存；58 开头 ETF 使用独立行情存储，不会自动加入现有个股评分、训练或预测股票池。</span></div>
                    <div><strong>数据字段</strong><span>包含行情源提供的 OHLC、成交量、成交额等字段；股票与 ETF 的可用字段不同，ETF 不沿用个股估值或 ST 含义。</span></div>
                </div>
            </Panel>
        </div>
    );
}
