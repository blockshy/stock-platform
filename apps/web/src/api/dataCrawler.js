import { apiGet, apiPost } from './client.js';

/** 独立股票行情抓取工具。 */
export const dataCrawlerApi = {
    start: (params = {}) => apiPost('/data-crawler/tasks', params),
    detail: (id) => apiGet(`/data-crawler/tasks/${id}`),
    recent: (limit = 20) => apiGet('/data-crawler/tasks', { limit }),
    // 能力探测兼容仍在执行旧任务的服务；暂停/续采属于写操作，底层绝不自动重发。
    capabilities: () => apiGet('/data-crawler/capabilities'),
    checkpoint: (id) => apiGet(`/data-crawler/tasks/${id}/checkpoint`),
    pause: (id) => apiPost(`/data-crawler/tasks/${id}/pause`),
    resume: (id) => apiPost(`/data-crawler/tasks/${id}/resume`),
};
