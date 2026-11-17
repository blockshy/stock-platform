<#
.SYNOPSIS
只读审计 58 开头 ETF 的不复权日线覆盖，并核验 ETF 未进入股票模型数据。
.DESCRIPTION
ETF 单独使用 stock_daily_58，不扩大 stock_daily_all 或原有股票模型股票池。
行情源对本次 ETF 返回的复权标记为 3，因此本审计固定使用不复权数据，不要求伪造第二种口径。
交易日历优先来自逐自然日完整的独立日历表；缺失日期只从每只 ETF 的首条记录起算。
晚起始只表示需要结合成立/上市日期核对，不自动判定缺失历史、数据造假或采集失败。
仅执行 SELECT；CSV 和汇总对象不包含数据库 URL、账号、密码或模型配置快照。
#>
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^\d{4}-\d{2}-\d{2}$')][string]$HistoryStart,
    [Parameter(Mandatory=$true)][ValidatePattern('^\d{4}-\d{2}-\d{2}$')][string]$AsOf,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [ValidateSet('production','verification')][string]$Profile='production',
    [string]$ConfigPath=''
)
. (Join-Path $PSScriptRoot 'Common.ps1') -Profile $Profile -ConfigPath $ConfigPath
. (Join-Path $PSScriptRoot 'Coverage.Audit.ps1')

function Measure-EtfWindowCoverage {
<# 保持 ETF 独立，只审计真实不复权口径；函数入口用于离线替身测试，不包含服务启停。 #>
param([string]$HistoryStart,[string]$AsOf,[string]$OutputDirectory)

# 正则先限定 SQL 日期字面量的字符，再校验真实日期与先后顺序，避免无效窗口进入查询。
$startDate = [DateTime]::ParseExact($HistoryStart, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture)
$endDate = [DateTime]::ParseExact($AsOf, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture)
if ($startDate -gt $endDate) { throw 'HistoryStart 不能晚于 AsOf。' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
[IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
$summaryFile=Join-Path $OutputDirectory 'etf-coverage-summary.json'
$finalReport=$false
Save-IncompleteCoverageAudit $summaryFile $HistoryStart $AsOf
try {
$calendar=Get-CoverageAuditCalendar $Database $HistoryStart $AsOf (Join-Path $OutputDirectory 'etf-calendar.csv')
$endLiteral=if ($calendar.lastTradingDate) { "'$($calendar.lastTradingDate)'" } else { 'NULL' }

# ETF 数据独立读取；只有完全没有独立日历表的旧版本才回退行情推导统计，并标明未严格验收。
# 先关联日历并去重得到“实际属于交易日的行情日期集合”，再与应有交易日集合比较大小。
# 周末行情不进入匹配集合并单独报错，不能抵消缺口；行情CTE无需逐证券逐交易日反复回查。
# c.first_date 为空时没有可比较的历史区间，缺失数为 0，并由 zeroHistoryEtfs 单独报告。
$coverageSql = @"
WITH etf_window AS (
    SELECT code,market,adjustflag,trade_date,close,tradestatus
    FROM ${Database}.stock_daily_58
    WHERE code LIKE '58%' AND trade_date BETWEEN '$HistoryStart' AND '$AsOf'
), calendar AS ($($calendar.calendarSql)), coverage AS (
    SELECT e.code,e.market,COUNT(*) AS row_count,MIN(e.trade_date) AS first_date,
           MAX(e.trade_date) AS last_date,MAX(e.trade_date='$AsOf') AS has_asof,
           MAX(e.trade_date=$endLiteral) AS has_last_trading_day,
           SUM(COALESCE(e.tradestatus,1)<>0 AND (e.close IS NULL OR e.close<=0)) AS invalid_active_rows,
           COUNT(DISTINCT CASE WHEN d.trade_date IS NOT NULL THEN e.trade_date END) AS matched_calendar_days,
           SUM(d.trade_date IS NULL) AS non_trading_rows
    FROM etf_window e LEFT JOIN calendar d ON d.trade_date=e.trade_date
    WHERE e.adjustflag=3 GROUP BY e.code,e.market
), unexpected AS (
    SELECT code,market,COUNT(*) AS row_count
    FROM etf_window WHERE adjustflag<>3 OR adjustflag IS NULL GROUP BY code,market
)
SELECT i.code,
       REPLACE(REPLACE(REPLACE(COALESCE(i.name,''),CHAR(9),' '),CHAR(10),' '),CHAR(13),' '),
       COALESCE(i.exchange,''),COALESCE(c.row_count,0),
       COALESCE(CAST(c.first_date AS CHAR),''),COALESCE(CAST(c.last_date AS CHAR),''),
       COALESCE(c.has_asof,0),COALESCE(c.invalid_active_rows,0),
       (SELECT COUNT(*) FROM calendar d WHERE d.trade_date>=c.first_date)-COALESCE(c.matched_calendar_days,0) AS missing_calendar_days,
       COALESCE(u.row_count,0) AS unexpected_adjustment_rows,
       COALESCE(c.has_last_trading_day,0),
       COALESCE(c.non_trading_rows,0)
FROM ${Database}.stock_info i
LEFT JOIN coverage c ON c.code=i.code AND c.market=i.exchange
LEFT JOIN unexpected u ON u.code=i.code AND u.market=i.exchange
WHERE i.code LIKE '58%' ORDER BY i.code,i.exchange;
"@
$profiles = @(Invoke-LocalSql $coverageSql | ForEach-Object {
    $fields = ([string]$_) -split "`t"
    if ($fields.Count -ne 12) { throw 'ETF 覆盖查询字段数量异常，停止生成不完整报告。' }
    [pscustomobject][ordered]@{
        code=$fields[0]; name=$fields[1]; market=$fields[2]; adjustFlag=3
        rows=[long]$fields[3]; firstDate=$fields[4]; lastDate=$fields[5]
        hasAsOf=[int]$fields[6]; invalidActiveRows=[long]$fields[7]
        missingCalendarDays=[long]$fields[8]; unexpectedAdjustmentRows=[long]$fields[9]
        hasLastTradingDay=[int]$fields[10];nonTradingRows=[long]$fields[11]
    }
})

# 异常口径按整个 ETF 分表窗口统计，避免未登记元数据或市场字段不匹配的异常行漏计。
# 模型隔离检查不限制日期：历史上任意一条 58 记录进入股票统一视图或模型表都应报告。
$isolationSql = @"
SELECT
    (SELECT COUNT(*) FROM ${Database}.stock_daily_58
     WHERE code LIKE '58%' AND trade_date BETWEEN '$HistoryStart' AND '$AsOf'
       AND (adjustflag<>3 OR adjustflag IS NULL)),
    (SELECT COUNT(*) FROM ${Database}.stock_daily_all WHERE code LIKE '58%'),
    (SELECT COUNT(*) FROM ${Database}.stock_score_daily WHERE code LIKE '58%'),
    (SELECT COUNT(*) FROM ${Database}.stock_forecast_prediction WHERE code LIKE '58%'),
    (SELECT COUNT(*) FROM ${Database}.prob_prediction WHERE code LIKE '58%');
"@
$isolationLines = @(Invoke-LocalSql $isolationSql)
if ($isolationLines.Count -ne 1) { throw 'ETF 模型隔离查询没有返回唯一汇总行。' }
$isolationFields = ([string]$isolationLines[0]) -split "`t"
if ($isolationFields.Count -ne 5) { throw 'ETF 模型隔离查询字段数量异常。' }
$unexpectedAdjustmentRows = [long]$isolationFields[0]
$stockDailyAllEtfRows = [long]$isolationFields[1]
$scoreEtfRows = [long]$isolationFields[2]
$shortTermEtfRows = [long]$isolationFields[3]
$probabilityEtfRows = [long]$isolationFields[4]

# 待核对名单包含晚起始 ETF，但不把其首条记录之前的交易日计算为缺失。
$attention = @($profiles | Where-Object {
    $_.rows -eq 0 -or ($calendar.lastTradingDate -and $_.hasLastTradingDay -eq 0) -or
    ($_.rows -gt 0 -and $_.firstDate -gt $HistoryStart) -or $_.invalidActiveRows -gt 0 -or
    $_.missingCalendarDays -gt 0 -or $_.unexpectedAdjustmentRows -gt 0 -or $_.nonTradingRows -gt 0
})
$detailFile = Join-Path $OutputDirectory 'coverage-by-etf.csv'
$attentionFile = Join-Path $OutputDirectory 'etf-attention.csv'
$csvTemplate = [pscustomobject][ordered]@{
    code=''; name=''; market=''; adjustFlag=3; rows=0; firstDate=''; lastDate=''
    hasAsOf=0; invalidActiveRows=0; missingCalendarDays=0; unexpectedAdjustmentRows=0
    hasLastTradingDay=0;nonTradingRows=0
}

Export-CoverageAuditCsv -Rows $profiles -Path $detailFile -Template $csvTemplate
Export-CoverageAuditCsv -Rows $attention -Path $attentionFile -Template $csvTemplate

$withData = @($profiles | Where-Object { $_.rows -gt 0 })
$totalRows = [long]0
$totalInvalidActiveRows = [long]0
$nonTradingRows=[long]0
foreach ($profile in $profiles) {
    # 空数组在 Windows PowerShell 5.1 严格模式下不保证 Measure-Object 返回 Sum 属性。
    # 显式从零累加，确保尚未登记任何 ETF 的环境也能生成完整且数值稳定的汇总。
    $totalRows += $profile.rows
    $totalInvalidActiveRows += $profile.invalidActiveRows
    $nonTradingRows += $profile.nonTradingRows
}
$firstDate = $null
$lastDate = $null
if ($withData.Count -gt 0) {
    # ISO 日期字符串可直接排序；只统计有不复权行情的 ETF，避免空值改变边界。
    $firstDate = $withData.firstDate | Sort-Object | Select-Object -First 1
    $lastDate = $withData.lastDate | Sort-Object -Descending | Select-Object -First 1
}
$issues=New-Object 'System.Collections.Generic.List[string]'
if ($calendar.tradingDays -gt 0 -and $profiles.Count -eq 0) { $issues.Add('ETF 池为空，尚未完成覆盖验收') }
if ($calendar.tradingDays -gt 0 -and @($profiles | Where-Object rows -eq 0).Count -gt 0) { $issues.Add('有 ETF 整段没有行情，不能当作新上市通过') }
if (@($profiles | Where-Object missingCalendarDays -gt 0).Count -gt 0) { $issues.Add('ETF 存在首条行情之后的交易日缺口') }
if ($calendar.lastTradingDate -and @($profiles | Where-Object hasLastTradingDay -eq 0).Count -gt 0) { $issues.Add('ETF 存在最后交易日缺行') }
if ($nonTradingRows -gt 0) { $issues.Add('ETF 存在非交易日行情') }
if ($totalInvalidActiveRows -gt 0) { $issues.Add('ETF 存在有效交易状态但收盘价无效的行情') }
if ($unexpectedAdjustmentRows -gt 0) { $issues.Add('ETF 包含不受支持的复权口径') }
if (($stockDailyAllEtfRows+$scoreEtfRows+$shortTermEtfRows+$probabilityEtfRows) -gt 0) { $issues.Add('ETF 数据进入了个股模型') }
$summary=[pscustomobject][ordered]@{
    historyStart=$HistoryStart; asOf=$AsOf; adjustFlag=3
    calendarSource=$calendar.calendarSource;lastTradingDate=$calendar.lastTradingDate
    tradingDays=$calendar.tradingDays;calendarFile=$calendar.calendarFile
    poolEtfs=$profiles.Count; dataEtfs=$withData.Count
    rows=$totalRows
    asOfEtfs=@($profiles | Where-Object { $_.hasAsOf -gt 0 }).Count
    lastTradingDayEtfs=@($profiles | Where-Object hasLastTradingDay -gt 0).Count
    firstDate=$firstDate; lastDate=$lastDate
    zeroHistoryEtfs=@($profiles | Where-Object { $_.rows -eq 0 }).Count
    laterStartEtfs=@($withData | Where-Object { $_.firstDate -gt $HistoryStart }).Count
    missingCalendarEtfs=@($profiles | Where-Object { $_.missingCalendarDays -gt 0 }).Count
    invalidActiveRows=$totalInvalidActiveRows
    nonTradingRows=$nonTradingRows
    unexpectedAdjustmentRows=$unexpectedAdjustmentRows
    stockDailyAllEtfRows=$stockDailyAllEtfRows; scoreEtfRows=$scoreEtfRows
    shortTermEtfRows=$shortTermEtfRows; probabilityEtfRows=$probabilityEtfRows
    modelIsolationViolationRows=[long]($stockDailyAllEtfRows+$scoreEtfRows+$shortTermEtfRows+$probabilityEtfRows)
    attentionRecords=$attention.Count; detailFile=$detailFile; attentionFile=$attentionFile
}
$finalReport=$true
Complete-CoverageAudit $summary @($issues.ToArray()) $summaryFile $calendar.strictCalendar
} catch {
    if (!$finalReport) { Save-IncompleteCoverageAudit $summaryFile $HistoryStart $AsOf }
    throw
}
}

Measure-EtfWindowCoverage $HistoryStart $AsOf $OutputDirectory
