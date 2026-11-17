package com.li.stock.crawl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 对外只读断点摘要；完整参数保存在服务端，继续任务不接受前端临时更换日期或复权口径。 */
public record CrawlCheckpointVO(long rootTaskId, long latestTaskId, String status,
                                boolean pauseRequested, boolean resumable, LocalDate from, LocalDate to,
                                String codePrefixes, List<Integer> fqTypes, int requestDelayMillis,
                                int securityCount, String adjustmentPolicy, LocalDate observedOn,
                                String lastCode, Integer lastAdjustFlag, LocalDate lastFrom, LocalDate lastTo,
                                LocalDateTime updatedAt) {}
