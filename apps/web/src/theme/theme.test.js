/** 主题行为离线回归：不加载 React、不访问网络，也不读写真实浏览器或本机存储。 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { THEME_STORAGE_KEY, createThemeStore, normalizeThemeMode, resolveTheme } from './theme.js';

/** 模拟媒体查询和跨页存储事件，保持与浏览器相同的事件值及监听器增删语义。 */
function makeBrowser({ stored = null, dark = false, blocked = false, legacyMedia = false } = {}) {
    const values = new Map(stored === null ? [] : [[THEME_STORAGE_KEY, stored]]);
    const mediaListeners = new Set();
    const storageListeners = new Set();
    const media = { matches: dark };
    if (legacyMedia) {
        media.addListener = (listener) => mediaListeners.add(listener);
        media.removeListener = (listener) => mediaListeners.delete(listener);
    } else {
        media.addEventListener = (_, listener) => mediaListeners.add(listener);
        media.removeEventListener = (_, listener) => mediaListeners.delete(listener);
    }
    const storage = { getItem: (key) => values.get(key) ?? null, setItem: (key, value) => values.set(key, value) };
    const browser = {
        document: { documentElement: { dataset: {}, style: {} } },
        matchMedia: () => media,
        addEventListener: (type, listener) => { if (type === 'storage') storageListeners.add(listener); },
        removeEventListener: (type, listener) => { if (type === 'storage') storageListeners.delete(listener); },
    };
    Object.defineProperty(browser, 'localStorage', { get() { if (blocked) throw new Error('storage blocked'); return storage; } });
    return {
        browser, values, mediaListeners, storageListeners,
        systemChange(matches) {
            media.matches = matches;
            mediaListeners.forEach((listener) => listener({ matches }));
        },
        storageChange(key, newValue, storageArea = storage) {
            storageListeners.forEach((listener) => listener({ key, newValue, storageArea }));
        },
    };
}

test('默认跟随系统，存储中无效模式也回退系统', () => {
    assert.equal(normalizeThemeMode(undefined), 'system');
    assert.equal(normalizeThemeMode('unknown'), 'system');
    const { browser } = makeBrowser({ dark: true, stored: 'obsolete' });
    const store = createThemeStore(browser);
    assert.deepEqual(store.getSnapshot(), { mode: 'system', resolvedTheme: 'dark' });
    assert.equal(browser.document.documentElement.style.colorScheme, 'dark');
});

test('手动选择优先于系统，并持久化到独立主题键', () => {
    const fixture = makeBrowser({ dark: true });
    fixture.values.set('stock.current', '000001');
    const store = createThemeStore(fixture.browser);
    store.setMode('light');
    assert.deepEqual(store.getSnapshot(), { mode: 'light', resolvedTheme: 'light' });
    assert.equal(fixture.values.get(THEME_STORAGE_KEY), 'light');
    assert.equal(fixture.values.get('stock.current'), '000001');
    assert.equal(resolveTheme('dark', false), 'dark');
});

test('系统切换实时更新跟随模式，通知订阅者之前 DOM 已切换', () => {
    const fixture = makeBrowser();
    const store = createThemeStore(fixture.browser);
    let notifications = 0;
    const unsubscribe = store.subscribe(() => {
        notifications++;
        assert.equal(fixture.browser.document.documentElement.dataset.theme, store.getSnapshot().resolvedTheme);
    });
    fixture.systemChange(true);
    assert.equal(store.getSnapshot().resolvedTheme, 'dark');
    fixture.systemChange(false);
    assert.equal(store.getSnapshot().resolvedTheme, 'light');
    assert.equal(notifications, 2);
    unsubscribe();
});

test('手动模式忽略系统外观变化，回到跟随时立即使用最新系统值', () => {
    const fixture = makeBrowser();
    const store = createThemeStore(fixture.browser);
    const unsubscribe = store.subscribe(() => {});
    store.setMode('light');
    const manualSnapshot = store.getSnapshot();
    fixture.systemChange(true);
    assert.equal(store.getSnapshot(), manualSnapshot);
    store.setMode('system');
    assert.deepEqual(store.getSnapshot(), { mode: 'system', resolvedTheme: 'dark' });
    unsubscribe();
});

test('跨标签页只同步主题 localStorage，清空后恢复跟随系统', () => {
    const fixture = makeBrowser({ dark: true });
    const store = createThemeStore(fixture.browser);
    const unsubscribe = store.subscribe(() => {});
    fixture.storageChange(THEME_STORAGE_KEY, 'light');
    assert.equal(store.getSnapshot().resolvedTheme, 'light');
    fixture.storageChange('unrelated.key', 'dark');
    assert.equal(store.getSnapshot().mode, 'light');
    fixture.storageChange(THEME_STORAGE_KEY, 'dark', {});
    assert.equal(store.getSnapshot().mode, 'light');
    fixture.storageChange(null, null);
    assert.deepEqual(store.getSnapshot(), { mode: 'system', resolvedTheme: 'dark' });
    unsubscribe();
});

test('存储访问受限不阻止初始化和手动主题切换', () => {
    const fixture = makeBrowser({ blocked: true });
    const store = createThemeStore(fixture.browser);
    assert.doesNotThrow(() => store.setMode('dark'));
    assert.equal(store.getSnapshot().resolvedTheme, 'dark');
    const unsubscribe = store.subscribe(() => {});
    unsubscribe();
    // 重新挂载不能因写存储失败而丢失当前会话中刚选定的模式。
    const again = store.subscribe(() => {});
    assert.equal(store.getSnapshot().mode, 'dark');
    again();
});

test('重复订阅与卸载只保留一组浏览器事件监听器', () => {
    const fixture = makeBrowser({ legacyMedia: true });
    const store = createThemeStore(fixture.browser);
    const first = store.subscribe(() => {});
    const second = store.subscribe(() => {});
    assert.equal(fixture.mediaListeners.size, 1);
    assert.equal(fixture.storageListeners.size, 1);
    first();
    assert.equal(fixture.mediaListeners.size, 1);
    second();
    assert.equal(fixture.mediaListeners.size, 0);
    assert.equal(fixture.storageListeners.size, 0);
});

test('缺少浏览器和媒体查询时仍可离线渲染，默认浅色系统', () => {
    const store = createThemeStore(undefined);
    assert.deepEqual(store.getSnapshot(), { mode: 'system', resolvedTheme: 'light' });
    assert.doesNotThrow(() => store.setMode('dark'));
});

test('重复选择同一模式保持快照身份，避免全站和图表无意义重绘', () => {
    const fixture = makeBrowser();
    const store = createThemeStore(fixture.browser);
    store.setMode('dark');
    const snapshot = store.getSnapshot();
    store.setMode('dark');
    assert.equal(store.getSnapshot(), snapshot);
});

test('真实 HTML 首屏脚本与运行时主题解析一致，包括禁用存储的情况', () => {
    const html = readFileSync(new URL('../../index.html', import.meta.url), 'utf8');
    const source = html.match(/<script id="theme-bootstrap">([\s\S]*?)<\/script>/)?.[1];
    assert.ok(source, '首屏初始化必须在 HTML 中存在');
    for (const stored of [null, 'light', 'dark', 'system', 'invalid']) {
        for (const dark of [false, true]) {
            for (const blocked of [false, true]) {
                const fixture = makeBrowser({ stored, dark, blocked });
                const context = { window: fixture.browser, document: fixture.browser.document };
                Object.defineProperty(context, 'localStorage', { get: () => fixture.browser.localStorage });
                vm.runInNewContext(source, context);
                const firstPaint = { ...fixture.browser.document.documentElement.dataset };
                const store = createThemeStore(fixture.browser);
                assert.equal(firstPaint.themeMode, store.getSnapshot().mode);
                assert.equal(firstPaint.theme, store.getSnapshot().resolvedTheme);
            }
        }
    }
});
