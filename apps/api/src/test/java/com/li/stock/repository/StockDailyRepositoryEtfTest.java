package com.li.stock.repository;

import com.li.stock.domain.vo.StockDailyVO;
import com.li.stock.mapper.StockDailyMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证单证券路由与采集/模型数据源隔离，避免新增 ETF 时把基金悄悄纳入个股模型。 */
class StockDailyRepositoryEtfTest {
    @Test
    void everySingleSecurityQueryUsesTheIndependentEtfShard() {
        List<String> tables = new ArrayList<>();
        StockDailyMapper mapper = (StockDailyMapper) Proxy.newProxyInstance(StockDailyMapper.class.getClassLoader(),
                new Class<?>[]{StockDailyMapper.class}, (proxy, method, args) -> {
                    tables.add((String) args[0]);
                    return switch (method.getName()) {
                        case "count" -> 0L;
                        case "page", "recentDesc", "listAsc" -> List.of();
                        default -> null;
                    };
                });
        StockDailyRepository repository = new StockDailyRepository(mapper);
        LocalDate date = LocalDate.of(2026, 11, 16);
        repository.count("588000", null, date, 3);
        repository.page("588000", null, date, 3, 0, 20);
        repository.recentAsc("588000", null, date, 3, 20);
        repository.listAsc("588000", null, date, 3);
        repository.latest("588000", 3);
        repository.latestBefore("588000", date, 3);
        repository.dataRange("588000");
        repository.preferredAdjustFlag("588000");
        assertEquals(8, tables.size());
        assertTrue(tables.stream().allMatch("stock_daily_58"::equals));
        assertThrows(IllegalArgumentException.class, () -> repository.latest("510300", 3));
        assertEquals(8, tables.size(), "不支持的证券不得到达 SQL 层");
    }

    @Test
    void mixedSavingKeepsEachSecurityInItsOwnWhitelistedShard() {
        List<String> tables = new ArrayList<>();
        StockDailyMapper mapper = (StockDailyMapper) Proxy.newProxyInstance(StockDailyMapper.class.getClassLoader(),
                new Class<?>[]{StockDailyMapper.class}, (proxy, method, args) -> {
                    tables.add((String) args[0]);
                    return ((List<?>) args[1]).size();
                });
        StockDailyRepository repository = new StockDailyRepository(mapper);
        assertEquals(4, repository.saveCrawlerBatch(List.of(row("000001"), row("300001"), row("600000"), row("588000"))));
        assertEquals(List.of("stock_daily_00", "stock_daily_30", "stock_daily_60", "stock_daily_58"), tables);
        assertThrows(IllegalArgumentException.class, () -> repository.saveCrawlerBatch(List.of(row("510300"))));
        assertEquals(4, tables.size());
    }

    @Test
    void generatedMapperSqlAndSchemaKeepEtfsOutOfTheStockModelView() throws Exception {
        Configuration config = new Configuration();
        for (String resource : List.of("mapper/StockDailyMapper.xml", "mapper/ScoreCalculationMapper.xml")) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
            }
        }
        String crawlSql = config.getMappedStatement("com.li.stock.mapper.StockDailyMapper.crawlerDateRanges")
                .getBoundSql(Map.of("codes", List.of("588000"), "adjustFlags", List.of(3))).getSql();
        String scoreSql = config.getMappedStatement("com.li.stock.mapper.ScoreCalculationMapper.loadBars")
                .getBoundSql(Map.of("asOfDate", LocalDate.of(2026, 11, 16), "adjustFlag", 2, "calendarDays", 420)).getSql();
        assertTrue(crawlSql.contains("FROM `stock_daily_crawler_all`"));
        assertTrue(scoreSql.contains("FROM `stock_daily_all`"));
        assertFalse(scoreSql.contains("crawler_all"));

        String schema;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("score_schema.sql")) {
            schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS `stock_daily_58` LIKE `stock_daily_00`"));
        assertTrue(schema.contains("ALTER TABLE `stock_daily_58` ALTER COLUMN `adjustflag` SET DEFAULT 3"));
        String modelView = statement(schema, "CREATE OR REPLACE VIEW `stock_daily_all`");
        assertTrue(modelView.contains("stock_daily_00") && modelView.contains("stock_daily_30") && modelView.contains("stock_daily_60"));
        assertFalse(modelView.contains("stock_daily_58"), "ETF 只能出现在专用采集视图中");
        String crawlerView = statement(schema, "CREATE OR REPLACE VIEW `stock_daily_crawler_all`");
        assertTrue(crawlerView.contains("stock_daily_all") && crawlerView.contains("stock_daily_58"));
    }

    /** 提取单条 DDL 检查隔离边界，不把后续视图中的 ETF 字样误算到股票视图。 */
    private String statement(String schema, String start) {
        int offset = schema.indexOf(start);
        return schema.substring(offset, schema.indexOf(';', offset));
    }

    private StockDailyVO row(String code) {
        return new StockDailyVO(code, code.startsWith("00") || code.startsWith("30") ? "SZ" : "SH",
                LocalDate.of(2026, 11, 16), null, null, null, null, null, null, null, null,
                null, null, null, 3, null, null, null, null, null, null, "baostock");
    }
}
