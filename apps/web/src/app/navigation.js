/**
 * 导航仅组织已有功能，不改变路由、API 或模块开关。
 * 保留旧 URL 以兼容书签和页面回跳；标题集中定义，侧栏与顶栏使用同一名称。
 */
export const navigationGroups = [
    { label: '行情与数据', items: [
        { to: '/', label: '市场看板', end: true, icon: 'M3 3v18h18 M7 15l3-4 3 3 5-7' },
        { to: '/stocks', label: '股票列表', icon: 'M8 6h13 M8 12h13 M8 18h13 M3 6h.01 M3 12h.01 M3 18h.01' },
        { to: '/data-crawler', label: '数据抓取', icon: 'M12 3v12 M7 10l5 5 5-5 M4 20h16' },
    ] },
    { label: '评分与预测', items: [
        { to: '/scores', label: '量化评分', icon: 'M12 3l2.7 5.5 6 .9-4.3 4.2 1 6-5.4-2.8-5.4 2.8 1-6L3.3 9.4l6-.9z' },
        { to: '/short-term', label: '短期预测', icon: 'M13 2L4 14h7l-1 8 9-12h-7z' },
        { to: '/prob', label: '概率预测', icon: 'M3 17c4-8 7 4 10-3s5 2 8-6 M3 21h18 M7 3v4 M17 3v4' },
        { to: '/forecast', label: '模型预测', badge: '旧版', icon: 'M5 5h14v14H5z M9 9h6v6H9z M9 1v4 M15 1v4 M9 19v4 M15 19v4' },
    ] },
    { label: '策略研究', items: [
        { to: '/quant', label: '策略回测', icon: 'M4 20V10 M10 20V4 M16 20v-7 M22 20H2' },
        { to: '/ga', label: '遗传算法预测', icon: 'M4 6h4 M4 12h7 M4 18h10 M15 5l5 5-5 5 M15 15l5 5-5 5' },
        { to: '/t-trading', label: '做T模型', icon: 'M20 8H9a4 4 0 0 0 0 8h3 M17 5l3 3-3 3 M4 16h11a4 4 0 0 0 0-8H9' },
        { to: '/t-lab', label: '做T模型2', icon: 'M3 17l5-6 4 3 5-8 4 4 M3 21h18 M17 6h4v4' },
    ] },
];

export function pageTitle(pathname) {
    if (pathname.startsWith('/stocks/')) return '股票详情';
    if (pathname === '/dev/ui') return '组件示例';
    const item = navigationGroups.flatMap((group) => group.items)
        .find((candidate) => candidate.to === '/' ? pathname === '/' : pathname === candidate.to || pathname.startsWith(`${candidate.to}/`));
    return item?.label || '页面不存在';
}

export function pageGroup(pathname) {
    return navigationGroups.find((group) => group.items.some((item) => item.to === '/'
        ? pathname === '/' : pathname === item.to || pathname.startsWith(`${item.to}/`)))?.label || '工作台';
}
