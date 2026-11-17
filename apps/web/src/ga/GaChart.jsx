import ChartView from '../shared/charts/ChartView.jsx';

/** GA图表适配：保留原默认高度与传入属性，图表数据和点击行为不变。 */
export default function EChart({ height = 280, ...props }) {
    return <ChartView {...props} height={height}/>;
}
