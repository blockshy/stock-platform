package com.li.stock.crawl;

import com.li.stock.crawl.CrawlCoveragePlanner.Coverage;
import com.li.stock.crawl.CrawlCoveragePlanner.Range;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 纯内存日期契约：不访问系统数据库、BaoStock 或正在运行的采集任务。 */
class CrawlCoveragePlannerTest {
    private final LocalDate mon = LocalDate.of(2026, 11, 2);
    private final LocalDate tue = mon.plusDays(1), wed = mon.plusDays(2), thu = mon.plusDays(3), fri = mon.plusDays(4);
    private final Range week = new Range(mon, fri);
    private final Set<LocalDate> days = Set.of(mon, tue, wed, thu, fri);

    @Test
    void exactRowsExposeAnInternalHoleEvenWhenBothEndpointsExist() {
        assertEquals(List.of(new Range(wed, wed)), CrawlCoveragePlanner.missing(week, days,
                Set.of(mon, tue, thu, fri), true, List.of()));
    }

    @Test
    void successfulEmptyPreListingIntervalIsNotRequestedAgain() {
        assertTrue(CrawlCoveragePlanner.missing(week, days, Set.of(thu, fri), true,
                List.of(new Coverage(new Range(mon, wed), Set.of()))).isEmpty());
    }

    @Test
    void missingPersistedRowIsRepairedButCertifiedEmptyDaysRemainCovered() {
        Coverage previous = new Coverage(week, Set.of(mon, wed, fri));
        assertEquals(List.of(new Range(wed, wed)), CrawlCoveragePlanner.missing(week, days,
                Set.of(mon, fri), false, List.of(previous)));
    }

    @Test
    void olderEmptyCoverageCannotHideALaterReturnedRowThatWasDeleted() {
        assertEquals(List.of(new Range(wed, wed)), CrawlCoveragePlanner.missing(week, days, Set.of(), false,
                List.of(new Coverage(week, Set.of()), new Coverage(new Range(wed, wed), Set.of(wed)))));
    }

    @Test
    void oldAdjustedRowsWithoutAVerifiedRevisionCannotSuppressAFullWindowRead() {
        assertEquals(List.of(week), CrawlCoveragePlanner.missing(week, days, days, false, List.of()));
        assertTrue(CrawlCoveragePlanner.missing(week, days, days, false,
                List.of(new Coverage(week, days))).isEmpty());
    }

    @Test
    void weekendDoesNotCreateARequestAndCanJoinAdjacentMissingTradingDays() {
        LocalDate nextMon = mon.plusDays(7);
        assertEquals(List.of(new Range(fri, nextMon)), CrawlCoveragePlanner.missing(new Range(fri, nextMon),
                Set.of(fri, nextMon), Set.of(), true, List.of()));
        assertTrue(CrawlCoveragePlanner.missing(new Range(fri.plusDays(1), fri.plusDays(2)),
                Set.of(), Set.of(), true, List.of()).isEmpty());
    }

    @Test
    void rowsOutsideRequestedWindowNeverExpandTheRequest() {
        assertEquals(List.of(week), CrawlCoveragePlanner.missing(week, days,
                Set.of(mon.minusDays(20), fri.plusDays(20)), true, List.of()));
    }

    @Test
    void eachAttemptInvalidatesAdjustedCoverageEvenWithoutChangingObservationDate() {
        // 不传观察日期是刻意设计：日期完全相同也不能证明暂停前后上游没有更新因子。
        assertEquals(CrawlAdjustmentRevision.key(3, 101L), CrawlAdjustmentRevision.key(3, 102L));
        assertFalse(CrawlAdjustmentRevision.key(2, 101L).equals(CrawlAdjustmentRevision.key(2, 102L)));
        assertFalse(CrawlAdjustmentRevision.key(1, 101L).equals(CrawlAdjustmentRevision.key(1, 102L)));
        assertEquals(CrawlAdjustmentRevision.key(2, 101L), CrawlAdjustmentRevision.key(2, 101L));
        assertTrue(CrawlAdjustmentRevision.trustsLegacyRows(3));
        assertFalse(CrawlAdjustmentRevision.trustsLegacyRows(2));
    }
}
