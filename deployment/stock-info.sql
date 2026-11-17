-- 股票基础信息表：补齐三个公开仓库缺少的基础表定义。
-- Java 自带 BaoStock 采集器只维护代码、名称、市场、查询代码、板块与交易所。
-- 行业和上市日期等尚未采集的信息保留 NULL，不生成虚构数据。
CREATE TABLE IF NOT EXISTS `stock_info` (
  `code` VARCHAR(10) NOT NULL COMMENT '股票代码，保留前导零',
  `name` VARCHAR(128) NOT NULL COMMENT '股票简称，由数据源提供',
  `market` TINYINT NOT NULL COMMENT '市场编号：0=深圳，1=上海',
  `secid` VARCHAR(16) NOT NULL COMMENT 'BaoStock 查询代码，例如 sz.000001',
  `board` VARCHAR(32) DEFAULT NULL COMMENT '板块名称',
  `exchange` CHAR(2) NOT NULL COMMENT '交易所代码：SH 或 SZ',
  `ipo_date` DATE DEFAULT NULL COMMENT '上市日期，尚未采集时为空',
  `out_date` DATE DEFAULT NULL COMMENT '退市日期，尚未采集时为空',
  `stock_type` VARCHAR(32) DEFAULT NULL COMMENT '证券类型原始值',
  `listing_status` VARCHAR(32) DEFAULT NULL COMMENT '上市状态原始值',
  `industry` VARCHAR(128) DEFAULT NULL COMMENT '所属行业，尚未采集时为空',
  `status` TINYINT DEFAULT NULL COMMENT '基础信息状态，尚未采集时为空',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次入库时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近更新时间',
  PRIMARY KEY (`code`),
  KEY `idx_stock_info_exchange` (`exchange`,`code`),
  KEY `idx_stock_info_secid` (`secid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='股票基础信息，由 Java 采集器维护';
