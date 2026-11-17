/**
 * 主题状态仅管理外观，不读取或改写任何选股、任务、模型参数的存储键。
 * 浏览器对象由调用者传入，便于在离线测试中验证系统事件和存储受限场景。
 */
export const THEME_STORAGE_KEY = 'stock.platform.theme';
export const THEME_MODES = Object.freeze(['light', 'dark', 'system']);
export const SYSTEM_THEME_QUERY = '(prefers-color-scheme: dark)';

/** 未设置、旧值或无效值统一回到跟随系统，避免页面出现无法选择的第四种状态。 */
export function normalizeThemeMode(value) {
    return THEME_MODES.includes(value) ? value : 'system';
}

/** 手动主题优先；只有 system 模式根据当前系统偏好解析为实际浅色或暗色。 */
export function resolveTheme(mode, prefersDark) {
    const normalized = normalizeThemeMode(mode);
    return normalized === 'system' ? (prefersDark ? 'dark' : 'light') : normalized;
}

/** 隐私模式、禁用站点存储和配额异常不能使页面启动失败。 */
export function readThemeMode(browser) {
    try {
        return normalizeThemeMode(browser?.localStorage?.getItem(THEME_STORAGE_KEY));
    } catch {
        return 'system';
    }
}

/**
 * DOM 在通知 React 订阅者之前更新，保证图表随后读取 CSS 变量时已获得新主题。
 * data-theme-mode 保留用户选择，data-theme 表示实际显示值，两者不能互相替代。
 */
export function applyDocumentTheme(document, snapshot) {
    const element = document?.documentElement;
    if (!element) return;
    element.dataset.theme = snapshot.resolvedTheme;
    element.dataset.themeMode = snapshot.mode;
    element.style.colorScheme = snapshot.resolvedTheme;
}

/**
 * 创建可被 useSyncExternalStore 订阅的主题状态。
 * 仅在存在订阅者时监听系统与跨标签页事件，避免 React StrictMode 的重复挂载泄漏监听器。
 */
export function createThemeStore(browser) {
    let media = null;
    try {
        media = browser?.matchMedia?.(SYSTEM_THEME_QUERY) || null;
    } catch {
        // 极简浏览器没有媒体查询能力时按浅色系统处理，仍允许手动切换。
    }
    const initialMode = readThemeMode(browser);
    let snapshot = Object.freeze({
        mode: initialMode,
        resolvedTheme: resolveTheme(initialMode, Boolean(media?.matches)),
    });
    const listeners = new Set();
    applyDocumentTheme(browser?.document, snapshot);

    function update(mode, prefersDark = Boolean(media?.matches)) {
        const next = { mode: normalizeThemeMode(mode), resolvedTheme: resolveTheme(mode, prefersDark) };
        if (next.mode === snapshot.mode && next.resolvedTheme === snapshot.resolvedTheme) return;
        snapshot = Object.freeze(next);
        applyDocumentTheme(browser?.document, snapshot);
        listeners.forEach((listener) => listener());
    }

    function onSystemChange(event) {
        // 手动模式的解析结果不变，update 保持快照引用，不触发无意义的全站重绘。
        update(snapshot.mode, Boolean(event.matches));
    }

    function onStorage(event) {
        if (event.key !== THEME_STORAGE_KEY && event.key !== null) return;
        try {
            // sessionStorage 的同名键不属于主题偏好，不能误触发跨标签页同步。
            if (event.storageArea && event.storageArea !== browser.localStorage) return;
        } catch {
            // 存储访问被限制时仍接受浏览器已提供的有效事件值，当前页面继续可用。
        }
        update(event.key === null ? 'system' : event.newValue);
    }

    function startListening() {
        if (media?.addEventListener) media.addEventListener('change', onSystemChange);
        else media?.addListener?.(onSystemChange);
        browser?.addEventListener?.('storage', onStorage);
        // 系统可能在首屏脚本与 React 订阅之间变化；此处只刷新系统值，不覆盖内存中的手动选择。
        update(snapshot.mode);
    }

    function stopListening() {
        if (media?.removeEventListener) media.removeEventListener('change', onSystemChange);
        else media?.removeListener?.(onSystemChange);
        browser?.removeEventListener?.('storage', onStorage);
    }

    return {
        getSnapshot: () => snapshot,
        subscribe(listener) {
            listeners.add(listener);
            if (listeners.size === 1) startListening();
            return () => {
                listeners.delete(listener);
                if (listeners.size === 0) stopListening();
            };
        },
        setMode(value) {
            const mode = normalizeThemeMode(value);
            update(mode);
            try {
                browser?.localStorage?.setItem(THEME_STORAGE_KEY, mode);
            } catch {
                // 存储不可用时仅本次页面会话生效，不能回滚用户刚选定的主题。
            }
        },
    };
}
