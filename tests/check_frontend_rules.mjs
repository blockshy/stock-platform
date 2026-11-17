/**
 * 无额外依赖的前端架构门禁。
 * 校验共享展示层不依赖业务、样式使用局部作用域、颜色统一来自设计变量，
 * 并保护本次迁移未授权改动的请求、格式化、持久化与监控逻辑。
 * 这是迁移保护而非完整 ESLint；后续有意改变业务时必须先审查并更新契约。
 */
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative } from 'node:path';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const source = join(root, 'apps/web/src');
const failures = [];
let checked = 0;
const requireRule = (condition, message) => { checked++; if (!condition) failures.push(message); };

/** 只遍历源码目录，不触及 node_modules、运行配置或本地凭据。 */
function walk(directory) {
    return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
        const path = join(directory, entry.name);
        return entry.isDirectory() ? walk(path) : [path];
    });
}

for (const path of walk(join(source, 'shared'))) {
    const name = relative(source, path).replaceAll('\\', '/');
    const content = readFileSync(path, 'utf8');
    if (/\.[jt]sx?$/.test(path)) {
        requireRule(!/from\s+['"][^'"]*(?:\/api(?:\/|\.)|\/context\/|\/pages\/|\/prob\/|\/ga\/|\/quant\/)/.test(content), `${name}：共享组件禁止依赖业务模块`);
        requireRule(!/\b(?:fetch|localStorage|sessionStorage)\s*(?:\(|\.)/.test(content), `${name}：共享展示层禁止发请求或保存业务状态`);
    }
    if (path.endsWith('.css')) {
        requireRule(path.endsWith('.module.css'), `${name}：新共享样式必须使用 CSS Modules`);
        requireRule(!/:global\b|!important/.test(content), `${name}：共享样式禁止全局覆盖和 !important`);
        requireRule(!/#[\da-f]{3,8}\b/i.test(content), `${name}：颜色必须引用 tokens.css`);
    }
}

const tokens = readFileSync(join(source, 'styles/tokens.css'), 'utf8');
for (const key of ['--ui-rise', '--ui-fall', '--ui-success', '--ui-danger', '--ui-control-height', '--ui-font']) {
    requireRule(tokens.includes(`${key}:`), `缺少设计变量 ${key}`);
}
const app = readFileSync(join(source, 'App.jsx'), 'utf8');
requireRule(app.includes('import.meta.env.DEV ? <Route path="/dev/ui"'), '组件示例必须受 DEV 条件保护');

const baseline = JSON.parse(readFileSync(join(root, 'contracts/frontend-protected.json'), 'utf8'));
for (const item of baseline.files) {
    const digest = createHash('sha256').update(readFileSync(join(source, item.path))).digest('hex');
    requireRule(digest === item.sha256, `${item.path}：受保护的业务契约发生变化，需要单独审查`);
}
assert.equal(failures.length, 0, failures.join('\n'));
console.log(`前端规范门禁通过：${checked} 条检查，${baseline.files.length} 份业务文件保持原始摘要。`);
