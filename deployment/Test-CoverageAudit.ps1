<#
行情覆盖门禁的纯离线回归：只从 AST 装载 Measure 函数，SQL 执行器全部为内存替身。
不导入 Common、不连接数据库、不执行外部命令或启动进程；本地临时 CSV/JSON 用于核验失败证据。
#>
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$temporaryRoot=Join-Path $root ('.local\script-tests\'+[guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($temporaryRoot) | Out-Null
$passed=0
function Assert-CoverageTest { param([bool]$Value,[string]$Message); if (!$Value) { throw $Message } }
function Assert-CoverageThrows {
    param([scriptblock]$Action,[string]$Expected)
    $message=''
    try { & $Action | Out-Null } catch { $message=$_.Exception.Message }
    Assert-CoverageTest ($message -like "*$Expected*") "预期失败未发生：$Expected；实际：$message"
}
try {
    . (Join-Path $PSScriptRoot 'Coverage.Audit.ps1')
    foreach ($entry in @(@('Measure-MarketCoverage.ps1','Measure-StockWindowCoverage'),@('Measure-EtfCoverage.ps1','Measure-EtfWindowCoverage'))) {
        $tokens=$null;$errors=$null
        $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $entry[0]),[ref]$tokens,[ref]$errors)
        Assert-CoverageTest (@($errors).Count -eq 0) ('脚本无法解析：'+$entry[0])
        $name=$entry[1]
        $functionAst=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$false)
        Assert-CoverageTest ($null -ne $functionAst) ('缺少独立审计函数：'+$name)
        . ([scriptblock]::Create($functionAst.Extent.Text))
    }
    $Database='unit_test'
    function Reset-CoverageFixture {
        $script:from='2026-11-13';$script:to='2026-11-17';$script:calendarExists=$true
        $script:calendarLines=@("2026-11-13`t1","2026-11-14`t0","2026-11-15`t0","2026-11-16`t1","2026-11-17`t1")
        $script:prices=@{2=@('2026-11-13','2026-11-16','2026-11-17');3=@('2026-11-13','2026-11-16','2026-11-17')}
        $script:etfPrices=@('2026-11-13','2026-11-16','2026-11-17')
        $script:unexpectedEtfRows=0;$script:isolationRows=0
        $script:emptyUniverse=$false
        $script:sqlCalls=New-Object 'System.Collections.Generic.List[string]'
    }
    function Get-FakeTradingDates {
        if (!$script:calendarExists) { return @($script:prices[3] | Sort-Object -Unique) }
        return @($script:calendarLines | ForEach-Object {
            $fields=$_ -split "`t"
            if ($fields[1] -eq '1') { $fields[0] }
        } | Sort-Object -Unique)
    }
    function Get-FakeCoverageFields {
        param([string[]]$Dates)
        $ordered=@($Dates | Sort-Object);$trading=@(Get-FakeTradingDates)
        $first=if ($ordered.Count) {$ordered[0]} else {''};$last=if ($ordered.Count) {$ordered[-1]} else {''}
        $expectedEnd=if ($trading.Count) {$trading[-1]} else {''}
        $missing=if ($first) {@($trading | Where-Object { $_ -ge $first -and $_ -notin $ordered }).Count} else {0}
        return [pscustomobject]@{rows=$ordered.Count;first=$first;last=$last;asOf=[int]($script:to -in $ordered);
            missing=$missing;hasEnd=[int]($expectedEnd -and $expectedEnd -in $ordered);
            nonTrading=@($ordered | Where-Object { $_ -notin $trading }).Count}
    }
    function Invoke-LocalSql {
        param([string]$Sql)
        $script:sqlCalls.Add($Sql)
        if ($Sql -match 'information_schema.tables') { return [string][int]$script:calendarExists }
        if ($Sql -match '^SELECT CAST\(calendar_date AS CHAR\),is_trading_day') { return $script:calendarLines }
        if ($Sql -match '^SELECT COALESCE\(CAST\(MAX\(trade_date\)') {
            $dates=@(Get-FakeTradingDates);$last=if ($dates.Count) {$dates[-1]} else {''}
            return "$last`t$($dates.Count)"
        }
        if ($Sql -match '^WITH window_data AS') {
            if ($script:emptyUniverse) { return }
            foreach ($flag in 2,3) {
                $c=Get-FakeCoverageFields $script:prices[$flag]
                (@('000001','测试证券,甲','SZ',$flag,$c.rows,$c.first,$c.last,$c.asOf,0,$c.missing,$c.hasEnd,$c.nonTrading) -join "`t")
            }
            return
        }
        if ($Sql -match '^WITH etf_window AS') {
            if ($script:emptyUniverse) { return }
            $c=Get-FakeCoverageFields $script:etfPrices
            return (@('588000','测试ETF','SH',$c.rows,$c.first,$c.last,$c.asOf,0,$c.missing,$script:unexpectedEtfRows,$c.hasEnd,$c.nonTrading) -join "`t")
        }
        if ($Sql -match '\) mismatched;') {
            return [string](@($script:prices[2] | Where-Object { $_ -notin $script:prices[3] }).Count + @($script:prices[3] | Where-Object { $_ -notin $script:prices[2] }).Count)
        }
        if ($Sql -match 'stock_forecast_prediction') { return (@($script:unexpectedEtfRows,$script:isolationRows,0,0,0) -join "`t") }
        throw '测试遇到未声明的SQL，禁止转发到任何真实数据库。'
    }
    function Invoke-FakeAudit {
        param([ValidateSet('stock','etf')][string]$Kind,[string]$Case)
        $directory=Join-Path $temporaryRoot $Case
        if ($Kind -eq 'stock') { return Measure-StockWindowCoverage $script:from $script:to $directory }
        return Measure-EtfWindowCoverage $script:from $script:to $directory
    }
    function Assert-Evidence {
        param([string]$Case,[string]$Kind)
        $directory=Join-Path $temporaryRoot $Case
        $detail=if ($Kind -eq 'stock') {'coverage-by-stock.csv'} else {'coverage-by-etf.csv'}
        $summary=if ($Kind -eq 'stock') {'coverage-summary.json'} else {'etf-coverage-summary.json'}
        Assert-CoverageTest (Test-Path -LiteralPath (Join-Path $directory $detail)) '异常发生后没有保存逐证券CSV证据'
        $saved=Get-Content -LiteralPath (Join-Path $directory $summary) -Raw -Encoding UTF8 | ConvertFrom-Json
        Assert-CoverageTest (!$saved.strictValidated -and $saved.validationIssues.Count -gt 0) '异常报告错误地声明通过严格验收'
    }

    foreach ($kind in 'stock','etf') {
        Reset-CoverageFixture
        $result=Invoke-FakeAudit $kind ($kind+'-complete')
        Assert-CoverageTest ($result.strictValidated -and $result.calendarSource -eq 'crawl_trade_calendar' -and $result.lastTradingDate -eq '2026-11-17') '完整独立日历未用于验收'
        $coverageSql=@($script:sqlCalls | Where-Object { $_ -match '^WITH (window_data|etf_window) AS' })[0]
        Assert-CoverageTest ($coverageSql -match 'calendar AS \(SELECT calendar_date AS trade_date FROM unit_test.crawl_trade_calendar WHERE is_trading_day=1' -and
            $coverageSql -match 'COUNT\(DISTINCT CASE WHEN d.trade_date IS NOT NULL THEN [we].trade_date END\)' -and
            $coverageSql -match 'LEFT JOIN calendar d ON d.trade_date=[we].trade_date' -and
            $coverageSql -match 'COALESCE\(c.matched_calendar_days,0\)' -and
            $coverageSql -notmatch '\)-COALESCE\(c.row_count' -and $coverageSql -notmatch 'NOT EXISTS') 'SQL没有使用独立日历过滤去重集合或仍包含昂贵的逐日期行情相关回查'
        Assert-CoverageTest (@($script:sqlCalls | Where-Object { $_ -match '(?im)^\s*(INSERT\s+INTO|UPDATE\s+\w|DELETE\s+FROM|ALTER\s+TABLE|CREATE\s+TABLE|DROP\s+TABLE|REPLACE\s+INTO)\b' }).Count -eq 0) '覆盖审计生成了数据库写语句'
        $passed++

        # 两复权共同缺同一天，pair count仍为0；独立日历门禁必须拦截，不能进入后续模型。
        Reset-CoverageFixture
        $script:prices[2]=@('2026-11-13','2026-11-17');$script:prices[3]=@('2026-11-13','2026-11-17');$script:etfPrices=@('2026-11-13','2026-11-17')
        Assert-CoverageThrows { Invoke-FakeAudit $kind ($kind+'-shared-gap') } '交易日缺口'
        Assert-Evidence ($kind+'-shared-gap') $kind
        $passed++

        # 行数未减少但日期错位：新增周六行不能抵消周一缺行，CSV须同时保留两种异常。
        Reset-CoverageFixture
        $script:prices[2]=@('2026-11-13','2026-11-14','2026-11-17');$script:prices[3]=$script:prices[2];$script:etfPrices=$script:prices[2]
        Assert-CoverageThrows { Invoke-FakeAudit $kind ($kind+'-wrong-date') } '非交易日行情'
        Assert-Evidence ($kind+'-wrong-date') $kind
        $passed++

        Reset-CoverageFixture
        $script:prices[2]=@('2026-11-13','2026-11-16');$script:prices[3]=$script:prices[2];$script:etfPrices=$script:prices[2]
        Assert-CoverageThrows { Invoke-FakeAudit $kind ($kind+'-missing-end') } '最后交易日缺行'
        Assert-Evidence ($kind+'-missing-end') $kind
        $passed++

        # 末日为周日时验收周五，不要求人为插入周末行情。
        Reset-CoverageFixture;$script:to='2026-11-15';$script:calendarLines=$script:calendarLines[0..2]
        $script:prices[2]=@('2026-11-13');$script:prices[3]=$script:prices[2];$script:etfPrices=$script:prices[2]
        $result=Invoke-FakeAudit $kind ($kind+'-weekend-end')
        Assert-CoverageTest ($result.strictValidated -and $result.lastTradingDate -eq '2026-11-13') '周末窗口末日被误判为缺行'
        $coverageSql=@($script:sqlCalls | Where-Object { $_ -match '^WITH (window_data|etf_window) AS' })[0]
        Assert-CoverageTest ($coverageSql -match "MAX\([we].trade_date='2026-11-13'\) AS has_last_trading_day") 'SQL末日检查没有使用最后交易日'
        $passed++

        Reset-CoverageFixture
        $script:prices[2]=@('2026-11-16','2026-11-17');$script:prices[3]=$script:prices[2];$script:etfPrices=$script:prices[2]
        $result=Invoke-FakeAudit $kind ($kind+'-late-start')
        Assert-CoverageTest ($result.strictValidated -and $result.attentionRecords -gt 0) '晚起始未保留注意项或被直接认定缺口'
        $passed++

        Reset-CoverageFixture;$script:prices[2]=@();$script:prices[3]=@();$script:etfPrices=@()
        Assert-CoverageThrows { Invoke-FakeAudit $kind ($kind+'-zero-history') } '整段没有行情'
        Assert-Evidence ($kind+'-zero-history') $kind
        $passed++

        Reset-CoverageFixture;$script:emptyUniverse=$true
        Assert-CoverageThrows { Invoke-FakeAudit $kind ($kind+'-empty-pool') } '池为空'
        Assert-Evidence ($kind+'-empty-pool') $kind
        $passed++

        Reset-CoverageFixture;$script:calendarExists=$false
        $script:prices[2]=@('2026-11-13','2026-11-17');$script:prices[3]=$script:prices[2];$script:etfPrices=$script:prices[2]
        $result=Invoke-FakeAudit $kind ($kind+'-legacy')
        Assert-CoverageTest (!$result.strictValidated -and $result.calendarSource -eq 'observed_raw_prices') '旧版推导日历错误声明完整验收'
        $passed++
    }

    foreach ($corruption in 'missing-holiday','invalid-status','duplicate') {
        Reset-CoverageFixture
        if ($corruption -eq 'missing-holiday') { $script:calendarLines=@($script:calendarLines | Where-Object { $_ -notmatch '^2026-11-14' }) }
        if ($corruption -eq 'invalid-status') { $script:calendarLines[1]="2026-11-14`t2" }
        if ($corruption -eq 'duplicate') { $script:calendarLines+=@("2026-11-13`t1") }
        Assert-CoverageThrows { Invoke-FakeAudit 'stock' $corruption } '日历不完整或无效'
        $evidence=Import-Csv -LiteralPath (Join-Path $temporaryRoot "$corruption\coverage-calendar.csv") -Encoding UTF8
        Assert-CoverageTest (@($evidence | Where-Object issue -ne '').Count -gt 0) '坏日历没有留下CSV证据'
        Assert-CoverageTest (@($script:sqlCalls | Where-Object { $_ -match '^WITH ' }).Count -eq 0) '坏日历被悄悄退回行情推导'
        $passed++
    }

    Reset-CoverageFixture;$script:unexpectedEtfRows=1
    Assert-CoverageThrows { Invoke-FakeAudit 'etf' 'etf-unsupported-flag' } '不受支持的复权口径'
    Assert-Evidence 'etf-unsupported-flag' 'etf'
    Reset-CoverageFixture;$script:isolationRows=1
    Assert-CoverageThrows { Invoke-FakeAudit 'etf' 'etf-model-isolation' } '进入了个股模型'
    Assert-Evidence 'etf-model-isolation' 'etf'
    $passed++
    Write-Host "PASS: $passed 组覆盖审计离线回归；只使用内存SQL替身与临时CSV/JSON。"
} finally {
    $safeRoot=[IO.Path]::GetFullPath((Join-Path $root '.local\script-tests')).TrimEnd('\')+'\'
    $resolved=[IO.Path]::GetFullPath($temporaryRoot)
    if ($resolved.StartsWith($safeRoot,[StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolved)) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
