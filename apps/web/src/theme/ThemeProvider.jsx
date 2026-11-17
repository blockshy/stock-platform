import { createContext, useContext, useMemo, useSyncExternalStore } from 'react';
import { createThemeStore } from './theme.js';

// 整个页面只创建一个外观状态源；订阅清理由 store 统一处理，不与任何业务 Context 耦合。
const store = createThemeStore(typeof window === 'undefined' ? undefined : window);
const ThemeContext = createContext(null);

export function ThemeProvider({ children }) {
    const snapshot = useSyncExternalStore(store.subscribe, store.getSnapshot, store.getSnapshot);
    const value = useMemo(() => ({ ...snapshot, setMode: store.setMode }), [snapshot]);
    return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

/** 图表和主题控件只订阅外观状态，不向业务页面暴露存储实现。 */
export function useTheme() {
    const context = useContext(ThemeContext);
    if (!context) throw new Error('主题组件必须放在 ThemeProvider 内。');
    return context;
}
