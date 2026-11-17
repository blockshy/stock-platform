package com.li.stock.domain.query;

import java.time.LocalDate;

/**
 * 页面触发日线抓取时的参数；旧请求可以继续只提供 daysBack。
 * 58 ETF 按上游实际支持情况只采不复权行情，fqType/storeRawPrices 仅决定混合任务中的股票口径。
 *
 * @param from 可选的历史起始日，包含该日；提供后优先于 daysBack
 * @param to 可选的历史截止日，包含该日；缺省使用任务提交当天，执行跨日时不再漂移
 */
public record CrawlTaskRequest(
        Integer limit,
        Integer daysBack,
        Integer fqType,
        Boolean storeRawPrices,
        String codePrefixes,
        Integer requestDelayMillis,
        Boolean refreshStockList,
        LocalDate from,
        LocalDate to
) {}
