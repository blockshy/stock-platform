<#
.SYNOPSIS
等待全市场历史行情采集完成，核验覆盖范围后串行执行三种计算。
.DESCRIPTION
这是本次用户授权任务的持久化执行器，不注册计划任务或开机自启。
接续指定的采集任务；股票失败时最多执行三轮有界增量补采，再核验覆盖范围，避免把部分结果报告为完成。
全部进度保存在 .local/market-pipeline，凭据仅由公共数据库和启动脚本读取。
#>
param(
    [Parameter(Mandatory=$true)][long]$CrawlTaskId,
    [Parameter(Mandatory=$true)][ValidatePattern('^\d{4}-\d{2}-\d{2}$')][string]$AsOf,
    [ValidatePattern('^\d{4}-\d{2}-\d{2}$')][string]$HistoryStart='2023-11-17',
    [switch]$IncludeEtf58,
    [ValidateSet('production')][string]$Profile='production',
    [string]$ConfigPath=''
)
. (Join-Path $PSScriptRoot 'Common.ps1') -Profile $Profile -ConfigPath $ConfigPath
$pipelineDir = Join-Path $LocalRoot 'market-pipeline'
New-Item -ItemType Directory -Path $pipelineDir -Force | Out-Null
$statePath = Join-Path $pipelineDir 'state.json'
$eventsPath = Join-Path $pipelineDir 'events.jsonl'
$guardPath = Join-Path $pipelineDir 'runner.lock'
$guard = $null
$keepAwake = $false
$state = [ordered]@{ status='RUNNING'; stage='CRAWL'; crawlTaskId=$CrawlTaskId; crawlRootTaskId=$CrawlTaskId; crawlAttempts=@(); asOf=$AsOf; historyStart=$HistoryStart; startedAt=[DateTimeOffset]::Now.ToString('o') }
if ($IncludeEtf58) {
    # ETF 使用独立任务、独立不复权分表和独立覆盖验收，不改变原股票任务的 4,605 只计数。
    $state.etfPlan = [ordered]@{status='QUEUED';codePrefixes='58';adjustFlag=3;from=$HistoryStart;to=$AsOf;includeInStockModels=$false}
}

function Save-PipelineState {
    <# 用临时文件替换状态文件，避免读取者遇到写了一半的 JSON；日志不包含完整概率配置。 #>
    param([switch]$Event)
    $state.updatedAt = [DateTimeOffset]::Now.ToString('o')
    $json = $state | ConvertTo-Json -Depth 10
    $temporary = "$statePath.tmp"
    Write-Utf8File $temporary $json
    Move-Item -LiteralPath $temporary -Destination $statePath -Force
    if ($Event) { [IO.File]::AppendAllText($eventsPath, ($state | ConvertTo-Json -Depth 10 -Compress) + [Environment]::NewLine, [Text.UTF8Encoding]::new($false)) }
}

function Invoke-ProjectApi {
    <# 只访问固定的本机后端；POST 超时设为一小时，允许同步的全市场评分/短期计算完成。 #>
    param([string]$Path, [string]$Method='Get', [object]$Body=$null)
    $timeout = if ($Method -eq 'Get') { 30 } else { 3600 }
    $arguments = @{ Uri="http://127.0.0.1:$ApiPort$Path"; Method=$Method; TimeoutSec=$timeout }
    if ($null -ne $Body) { $arguments.ContentType='application/json'; $arguments.Body=$Body | ConvertTo-Json -Compress }
    # Windows PowerShell 5.1 对未声明 charset 的 JSON 会按错误编码解码；直接读取原始 UTF-8 字节。
    # 只重试只读查询。POST 可能已经被服务端接受，自动重发会重复创建预测批次。
    $attempts = if ($Method -eq 'Get') { 3 } else { 1 }
    for ($attempt=1; $attempt -le $attempts; $attempt++) {
        try { $http = Invoke-WebRequest @arguments -UseBasicParsing; break }
        catch { if ($attempt -eq $attempts) { throw }; Start-Sleep -Seconds 5 }
    }
    $response = [Text.Encoding]::UTF8.GetString($http.RawContentStream.ToArray()) | ConvertFrom-Json
    if ($response.code -ne 0) { throw "接口 $Path 返回错误：$($response.message)" }
    return $response.data
}

function Restore-StockCrawlState {
    <# 同一任务链重启 watcher 时保留已完成轮次与最新任务 ID，不能通过重启重置三轮上限。 #>
    param([object]$Previous)
    if ($null -eq $Previous) { return }
    $savedId = $Previous.PSObject.Properties['crawlTaskId']
    $savedRoot = $Previous.PSObject.Properties['crawlRootTaskId']
    $savedAsOf = $Previous.PSObject.Properties['asOf']
    $savedFrom = $Previous.PSObject.Properties['historyStart']
    if (!$savedId -or !$savedAsOf -or !$savedFrom) { return }
    $rootId = if ($savedRoot) { [long]$savedRoot.Value } else { [long]$savedId.Value }
    if (($CrawlTaskId -ne [long]$savedId.Value -and $CrawlTaskId -ne $rootId) -or
        $savedAsOf.Value -ne $AsOf -or $savedFrom.Value -ne $HistoryStart) { return }
    $state.crawlTaskId = [long]$savedId.Value
    $state.crawlRootTaskId = $rootId
    $savedAttempts = $Previous.PSObject.Properties['crawlAttempts']
    if ($savedAttempts) { $state.crawlAttempts = @($savedAttempts.Value) }
    foreach ($name in @('crawlRetryStatus','crawlRetryNotBefore')) {
        $property = $Previous.PSObject.Properties[$name]
        if ($property) { $state[$name] = $property.Value }
    }
    # POST 超时或进程在返回 ID 前退出时，服务器可能已经创建任务；禁止自动再次提交。
    # 保留已知任务链及未确认状态，供只读核对任务列表后再恢复，避免产生重复采集任务。
    if ($state.Contains('crawlRetryStatus') -and
        $state.crawlRetryStatus -in @('SUBMITTING','SUBMISSION_UNCONFIRMED')) {
        throw '上次增量补采提交结果尚未确认，请先核对采集任务列表；本次未重复提交。'
    }
}

function Wait-StockCrawlWithRetries {
    <# 原任务计为第一轮；仅在上一轮结束且已取得股票池后补采，最多再补两轮。 #>
    $maxRounds = 3
    while ($true) {
        $activeTaskId = [long]$state.crawlTaskId
        $state.stage = 'CRAWL'
        while ($true) {
            $currentCrawl = Invoke-ProjectApi "/api/data-crawler/tasks/$activeTaskId"
            $state.crawl = $currentCrawl | Select-Object id,totalCount,successCount,failCount,status,startTime,endTime,remark
            Save-PipelineState
            if ($currentCrawl.status -ne 'running') { break }
            Start-Sleep -Seconds 30
        }

        # 完整保存每轮终态备注（包含失败证券明细），成功数是本轮全股票池计数，不能跨轮相加。
        # watcher 在退避期间重启时会再次读到同一终态；先去除该 ID，保证轮次不会重复累计。
        $completed = $currentCrawl | Select-Object id,totalCount,successCount,failCount,status,startTime,endTime,remark
        $state.crawlAttempts = @($state.crawlAttempts | Where-Object { [long]$_.id -ne $activeTaskId }) + @($completed)
        Save-PipelineState -Event
        if ($currentCrawl.totalCount -gt 0 -and $currentCrawl.status -eq 'done' -and
            $currentCrawl.failCount -eq 0 -and $currentCrawl.successCount -eq $currentCrawl.totalCount) {
            $state.crawlRetryStatus = 'COMPLETE'
            Save-PipelineState -Event
            return $currentCrawl
        }
        # 未建立股票池、异常状态或连续三轮失败都保留原始原因，不盲目启动新的全量抓取。
        if ($currentCrawl.totalCount -le 0 -or $currentCrawl.status -notin @('done','failed')) {
            throw "采集任务 $activeTaskId 未取得有效股票池或状态异常：$($currentCrawl.remark)"
        }
        if ($state.crawlAttempts.Count -ge $maxRounds) {
            throw "股票增量采集已达到 $maxRounds 轮上限，任务 $activeTaskId 仍未完整成功：$($currentCrawl.remark)"
        }

        # 每轮补采前退避 60 秒；截止时刻持久化，watcher 重启不会丢失这段等待状态。
        if (!$state.Contains('crawlRetryStatus') -or $state.crawlRetryStatus -ne 'WAITING') {
            $state.crawlRetryNotBefore = [DateTimeOffset]::UtcNow.AddSeconds(60).ToString('o')
        }
        $state.crawlRetryStatus = 'WAITING'
        $state.stage = 'CRAWL_RETRY_WAIT'
        Save-PipelineState -Event
        $remaining = [Math]::Max(0, [Math]::Ceiling(([DateTimeOffset]$state.crawlRetryNotBefore - [DateTimeOffset]::UtcNow).TotalSeconds))
        if ($remaining -gt 0) { Start-Sleep -Seconds ([int]$remaining) }
        $state.crawlRetryStatus = 'SUBMITTING'
        Save-PipelineState -Event
        try {
            # 服务端已修复 fqType 与 adjustflag 的范围映射：完整区间会跳过请求，只补缺失前后段。
            # 固定原来的股票池与日期；不刷新名单、不改限速、不把 ETF 混入该重试链。
            $nextCrawl = Invoke-ProjectApi '/api/data-crawler/tasks' 'Post' @{
                limit=0;codePrefixes='00,30,60';fqType=1;storeRawPrices=$true;requestDelayMillis=1500
                refreshStockList=$false;from=$HistoryStart;to=$AsOf
            }
            if ($null -eq $nextCrawl -or !$nextCrawl.PSObject.Properties['id'] -or [long]$nextCrawl.id -le 0) {
                throw '增量补采接口没有返回有效任务 ID。'
            }
        } catch {
            $state.crawlRetryStatus = 'SUBMISSION_UNCONFIRMED'
            Save-PipelineState -Event
            throw "增量补采提交结果未确认，请先核对任务列表，未自动重发：$($_.Exception.Message)"
        }
        $state.crawlTaskId = [long]$nextCrawl.id
        $state.crawl = $nextCrawl | Select-Object id,totalCount,successCount,failCount,status,startTime,endTime,remark
        $state.crawlRetryStatus = 'SUBMITTED'
        $state.stage = 'CRAWL'
        Save-PipelineState -Event
    }
}

try {
    # 文件独占锁可跨进程阻止重复执行器，进程退出后由系统自动释放。
    $guard = [IO.File]::Open($guardPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    # 拿到独占锁之后才恢复旧状态，避免与仍在运行的 watcher 竞争读取/覆盖任务链。
    if (Test-Path -LiteralPath $statePath) {
        # Save-PipelineState 写入无 BOM UTF-8；Windows PowerShell 5.1 默认按系统代码页读文件，
        # 必须显式指定同一编码，避免中文备注被误解码后导致 JSON 解析失败。
        Restore-StockCrawlState (Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json)
    }
    # 长任务期间只申请当前线程的防自动休眠状态；不修改全局电源计划，屏幕仍可正常熄灭。
    # 任务结束或进程退出时请求会释放，不能阻止用户手动休眠或关机。
    Add-Type -TypeDefinition @'
using System.Runtime.InteropServices;
namespace StockLocalDeployment {
    /// <summary>封装 Windows 线程级电源请求，确保长时间采集和计算不会因空闲自动休眠而中断。</summary>
    public static class PowerRequest {
        /// <summary>设置线程执行状态；返回零表示申请失败，非零表示之前的执行状态。</summary>
        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern uint SetThreadExecutionState(uint flags);
    }
}
'@
    $keepAwake = [StockLocalDeployment.PowerRequest]::SetThreadExecutionState([uint32]2147483649) -ne 0
    $state.preventAutomaticSleep = $keepAwake
    Save-PipelineState -Event
    $crawl = Wait-StockCrawlWithRetries

    $state.stage='VALIDATE_DATA'
    Save-PipelineState -Event
    # 成功计数包含返回零行的情况；逐股审计三年窗口，并单独保存新股/停牌/缺口待核对名单。
    $state.coverage = & (Join-Path $PSScriptRoot 'Measure-MarketCoverage.ps1') -HistoryStart $HistoryStart -AsOf $AsOf -OutputDirectory $pipelineDir -Profile $Profile -ConfigPath $ConfigPath
    Save-PipelineState -Event
    if ($state.coverage.poolStocks -ne $crawl.totalCount -or @($state.coverage.flags | Where-Object { $_.stocks -ne $crawl.totalCount }).Count -gt 0) {
        throw '两种复权口径的实际股票数与采集股票池不一致，需要检查零行情股票后再计算。'
    }
    if ($state.coverage.mismatchedAdjustmentDates -gt 0 -or @($state.coverage.flags | Where-Object { $_.invalidActiveRows -gt 0 }).Count -gt 0) {
        throw '行情存在两种复权日期不匹配或交易日收盘价无效的记录，需要检查覆盖报告。'
    }

    # 采集结束后才重启，以加载全市场批量写入修复和调整后的 Java 内存配置。
    # Stop.ps1 会拒绝中断用户另行启动的计算任务；本执行器不会强制结束其它任务。
    $state.stage='RESTART_BACKEND'
    Save-PipelineState -Event
    & (Join-Path $PSScriptRoot 'Stop.ps1') -Profile $Profile -ConfigPath $ConfigPath
    $state.stage='BUILD_BACKEND'
    Save-PipelineState -Event
    # Java 退出后才能在 Windows 安全替换正在运行的 JAR；源码与单元测试已提前验证。
    Push-Location $ApiRoot
    try {
        & mvn.cmd -B -ntp -s (Join-Path $PSScriptRoot 'maven-settings.xml') `
            -gs (Join-Path $PSScriptRoot 'maven-global-settings.xml') `
            "-Dmaven.repo.local=$(Join-Path $LocalRoot 'maven')" -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw '后端打包失败，请检查 runner.out.log；尚未开始评分或预测。' }
    } finally { Pop-Location }
    & (Join-Path $PSScriptRoot 'Start.ps1') -Profile $Profile -ConfigPath $ConfigPath

    if ($IncludeEtf58) {
        $state.stage='ETF_CRAWL'
        $state.etfPlan.status='RUNNING'
        Save-PipelineState -Event
        # 新 JAR 才支持 58 分表与显式起止日；等股票任务结束后顺序接入，不中断原采集连接。
        # 数据源对 ETF 前复权请求仍返回 adjustflag=3，因此只请求和保存真实的不复权日线。
        $etfStarted = Invoke-ProjectApi '/api/data-crawler/tasks' 'Post' @{
            limit=0;codePrefixes='58';fqType=0;storeRawPrices=$false;requestDelayMillis=1500
            refreshStockList=$true;from=$HistoryStart;to=$AsOf
        }
        $state.etfCrawlTaskId = [long]$etfStarted.id
        Save-PipelineState -Event
        while ($true) {
            $etfCrawl = Invoke-ProjectApi "/api/data-crawler/tasks/$($state.etfCrawlTaskId)"
            $state.etfCrawl = $etfCrawl | Select-Object id,totalCount,successCount,failCount,status,startTime,endTime,remark
            Save-PipelineState
            if ($etfCrawl.status -ne 'running') { break }
            Start-Sleep -Seconds 30
        }
        if ($etfCrawl.status -ne 'done' -or $etfCrawl.totalCount -le 0 -or $etfCrawl.failCount -gt 0 -or $etfCrawl.successCount -ne $etfCrawl.totalCount) {
            throw "ETF 采集未完整成功，任务 $($state.etfCrawlTaskId)：$($etfCrawl.remark)"
        }
        $state.stage='VALIDATE_ETF'
        Save-PipelineState -Event
        $state.etfCoverage = & (Join-Path $PSScriptRoot 'Measure-EtfCoverage.ps1') -HistoryStart $HistoryStart -AsOf $AsOf -OutputDirectory $pipelineDir -Profile $Profile -ConfigPath $ConfigPath
        Save-PipelineState -Event
        if ($state.etfCoverage.poolEtfs -ne $etfCrawl.totalCount -or $state.etfCoverage.dataEtfs -ne $etfCrawl.totalCount -or $state.etfCoverage.zeroHistoryEtfs -gt 0) {
            throw 'ETF 实际入库覆盖与该任务股票池不一致，需要检查 ETF 覆盖报告。'
        }
        if ($state.etfCoverage.invalidActiveRows -gt 0 -or $state.etfCoverage.unexpectedAdjustmentRows -gt 0 -or $state.etfCoverage.modelIsolationViolationRows -gt 0) {
            throw 'ETF 存在无效行情、错误复权口径或混入个股模型的数据，需要检查 ETF 覆盖报告。'
        }
        $state.etfPlan.status='SUCCESS'
        Save-PipelineState -Event
    }

    $state.stage='SCORE'
    Save-PipelineState -Event
    # 按 Java 的真实入池规则估算应产出数量：评分排除当日停牌/ST/无有效收盘价，
    # 短期模型另外需要至少 40 条记录，以及构建最近 20 日信号所需的有效滞后价格。
    # 不过滤历史停牌或 ST 行，且按实际记录排序，与模型读取的 420 自然日窗口保持一致。
    $eligibilitySql = @"
WITH numbered AS (
 SELECT code,market,trade_date,close,tradestatus,is_st,
 ROW_NUMBER() OVER(PARTITION BY code,market ORDER BY trade_date DESC) AS rn
 FROM ${Database}.stock_daily_all
 WHERE adjustflag=2 AND trade_date BETWEEN DATE_SUB('$AsOf',INTERVAL 420 DAY) AND '$AsOf'
), per_stock AS (
 SELECT code,market,COUNT(*) AS bar_count,
 MAX(CASE WHEN trade_date='$AsOf' AND close>0 AND COALESCE(tradestatus,1)<>0
   AND COALESCE(is_st,0)<>1 THEN 1 ELSE 0 END) AS eligible_asof,
 SUM(CASE WHEN rn<=20 AND close IS NOT NULL THEN 1 ELSE 0 END) AS last20_nonnull,
 MAX(CASE WHEN rn=6 THEN close END) AS lag5,
 MAX(CASE WHEN rn=11 THEN close END) AS lag10,
 MAX(CASE WHEN rn=21 THEN close END) AS lag20
 FROM numbered GROUP BY code,market
)
SELECT COUNT(*),COALESCE(SUM(eligible_asof),0),
 COALESCE(SUM(CASE WHEN eligible_asof=1 AND bar_count>=40 AND last20_nonnull=20
   AND lag5>0 AND lag10>0 AND lag20>0 THEN 1 ELSE 0 END),0)
FROM per_stock;
"@
    $eligibleFields = ([string](Invoke-LocalSql $eligibilitySql)) -split "`t"
    $state.eligibility = [ordered]@{sourceStocks=[int]$eligibleFields[0];expectedScoreStocks=[int]$eligibleFields[1];expectedShortTermStocks=[int]$eligibleFields[2]}
    # 使用启动全量采集时已发布的统一交易日，避免长任务跨收盘后混用部分股票的新日线。
    $state.score = Invoke-ProjectApi "/api/rankings/scores/rebuild?date=$AsOf" 'Post'
    if ($state.score.scoredStocks -le 0) { throw '评分没有生成有效股票结果。' }
    # 核对数据库真实行数；savedStocks 是 MySQL 受影响行数，更新旧记录时可能大于股票数。
    $state.scoreStoredRows = [long](Invoke-LocalSql "SELECT COUNT(*) FROM ${Database}.stock_score_daily WHERE trade_date='$AsOf' AND adjustflag=2;")
    if ($state.scoreStoredRows -ne $state.score.scoredStocks -or $state.score.scoredStocks -ne $state.eligibility.expectedScoreStocks -or $state.score.tradeDate -ne $AsOf -or $state.score.adjustFlag -ne 2) {
        throw '评分落库数量、日期或复权口径与本次计算不一致。'
    }
    Save-PipelineState -Event

    $state.stage='SHORT_TERM'
    Save-PipelineState -Event
    $state.shortTerm = Invoke-ProjectApi "/api/short-term/history/runs/rebuild?date=$AsOf" 'Post'
    if ($state.shortTerm.forecastStocks -le 0 -or $state.shortTerm.savedRows -le 0) { throw '短期预测没有生成有效结果。' }
    $shortRunId = [long]$state.shortTerm.runId
    $shortFields = ([string](Invoke-LocalSql "SELECT COUNT(*),COUNT(DISTINCT code,market),MIN(forecast_date),MAX(forecast_date),MIN(adjustflag),MAX(adjustflag),COUNT(DISTINCT horizon_days) FROM ${Database}.stock_forecast_prediction WHERE run_id=$shortRunId;")) -split "`t"
    $state.shortTermStoredRows = [long]$shortFields[0]
    if ($state.shortTermStoredRows -ne ([long]$state.shortTerm.forecastStocks * $state.shortTerm.horizonDays) -or $state.shortTerm.forecastStocks -ne $state.eligibility.expectedShortTermStocks -or [int]$shortFields[1] -ne $state.shortTerm.forecastStocks -or $shortFields[2] -ne $AsOf -or $shortFields[3] -ne $AsOf -or $shortFields[4] -ne '2' -or $shortFields[5] -ne '2' -or [int]$shortFields[6] -ne $state.shortTerm.horizonDays) {
        throw '短期预测落库数量、日期、复权口径或周期数量不完整。'
    }
    Save-PipelineState -Event

    $state.stage='PROBABILITY'
    Save-PipelineState -Event
    # 不限制 LIVE 股票数量；OOS 抽样、历史窗口和模拟路径保持引擎既定统计参数。
    $started = Invoke-ProjectApi '/api/prob/runs' 'Post' @{type='DAILY';asof=$AsOf;limitStocks=0;force=$true;noResume=$true}
    $state.probabilityRunId = $started.runId
    Save-PipelineState -Event
    while ($true) {
        $run = Invoke-ProjectApi "/api/prob/runs/$($started.runId)"
        # 使用字段白名单，不把可能包含数据库 URL 的历史 config_json 复制到监控状态。
        $state.probability = $run | Select-Object run_id,run_type,status,n_universe,n_ok,n_fail,excluded_json,data_warn_count,started_at,finished_at,message
        Save-PipelineState
        if ($run.status -ne 'RUNNING') { break }
        Start-Sleep -Seconds 30
    }
    if ($run.status -ne 'SUCCESS' -or $run.n_fail -gt 0 -or $run.n_universe -le 0 -or $run.n_ok -le 0) { throw "概率任务未完整成功：$($run.message)" }
    # DAILY 的任务头日期可能为空，必须用当前 run_id 的 LIVE 明细核对真实计算截止日。
    $probRunId = [long]$started.runId
    $probFields = ([string](Invoke-LocalSql "SELECT COUNT(*),COUNT(DISTINCT code,market),MIN(asof_date),MAX(asof_date),COUNT(DISTINCT horizon) FROM ${Database}.prob_prediction WHERE run_id=$probRunId AND pred_type='LIVE';")) -split "`t"
    $state.probabilityStoredRows = [long]$probFields[0]
    $state.probabilityStocks = [int]$probFields[1]
    if ($state.probabilityStoredRows -ne $run.n_ok -or $state.probabilityStocks -ne $run.n_universe -or $probFields[2] -ne $AsOf -or $probFields[3] -ne $AsOf -or [int]$probFields[4] -ne 4 -or $state.probabilityStoredRows -ne (4 * $state.probabilityStocks)) {
        throw '概率预测 LIVE 落库数量、股票数、截止日或 1/3/5/10 日周期不完整。'
    }
    $state.stage='COMPLETE'
    $state.status='SUCCESS'
    $state.finishedAt=[DateTimeOffset]::Now.ToString('o')
    Save-PipelineState -Event
} catch {
    # 错误只写可读原因；不输出接口的完整响应、环境变量或连接凭据。
    if ($guard) {
        $state.status='FAILED'
        if ($IncludeEtf58 -and $state.etfPlan.status -eq 'RUNNING') { $state.etfPlan.status='FAILED' }
        $state.error=$_.Exception.Message
        Save-PipelineState -Event
    }
    throw
} finally {
    if ($keepAwake) { [StockLocalDeployment.PowerRequest]::SetThreadExecutionState([uint32]2147483648) | Out-Null }
    if ($guard) { $guard.Dispose() }
}
