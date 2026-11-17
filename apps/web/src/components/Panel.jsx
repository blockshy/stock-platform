import { Card } from '../shared/ui/UI.jsx';

/** 主站面板适配：保留 h2 标题层级与业务 className，统一卡片外观。 */
export default function Panel(props) {
    return <Card {...props} titleAs="h2" className={`panel ${props.className || ''}`}/>;
}
