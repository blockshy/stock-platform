import { useId } from 'react';
import { useTheme } from './ThemeProvider.jsx';
import styles from './ThemePicker.module.css';

const LABELS = { light: '浅色', dark: '暗色', system: '跟随系统' };
const ICONS = {
    light: 'M12 3v2 M12 19v2 M3 12h2 M19 12h2 M5.6 5.6L7 7 M17 17l1.4 1.4 M5.6 18.4L7 17 M17 7l1.4-1.4 M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
    dark: 'M20.5 13.2A8.7 8.7 0 0 1 10.8 3.5 8.8 8.8 0 1 0 20.5 13.2Z',
    system: 'M4 4h16v12H4z M8 20h8 M12 16v4',
};

/** 原生 select 保留方向键、键盘搜索及系统菜单可达性；只统一关闭态外观。 */
export default function ThemePicker() {
    const { mode, resolvedTheme, setMode } = useTheme();
    const descriptionId = useId();
    const description = `主题：${LABELS[mode]}${mode === 'system' ? `，当前${LABELS[resolvedTheme]}` : ''}`;
    return <label className={styles.picker} data-ui="theme-picker" title={description}>
        <svg className={styles.icon} viewBox="0 0 24 24" fill="none" stroke="currentColor"
            strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d={ICONS[mode]}/>
        </svg>
        <select className={styles.select} value={mode} onChange={(event) => setMode(event.target.value)}
            aria-label="系统主题" aria-describedby={descriptionId}>
            <option value="light">浅色</option>
            <option value="dark">暗色</option>
            <option value="system">跟随系统</option>
        </select>
        <span id={descriptionId} className={styles.description}>{description}</span>
    </label>;
}
