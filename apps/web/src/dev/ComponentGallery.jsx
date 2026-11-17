import { useState } from 'react';
import { Alert, Badge, Button, Card, EmptyState, Field, FilterBar, Input, Metric, PageHeader, Pager, Select, Tabs } from '../shared/ui/UI.jsx';
import ChartView from '../shared/charts/ChartView.jsx';
import styles from './ComponentGallery.module.css';

/**
 * 仅开发模式可访问的视觉验收页。
 * 全部使用静态示例数据和组件内部状态，不请求接口、不触发采集/训练，也不写本地偏好。
 * 用于检查各状态、键盘焦点和窄屏排版，不能将示例数值作为真实行情展示。
 */
export default function ComponentGallery() {
    const [tab, setTab] = useState('controls');
    const [value, setValue] = useState('示例输入');
    const [market, setMarket] = useState('SZ');
    const [page, setPage] = useState(1);
    const [size, setSize] = useState(20);
    const [notice, setNotice] = useState('此页所有数值均为静态示例，不连接后台。');
    const chartOption = {
        grid: { top: 24, right: 20, bottom: 28, left: 46 },
        tooltip: { trigger: 'axis' },
        xAxis: { type: 'category', data: ['一', '二', '三', '四', '五'], axisLine: { lineStyle: { color: '#dfe6ef' } } },
        yAxis: { type: 'value', splitLine: { lineStyle: { color: '#edf1f6' } } },
        series: [{ type: 'line', data: [101, 104, 102, 108, 106], smooth: false, symbolSize: 6,
            lineStyle: { color: '#285bd4', width: 2 }, itemStyle: { color: '#285bd4' } }],
    };

    return <div className={styles.gallery}>
        <PageHeader title="组件示例" description="开发环境专用 · 检查统一外观、交互状态与响应式布局"
            actions={<Badge tone="info">静态示例</Badge>}/>
        <Alert title="示例说明">{notice}</Alert>
        <div className={styles.metrics}>
            <Metric label="上涨 · 市场方向" value="+2.36%" tone="up" hint="收益语义：红涨绿跌"/>
            <Metric label="下跌 · 市场方向" value="−1.28%" tone="down" hint="不等同于任务成功"/>
            <Metric label="成功 · 任务结果" value="1,403" tone="success" hint="操作成功使用独立语义色"/>
            <Metric label="失败 · 任务结果" value="57" tone="danger" hint="真实错误，不伪造进度"/>
        </div>
        <Card title="标签与表单" extra={<Tabs value={tab} onChange={setTab} items={[
            { value: 'controls', label: '基础控件' }, { value: 'states', label: '状态示例' },
        ]}/> }>
            {tab === 'controls' ? <>
                <FilterBar>
                    <Field label="文本输入" hint="原样保留字符串"><Input value={value} onChange={setValue}/></Field>
                    <Field label="选项"><Select value={market} onChange={setMarket} options={[
                        { value: 'SZ', label: '深圳 · 示例' }, { value: 'SH', label: '上海 · 示例' },
                    ]}/></Field>
                    <Button variant="primary" onClick={() => setNotice(`本地示例已更新：${value} / ${market}`)}>主要操作</Button>
                    <Button onClick={() => setNotice('只修改当前示例提示，没有发送请求。')}>次要操作</Button>
                </FilterBar>
                <div className={styles.row}>
                    <Button variant="ghost">轻量操作</Button><Button variant="danger">危险操作样式</Button>
                    <Button disabled>禁用状态</Button><Button loading loadingText="正在处理…">操作</Button>
                    <Button sm>紧凑按钮</Button>
                </div>
            </> : <div className={styles.row}>
                <Badge tone="plain">未开始</Badge><Badge tone="info">运行中</Badge><Badge tone="success">成功</Badge>
                <Badge tone="danger">失败</Badge><Badge tone="warn">等待确认</Badge><Badge tone="up">上涨</Badge><Badge tone="down">下跌</Badge>
            </div>}
        </Card>
        <div className={styles.columns}>
            <Card title="图表容器" extra={<Badge>静态曲线</Badge>}><ChartView option={chartOption} height={240}/></Card>
            <Card title="数据表与分页">
                <div className="table-wrap"><table className="data-table">
                    <thead><tr><th>示例代码</th><th>名称</th><th>展示值</th><th>任务状态</th></tr></thead>
                    <tbody><tr><td>000001</td><td>静态样本 A</td><td className="up">+2.36%</td><td><Badge tone="success">已完成</Badge></td></tr>
                        <tr><td>588000</td><td>静态样本 B</td><td>78.82 亿份</td><td><Badge tone="info">运行中</Badge></td></tr></tbody>
                </table></div>
                <Pager page={page} pages={3} size={size} onPage={setPage} onSize={setSize} summary={`示例第 ${page} / 3 页`}/>
            </Card>
        </div>
        <div className={styles.columns}>
            <Card title="空数据与加载"><EmptyState text="暂无符合条件的数据"/><EmptyState text="正在读取示例…" loading/></Card>
            <Card title="错误与成功反馈"><div className={styles.stack}>
                <Alert tone="danger" title="状态读取失败" action={<Button sm variant="ghost" onClick={() => setNotice('示例重试按钮可用；未发起后台请求。')}>重试</Button>}>保留明确原因与可执行操作。</Alert>
                <Alert tone="success" title="操作已完成">成功反馈与上涨色分开。</Alert>
                <Alert tone="warn" title="等待确认">说明仍需处理的条件。</Alert>
            </div></Card>
        </div>
    </div>;
}
