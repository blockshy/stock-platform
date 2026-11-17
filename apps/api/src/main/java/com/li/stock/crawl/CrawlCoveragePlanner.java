package com.li.stock.crawl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 采集缺口规划器：仅做内存集合运算，不访问网络、数据库或当前时钟。
 *
 * <p>成功覆盖保存的是“已经向上游完整查询过的闭区间”，而不是行情的 MIN/MAX。
 * returnedDates 是该次响应实际返回的日期清单：清单以外的日期是经过查询确认的合法空结果；
 * 清单内日期如果后来从行情表消失，必须重新补采，不能被覆盖记录掩盖。</p>
 */
public final class CrawlCoveragePlanner {
    private CrawlCoveragePlanner() {}

    /** 包含首尾两个日期的请求区间。 */
    public record Range(LocalDate from, LocalDate to) {
        public Range {
            if (from == null || to == null || from.isAfter(to)) {
                throw new IllegalArgumentException("采集覆盖区间必须包含有效的起止日期");
            }
        }
    }

    /** 仅接受同一证券、市场、复权口径和版本下的成功响应清单。 */
    public record Coverage(Range range, Set<LocalDate> returnedDates) {
        public Coverage { returnedDates = Set.copyOf(returnedDates); }
    }

    /**
     * 找出尚未证明完整的交易日，并将相邻缺口合并成请求区间。
     *
     * @param tradingDates 已成功获取的交易日历；不能传入由现有行情反推的不完整日历
     * @param existingDates 当前行情表精确日期，而非首末日期
     * @param trustExisting 原始价可采信旧库精确日期；未知复权基准的旧价格必须为 false
     * @param coverage 当前复权版本的成功区间；失败查询不能进入此集合
     */
    public static List<Range> missing(Range requested, Set<LocalDate> tradingDates,
                                      Set<LocalDate> existingDates, boolean trustExisting,
                                      Collection<Coverage> coverage) {
        Set<LocalDate> verified = new HashSet<>();
        // 相交覆盖可能来自不同重试区间。任一成功响应曾返回的日期，都必须仍然有行情行；
        // 不能让更早的“空区间”遮住后来确实返回、又从数据库丢失的记录。
        Set<LocalDate> requiredRows = new HashSet<>();
        coverage.forEach(item -> requiredRows.addAll(item.returnedDates()));
        for (Coverage item : coverage) {
            LocalDate from = item.range().from().isBefore(requested.from()) ? requested.from() : item.range().from();
            LocalDate to = item.range().to().isAfter(requested.to()) ? requested.to() : item.range().to();
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                if (!requiredRows.contains(day) || existingDates.contains(day)) verified.add(day);
            }
        }
        if (trustExisting) verified.addAll(existingDates);
        List<Range> missing = new ArrayList<>();
        LocalDate start = null;
        LocalDate lastMissing = null;
        for (LocalDate day = requested.from(); !day.isAfter(requested.to()); day = day.plusDays(1)) {
            // 周末/节假日可以桥接两段连续缺失交易日，但不能自行生成有效行情。
            if (!tradingDates.contains(day)) continue;
            if (!verified.contains(day)) {
                if (start == null) start = day;
                lastMissing = day;
            } else if (start != null) {
                missing.add(new Range(start, lastMissing));
                start = null;
            }
        }
        if (start != null) missing.add(new Range(start, lastMissing));
        return List.copyOf(missing);
    }
}
