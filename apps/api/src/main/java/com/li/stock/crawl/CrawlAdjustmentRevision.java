package com.li.stock.crawl;

/**
 * 复权覆盖版本策略，与缺口规划、任务状态分离，供后续已验收的因子事件指纹替换。
 *
 * <p>当前没有经过验收的因子版本，日期相同也不能证明前/后复权锚点相同：
 * 同一天的收盘入库或分红因子更新可能发生在暂停与继续之间。因此每一轮 crawl_log
 * 使用独立的尝试标记，新任务和恢复任务都重新确认该证券的完整请求窗口；
 * 只有当前轮次已经完整成功提交的口径可以复用。</p>
 *
 * <p>尝试标记不是经过证明的上游复权快照，只用于避免跨轮盲目复用；上游响应的一致性
 * 与因子重建仍须方案A对照验收。此保守回退不承诺复权提速；原始价格保留稳定覆盖版本。</p>
 */
public final class CrawlAdjustmentRevision {
    public static final String POLICY = "RAW_STABLE_ADJUSTED_ATTEMPT_RECHECK_V2";
    private CrawlAdjustmentRevision() {}

    public static String key(int adjustFlag, long attemptTaskId) {
        if (adjustFlag < 1 || adjustFlag > 3) throw new IllegalArgumentException("非法复权口径");
        if (attemptTaskId <= 0) throw new IllegalArgumentException("复权覆盖必须绑定有效的当前轮次任务编号");
        return adjustFlag == 3 ? "baostock-raw-v1" : "baostock-attempt-v2:" + attemptTaskId;
    }

    /** 未带可核验版本的历史前/后复权行不能单凭日期存在就认定覆盖。 */
    public static boolean trustsLegacyRows(int adjustFlag) { return adjustFlag == 3; }
}
