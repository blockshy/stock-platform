package com.li.stock.crawl;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 数据库中的持久任务快照；JSON 仅包含规范化参数和最小证券元数据，不包含连接凭据。 */
public record CrawlJobCheckpoint(long rootTaskId, long latestTaskId, String status,
                                 boolean pauseRequested, String configJson, String securitiesJson,
                                 LocalDate observedOn, String lastCode, Integer lastAdjustFlag,
                                 LocalDate lastFrom, LocalDate lastTo, LocalDateTime updatedAt) {}
