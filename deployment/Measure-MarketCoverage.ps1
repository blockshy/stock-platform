<#
.SYNOPSIS
只读统计指定三年窗口内每只股票的两种复权行情覆盖情况。
.DESCRIPTION
按股票池逐只核验，避免仅检查全库总行数而遗漏整只股票。
上市不足三年、停牌和数据缺口均保留原始统计，不补造价格，也不将晚起始日自动认定为新股。
结果 CSV 使用 UTF-8；输出对象仅包含汇总，不包含数据库连接信息。
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

function Measure-StockWindowCoverage {
<# 独立函数支持纯内存审计回归；只调用已有 SELECT 执行器，不创建或继续采集任务。 #>
param([string]$HistoryStart,[string]$AsOf,[string]$OutputDirectory)
$OutputDirectory=[IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$summaryFile=Join-Path $OutputDirectory 'coverage-summary.json'
$finalReport=$false
Save-IncompleteCoverageAudit $summaryFile $HistoryStart $AsOf
try {
$calendar=Get-CoverageAuditCalendar $Database $HistoryStart $AsOf (Join-Path $OutputDirectory 'coverage-calendar.csv')
$endLiteral=if ($calendar.lastTradingDate) { "'$($calendar.lastTradingDate)'" } else { 'NULL' }
# 独立日历完整覆盖自然日，避免两口径共同漏日被误当休市；首条较晚仍须结合上市资料另行核对。
# 先按日历成员过滤并对日期去重，再比较集合大小；周末多余行不会抵消缺口。
# 行情与日历只关联聚合一次，不按“证券×交易日”反复相关扫描全市场行情CTE。
# 保留 hasAsOf 原义，新增 hasLastTradingDay 用于期末验收，周末截止窗口不强求周末行情。
$sql = @"
WITH window_data AS (
    SELECT code,market,adjustflag,trade_date,close,tradestatus,is_st
    FROM ${Database}.stock_daily_all
    WHERE trade_date BETWEEN '$HistoryStart' AND '$AsOf' AND adjustflag IN (2,3)
), calendar AS ($($calendar.calendarSql)), coverage AS (
    SELECT w.code,w.market,w.adjustflag,COUNT(*) AS row_count,MIN(w.trade_date) AS first_date,
           MAX(w.trade_date) AS last_date,
           MAX(w.trade_date='$AsOf') AS has_asof,
           MAX(w.trade_date=$endLiteral) AS has_last_trading_day,
           SUM(COALESCE(w.tradestatus,1)<>0 AND (w.close IS NULL OR w.close<=0)) AS invalid_active_rows,
           COUNT(DISTINCT CASE WHEN d.trade_date IS NOT NULL THEN w.trade_date END) AS matched_calendar_days,
           SUM(d.trade_date IS NULL) AS non_trading_rows
    FROM window_data w LEFT JOIN calendar d ON d.trade_date=w.trade_date
    GROUP BY w.code,w.market,w.adjustflag
)
SELECT i.code,REPLACE(REPLACE(REPLACE(COALESCE(i.name,''),CHAR(9),' '),CHAR(10),' '),CHAR(13),' '),i.exchange,f.adjustflag,COALESCE(c.row_count,0),
       COALESCE(CAST(c.first_date AS CHAR),''),COALESCE(CAST(c.last_date AS CHAR),''),
       COALESCE(c.has_asof,0),COALESCE(c.invalid_active_rows,0),
       (SELECT COUNT(*) FROM calendar d WHERE d.trade_date>=c.first_date)-COALESCE(c.matched_calendar_days,0) AS missing_calendar_days,
       COALESCE(c.has_last_trading_day,0),
       COALESCE(c.non_trading_rows,0)
FROM ${Database}.stock_info i CROSS JOIN (SELECT 2 AS adjustflag UNION ALL SELECT 3) f
LEFT JOIN coverage c ON c.code=i.code AND c.market=i.exchange AND c.adjustflag=f.adjustflag
WHERE LEFT(i.code,2) IN ('00','30','60') ORDER BY i.code,f.adjustflag;
"@
$profiles = @(Invoke-LocalSql $sql | ForEach-Object {
    $fields = ([string]$_) -split "`t"
    if ($fields.Count -ne 12) { throw '股票覆盖查询字段数量异常，停止生成不完整报告。' }
    [pscustomobject][ordered]@{
        code=$fields[0]; name=$fields[1]; market=$fields[2]; adjustFlag=[int]$fields[3]
        rows=[long]$fields[4]; firstDate=$fields[5]; lastDate=$fields[6]
        hasAsOf=[int]$fields[7]; invalidActiveRows=[long]$fields[8]; missingCalendarDays=[long]$fields[9]
        hasLastTradingDay=[int]$fields[10];nonTradingRows=[long]$fields[11]
    }
})
$attention = @($profiles | Where-Object {
    $_.rows -eq 0 -or ($calendar.lastTradingDate -and $_.hasLastTradingDay -eq 0) -or
    ($_.rows -gt 0 -and $_.firstDate -gt $HistoryStart) -or $_.invalidActiveRows -gt 0 -or
    $_.missingCalendarDays -gt 0 -or $_.nonTradingRows -gt 0
})
$template=[pscustomobject][ordered]@{
    code='';name='';market='';adjustFlag=0;rows=0;firstDate='';lastDate=''
    hasAsOf=0;invalidActiveRows=0;missingCalendarDays=0;hasLastTradingDay=0;nonTradingRows=0
}
Export-CoverageAuditCsv $profiles (Join-Path $OutputDirectory 'coverage-by-stock.csv') $template
Export-CoverageAuditCsv $attention (Join-Path $OutputDirectory 'coverage-attention.csv') $template
# 两种口径应有相同的日期键；分别比较起止日期或条数无法发现日期集合错位。
$pairSql = @"
SELECT COUNT(*) FROM (
    SELECT code,market,trade_date FROM ${Database}.stock_daily_all
    WHERE trade_date BETWEEN '$HistoryStart' AND '$AsOf' AND adjustflag IN (2,3)
    GROUP BY code,market,trade_date HAVING COUNT(DISTINCT adjustflag)<>2
) mismatched;
"@
$mismatchedDates = [long](Invoke-LocalSql $pairSql)
$issues=New-Object 'System.Collections.Generic.List[string]'
if ($calendar.tradingDays -gt 0 -and $profiles.Count -eq 0) { $issues.Add('股票池为空，尚未完成覆盖验收') }
if ($calendar.tradingDays -gt 0 -and @($profiles | Where-Object rows -eq 0).Count -gt 0) { $issues.Add('有股票整段没有行情，不能当作新股通过') }
if (@($profiles | Where-Object missingCalendarDays -gt 0).Count -gt 0) { $issues.Add('存在首条行情之后的交易日缺口') }
if ($calendar.lastTradingDate -and @($profiles | Where-Object hasLastTradingDay -eq 0).Count -gt 0) { $issues.Add('存在最后交易日缺行') }
if (@($profiles | Where-Object nonTradingRows -gt 0).Count -gt 0) { $issues.Add('存在非交易日行情') }
if (@($profiles | Where-Object invalidActiveRows -gt 0).Count -gt 0) { $issues.Add('存在有效交易状态但收盘价无效的行情') }
if ($mismatchedDates -gt 0) { $issues.Add('两种复权口径的日期键不一致') }
$summary=[pscustomobject][ordered]@{
    historyStart=$HistoryStart; asOf=$AsOf; poolStocks=[int]($profiles.Count/2)
    calendarSource=$calendar.calendarSource;lastTradingDate=$calendar.lastTradingDate
    tradingDays=$calendar.tradingDays;calendarFile=$calendar.calendarFile
    flags=@(foreach ($flag in 2,3) {
        $subset = @($profiles | Where-Object { $_.adjustFlag -eq $flag })
        $rows=0L;$invalid=0L;$nonTrading=0L
        foreach ($item in $subset) { $rows+=$item.rows;$invalid+=$item.invalidActiveRows;$nonTrading+=$item.nonTradingRows }
        [pscustomobject][ordered]@{
            adjustFlag=$flag; stocks=@($subset | Where-Object { $_.rows -gt 0 }).Count
            rows=$rows
            asOfStocks=@($subset | Where-Object { $_.hasAsOf -eq 1 }).Count
            lastTradingDayStocks=@($subset | Where-Object hasLastTradingDay -gt 0).Count
            laterStartStocks=@($subset | Where-Object { $_.rows -gt 0 -and $_.firstDate -gt $HistoryStart }).Count
            gapStocks=@($subset | Where-Object { $_.missingCalendarDays -gt 0 }).Count
            invalidActiveRows=$invalid;nonTradingRows=$nonTrading
        }
    })
    mismatchedAdjustmentDates=$mismatchedDates; attentionRecords=$attention.Count
    detailFile=(Join-Path $OutputDirectory 'coverage-by-stock.csv')
    attentionFile=(Join-Path $OutputDirectory 'coverage-attention.csv')
}
$finalReport=$true
Complete-CoverageAudit $summary @($issues.ToArray()) $summaryFile $calendar.strictCalendar
} catch {
    if (!$finalReport) { Save-IncompleteCoverageAudit $summaryFile $HistoryStart $AsOf }
    throw
}
}

Measure-StockWindowCoverage $HistoryStart $AsOf $OutputDirectory
