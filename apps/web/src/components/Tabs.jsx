import { Tabs as SharedTabs } from '../shared/ui/UI.jsx';

/** 保留原 items/value/onChange 协议，不改变页面状态或组件挂载方式。 */
export default function Tabs(props) {
    return <SharedTabs {...props}/>;
}
