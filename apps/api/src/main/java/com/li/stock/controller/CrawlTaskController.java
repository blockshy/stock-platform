package com.li.stock.controller;

import com.li.stock.common.ApiResponse;
import com.li.stock.domain.query.CrawlTaskRequest;
import com.li.stock.domain.vo.CrawlTaskVO;
import com.li.stock.crawl.CrawlCheckpointVO;
import com.li.stock.service.CrawlTaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 独立的股票行情抓取工具接口。 */
@RestController
@RequestMapping("/api/data-crawler")
public class CrawlTaskController {
    private final CrawlTaskService service;

    public CrawlTaskController(CrawlTaskService service) {
        this.service = service;
    }

    @PostMapping("/tasks")
    public ApiResponse<CrawlTaskVO> start(@RequestBody(required = false) CrawlTaskRequest request) {
        return ApiResponse.ok(service.start(request));
    }

    /** 前端显式探测能力，兼容尚未切换的新旧服务；接口存在不表示历史任务有可恢复参数。 */
    @GetMapping("/capabilities")
    public ApiResponse<Map<String, Boolean>> capabilities() {
        return ApiResponse.ok(Map.of("durableResume", true, "safePause", true, "fixedDateRange", true, "batchProduction", false));
    }

    /** 只提出暂停请求，当前查询和事务安全落盘后才进入 paused；不强杀线程或丢弃已获取数据。 */
    @PostMapping("/tasks/{id}/pause")
    public ApiResponse<CrawlTaskVO> pause(@PathVariable long id) {
        return ApiResponse.ok(service.pause(id));
    }

    /** 沿用已保存的日期与证券池创建续采日志，不重新根据当前日期或网页表单扩大采集范围。 */
    @PostMapping("/tasks/{id}/resume")
    public ApiResponse<CrawlTaskVO> resume(@PathVariable long id) {
        return ApiResponse.ok(service.resume(id));
    }

    /** 返回非敏感的断点摘要；没有持久参数的旧任务必须明确提示，不能猜测其原始请求。 */
    @GetMapping("/tasks/{id}/checkpoint")
    public ApiResponse<CrawlCheckpointVO> checkpoint(@PathVariable long id) {
        return ApiResponse.ok(service.checkpoint(id));
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<CrawlTaskVO> find(@PathVariable long id) {
        return ApiResponse.ok(service.find(id));
    }

    @GetMapping("/tasks")
    public ApiResponse<List<CrawlTaskVO>> recent(
            @RequestParam(required = false, defaultValue = "20") Integer limit) {
        return ApiResponse.ok(service.recent(limit));
    }
}
