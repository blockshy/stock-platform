import { Metric } from '../shared/ui/UI.jsx';

/** 数值卡只负责展示，value 的单位、精度及空值由原页面决定。 */
export default function StatCard(props) {
    return <Metric {...props} className="stat-card"/>;
}
