import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 本地部署默认通过 IPv4 回环地址连接 Spring Boot，避免 localhost 被解析成 IPv6
// 后与仅监听 IPv4 的后端连接失败。后端端口调整时，可以通过 VITE_BACKEND 覆盖。
// PowerShell 示例：$env:VITE_BACKEND = 'http://127.0.0.1:18080'; npm.cmd run dev
const BACKEND = process.env.VITE_BACKEND || 'http://127.0.0.1:8080';

export default defineConfig({
    plugins: [react()],
    // 把后端地址注入前端，页面在无法走同源代理时（例如用别的静态服务器打开）
    // 可以直接回退到该地址调用接口。
    define: {
        __BACKEND__: JSON.stringify(BACKEND),
    },
    server: {
        // 当前部署供本机浏览器使用，明确绑定回环地址，避免监听局域网网卡。
        host: '127.0.0.1',
        port: 5173,
        strictPort: true,    // 端口被占用直接报错，避免悄悄换到 5174 造成困惑
        open: false,
        proxy: {
            '/api': {
                target: BACKEND,
                changeOrigin: true,
                // 保留 /api 前缀，与后端 @RequestMapping 保持一致
                rewrite: (path) => path,
                // 浏览器同源请求也会带 Origin（POST/PUT 尤其如此）。后端若按 CORS 规则
                // 校验这个 Origin，旧配置的 allowedMethods 不含 PUT 时会直接 403，
                // 参数配置「保存」因此失败。代理层去掉 Origin，让同源请求不涉及 CORS；
                // 真正的跨域直连仍由后端 WebConfig 放行 PUT。
                configure: (proxy) => {
                    proxy.on('proxyReq', (proxyReq) => proxyReq.removeHeader('origin'));
                },
            },
        },
    },
    preview: {
        // 构建产物预览与开发服务采用相同的本机访问范围。
        host: '127.0.0.1',
        port: 4173,
        // 固定预览端口；发生冲突时显式报错，便于启动脚本准确判断运行状态。
        strictPort: true,
    },
    build: {
        outDir: 'dist',
        chunkSizeWarningLimit: 1200,
    },
});
