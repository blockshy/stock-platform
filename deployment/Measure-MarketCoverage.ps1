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
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
# 交易日历取自同窗口内全市场实际返回的原始日线，不把周末和休市日算作缺失。
# 缺失数只计算首条记录至截止日；首条较晚必须结合上市资料判断，不能直接断言完整。
$sql = @"
WITH window_data AS (
    SELECT code,market,adjustflag,trade_date,close,tradestatus,is_st
    FROM ${Database}.stock_daily_all
    WHERE trade_date BETWEEN '$HistoryStart' AND '$AsOf' AND adjustflag IN (2,3)
), coverage AS (
    SELECT code,market,adjustflag,COUNT(*) AS row_count,MIN(trade_date) AS first_date,
           MAX(trade_date) AS last_date,
           SUM(trade_date='$AsOf') AS has_asof,
           SUM(COALESCE(tradestatus,1)<>0 AND (close IS NULL OR close<=0)) AS invalid_active_rows
    FROM window_data GROUP BY code,market,adjustflag
), calendar AS (SELECT DISTINCT trade_date FROM window_data WHERE adjustflag=3)
SELECT i.code,i.name,i.exchange,f.adjustflag,COALESCE(c.row_count,0),
       COALESCE(CAST(c.first_date AS CHAR),''),COALESCE(CAST(c.last_date AS CHAR),''),
       COALESCE(c.has_asof,0),COALESCE(c.invalid_active_rows,0),
       (SELECT COUNT(*) FROM calendar d WHERE d.trade_date>=c.first_date)-COALESCE(c.row_count,0)
FROM ${Database}.stock_info i CROSS JOIN (SELECT 2 AS adjustflag UNION ALL SELECT 3) f
LEFT JOIN coverage c ON c.code=i.code AND c.market=i.exchange AND c.adjustflag=f.adjustflag
WHERE LEFT(i.code,2) IN ('00','30','60') ORDER BY i.code,f.adjustflag;
"@
$profiles = @(Invoke-LocalSql $sql | ForEach-Object {
    $fields = $_ -split "`t"
    [pscustomobject][ordered]@{
        code=$fields[0]; name=$fields[1]; market=$fields[2]; adjustFlag=[int]$fields[3]
        rows=[int]$fields[4]; firstDate=$fields[5]; lastDate=$fields[6]
        hasAsOf=[int]$fields[7]; invalidActiveRows=[int]$fields[8]; missingCalendarDays=[int]$fields[9]
    }
})
$profiles | Export-Csv -LiteralPath (Join-Path $OutputDirectory 'coverage-by-stock.csv') -NoTypeInformation -Encoding UTF8
$attention = @($profiles | Where-Object { $_.rows -eq 0 -or $_.hasAsOf -eq 0 -or $_.firstDate -gt $HistoryStart -or $_.invalidActiveRows -gt 0 -or $_.missingCalendarDays -gt 0 })
$attention | Export-Csv -LiteralPath (Join-Path $OutputDirectory 'coverage-attention.csv') -NoTypeInformation -Encoding UTF8
# 两种口径应有相同的日期键；分别比较起止日期或条数无法发现日期集合错位。
$pairSql = @"
SELECT COUNT(*) FROM (
    SELECT code,market,trade_date FROM ${Database}.stock_daily_all
    WHERE trade_date BETWEEN '$HistoryStart' AND '$AsOf' AND adjustflag IN (2,3)
    GROUP BY code,market,trade_date HAVING COUNT(DISTINCT adjustflag)<>2
) mismatched;
"@
$mismatchedDates = [long](Invoke-LocalSql $pairSql)
[pscustomobject][ordered]@{
    historyStart=$HistoryStart; asOf=$AsOf; poolStocks=[int]($profiles.Count/2)
    flags=@(foreach ($flag in 2,3) {
        $subset = @($profiles | Where-Object { $_.adjustFlag -eq $flag })
        [pscustomobject][ordered]@{
            adjustFlag=$flag; stocks=@($subset | Where-Object { $_.rows -gt 0 }).Count
            rows=[long](($subset | Measure-Object rows -Sum).Sum)
            asOfStocks=@($subset | Where-Object { $_.hasAsOf -eq 1 }).Count
            laterStartStocks=@($subset | Where-Object { $_.firstDate -gt $HistoryStart }).Count
            gapStocks=@($subset | Where-Object { $_.missingCalendarDays -gt 0 }).Count
            invalidActiveRows=[long](($subset | Measure-Object invalidActiveRows -Sum).Sum)
        }
    })
    mismatchedAdjustmentDates=$mismatchedDates; attentionRecords=$attention.Count
    detailFile=(Join-Path $OutputDirectory 'coverage-by-stock.csv')
    attentionFile=(Join-Path $OutputDirectory 'coverage-attention.csv')
}
