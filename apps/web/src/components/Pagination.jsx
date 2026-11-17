import { Pager } from '../shared/ui/UI.jsx';

/** 主站保留服务端传入的 pages/total，不套用其它模块的空页归一化规则。 */
export default function Pagination({ page, size, total, pages, onPage, onSize, sizes = [20, 50, 100] }) {
    return <Pager page={page} pages={pages} size={size} onPage={onPage} onSize={onSize}
        sizes={sizes} summary={`共 ${total} 条 / ${pages} 页`}/>;
}
