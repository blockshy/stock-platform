-- 独立、显式执行的采集断点迁移。不得在运行中的生产采集旁自动执行；先在隔离验收库验证。
-- 不修改既有行情结构/模型表；任务参数与证券清单仅保存在本地数据库，不包含凭据。
CREATE TABLE IF NOT EXISTS crawl_job_checkpoint (
  root_task_id BIGINT NOT NULL PRIMARY KEY,
  latest_task_id BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL,
  pause_requested BOOLEAN NOT NULL DEFAULT FALSE,
  config_json LONGTEXT NOT NULL,
  securities_json LONGTEXT DEFAULT NULL,
  observed_on DATE NOT NULL,
  last_code VARCHAR(10) DEFAULT NULL,
  last_adjust_flag TINYINT DEFAULT NULL,
  last_from DATE DEFAULT NULL,
  last_to DATE DEFAULT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_crawl_checkpoint_latest (latest_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 一个逻辑任务可有多轮 crawl_log；保留原始日志，按任一轮编号都能找到同一份固定参数。
CREATE TABLE IF NOT EXISTS crawl_job_attempt (
  task_id BIGINT NOT NULL PRIMARY KEY,
  root_task_id BIGINT NOT NULL,
  KEY idx_crawl_attempt_root (root_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 每条覆盖由一次成功的完整区间响应产生。返回日期清单用于区分合法空结果和后来丢失的行。
CREATE TABLE IF NOT EXISTS crawl_coverage (
  code VARCHAR(10) NOT NULL,
  market CHAR(2) NOT NULL,
  adjustflag TINYINT NOT NULL,
  basis_key VARCHAR(100) NOT NULL,
  from_date DATE NOT NULL,
  to_date DATE NOT NULL,
  returned_dates_json LONGTEXT NOT NULL,
  task_id BIGINT NOT NULL,
  row_count INT NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (code,market,adjustflag,basis_key,from_date,to_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 全日历包含交易/非交易日；只有成功查询过的区间才写入，未查到的日期仍保持未知。
CREATE TABLE IF NOT EXISTS crawl_trade_calendar (
  calendar_date DATE NOT NULL PRIMARY KEY,
  is_trading_day BOOLEAN NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
