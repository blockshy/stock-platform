<#
.SYNOPSIS
等待全市场历史行情采集完成，核验覆盖范围后串行执行三种计算。
.DESCRIPTION
这是本次用户授权任务的持久化执行器，不注册计划任务或开机自启。
接续指定的采集任务；失败时优先沿持久断点最多执行三轮，暂停必须由用户显式继续。
旧任务只有提供经过核对的固定请求文件才允许创建补采任务；提交结果不明时停止，不重发写请求。
本脚本不部署、不重启后端；应先部署并验收需要使用的服务版本。
全部进度保存在 .local/market-pipeline，凭据仅由公共数据库和启动脚本读取。
#>
param(
    [Parameter(Mandatory=$true)][long]$CrawlTaskId,
    [Parameter(Mandatory=$true)][ValidatePattern('^\d{4}-\d{2}-\d{2}$')][string]$AsOf,
    [ValidatePattern('^\d{4}-\d{2}-\d{2}$')][string]$HistoryStart='2023-11-17',
    [switch]$IncludeEtf58,
    [switch]$ContinuePaused,
    [string]$LegacyCrawlRequestPath='',
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
$script:continuePausedConsumed = $false
$script:preservePreviousStateOnFailure = $false
$script:crawlerCapabilitiesLoaded = $false
$script:durableResumeSupported = $false
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
    param([string]$Path, [string]$Method='Get', [object]$Body=$null, [switch]$AllowNotFound)
    $timeout = if ($Method -eq 'Get') { 30 } else { 3600 }
    $arguments = @{ Uri="http://127.0.0.1:$ApiPort$Path"; Method=$Method; TimeoutSec=$timeout }
    if ($null -ne $Body) { $arguments.ContentType='application/json'; $arguments.Body=$Body | ConvertTo-Json -Compress }
    # Windows PowerShell 5.1 对未声明 charset 的 JSON 会按错误编码解码；直接读取原始 UTF-8 字节。
    # 只重试只读查询。POST 可能已经被服务端接受，自动重发会重复创建预测批次。
    $attempts = if ($Method -eq 'Get') { 3 } else { 1 }
    for ($attempt=1; $attempt -le $attempts; $attempt++) {
        try { $http = Invoke-WebRequest @arguments -UseBasicParsing; break }
        catch {
            # 只有明确的 404 才视为旧服务不具备该只读接口；500、超时、网络故障不能降级成新建任务。
            if ($AllowNotFound -and $Method -eq 'Get' -and $_.Exception.PSObject.Properties['Response'] -and $_.Exception.Response -and
                [int]$_.Exception.Response.StatusCode -eq 404) { return $null }
            if ($attempt -eq $attempts) { throw }
            Start-Sleep -Seconds 5
        }
    }
    $response = [Text.Encoding]::UTF8.GetString($http.RawContentStream.ToArray()) | ConvertFrom-Json
    if ($response.code -ne 0) { throw "接口 $Path 返回错误：$($response.message)" }
    return $response.data
}

function Get-CrawlCheckpoint {
    <# 能力探测只读且每次执行器启动只做一次；旧日志没有快照时返回 null，不猜测旧请求。 #>
    param([long]$TaskId)
    if (!$script:crawlerCapabilitiesLoaded) {
        $capabilities = Invoke-ProjectApi '/api/data-crawler/capabilities' -AllowNotFound
        $script:durableResumeSupported = $null -ne $capabilities -and
            $null -ne $capabilities.PSObject.Properties['durableResume'] -and $capabilities.durableResume -eq $true
        $script:crawlerCapabilitiesLoaded = $true
    }
    if (!$script:durableResumeSupported) { return $null }
    return Invoke-ProjectApi "/api/data-crawler/tasks/$TaskId/checkpoint"
}

function Assert-CrawlCheckpointPlan {
    <# 日期、前缀、复权口径和限速必须与本流水线一致；实际证券快照继续由服务端保存和恢复。 #>
    param([object]$Checkpoint, [ValidateSet('stock','etf')][string]$Kind)
    $prefixes = (@([string]$Checkpoint.codePrefixes -split ',' | ForEach-Object { $_.Trim() } | Sort-Object -Unique) -join ',')
    $flags = (@($Checkpoint.fqTypes | Sort-Object -Unique) -join ',')
    $expectedPrefixes = if ($Kind -eq 'stock') { '00,30,60' } else { '58' }
    $expectedFlags = if ($Kind -eq 'stock') { '0,1' } else { '0' }
    if ($Checkpoint.from -ne $HistoryStart -or $Checkpoint.to -ne $AsOf -or
        $prefixes -cne $expectedPrefixes -or $flags -cne $expectedFlags -or $Checkpoint.requestDelayMillis -ne 1500) {
        throw '任务持久参数与本流水线的日期、前缀、复权口径或 1500ms 限速不符，未提交继续任务。'
    }
}

function Get-ExplicitLegacyCrawlRequest {
    <# 旧日志不能还原请求。必须由调用者提供核对后的完整固定参数，并保持现有证券名单，不偷偷刷新。 #>
    if (!$LegacyCrawlRequestPath) {
        throw '旧任务没有持久断点；请核对原请求后显式提供 -LegacyCrawlRequestPath，或新建固定参数任务并传入新 ID。'
    }
    $request = Get-Content -LiteralPath $LegacyCrawlRequestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($field in @('limit','codePrefixes','fqType','storeRawPrices','requestDelayMillis','refreshStockList','from','to')) {
        if (!$request.PSObject.Properties[$field]) { throw "旧任务固定请求缺少 $field，未提交补采。" }
    }
    $prefixes = (@([string]$request.codePrefixes -split ',' | ForEach-Object { $_.Trim() } | Sort-Object -Unique) -join ',')
    if ($request.from -ne $HistoryStart -or $request.to -ne $AsOf -or $prefixes -cne '00,30,60' -or
        $request.limit -ne 0 -or $request.fqType -ne 1 -or $request.requestDelayMillis -ne 1500 -or
        $request.storeRawPrices -isnot [bool] -or !$request.storeRawPrices -or
        $request.refreshStockList -isnot [bool] -or $request.refreshStockList) {
        throw '旧任务固定请求必须明确为本窗口的全量 00/30/60、前复权及原始价、1500ms、沿用已有证券名单。'
    }
    # 只发送白名单字段，拒绝通过额外 JSON 字段改变服务器默认行为；from/to 固定，不使用滚动 daysBack。
    return @{limit=0;codePrefixes='00,30,60';fqType=1;storeRawPrices=$true;requestDelayMillis=1500;
        refreshStockList=$false;from=$HistoryStart;to=$AsOf}
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
    if ($CrawlTaskId -ne [long]$savedId.Value -and $CrawlTaskId -ne $rootId) { return }
    if ($savedAsOf.Value -ne $AsOf -or $savedFrom.Value -ne $HistoryStart) {
        $script:preservePreviousStateOnFailure = $true
        throw '同一任务链不能更换固定日期；请保留原日期或传入另行创建的新任务 ID。'
    }
    if ($Previous.PSObject.Properties['etfPlan'] -and !$IncludeEtf58) {
        $script:preservePreviousStateOnFailure = $true
        throw '此前任务链包含 ETF，请继续保留 -IncludeEtf58；不能默默跳过已授权的独立 ETF 阶段。'
    }
    # 外部验收暂停与计算提交均不是可自动重放阶段。保留原状态，避免一次失败启动覆盖人工核验依据。
    if (($Previous.PSObject.Properties['status'] -and $Previous.status -eq 'PAUSED_FOR_BATCH_VALIDATION') -or
        ($Previous.PSObject.Properties['stage'] -and $Previous.stage -in @('SCORE','SHORT_TERM','PROBABILITY','COMPLETE','RESTART_BACKEND','BUILD_BACKEND'))) {
        $script:preservePreviousStateOnFailure = $true
        throw '此前任务链处于人工验收暂停或已进入部署/计算阶段，请先核对记录；本次不自动续跑或重放写请求。'
    }
    $state.crawlTaskId = [long]$savedId.Value
    $state.crawlRootTaskId = $rootId
    $savedAttempts = $Previous.PSObject.Properties['crawlAttempts']
    if ($savedAttempts) { $state.crawlAttempts = @($savedAttempts.Value) }
    foreach ($name in @('crawlRetryStatus','crawlRetryNotBefore')) {
        $property = $Previous.PSObject.Properties[$name]
        if ($property) { $state[$name] = $property.Value }
    }
    foreach ($name in @('etfCrawlTaskId','etfCrawl','etfPlan','etfSubmissionStatus')) {
        $property = $Previous.PSObject.Properties[$name]
        if ($property) { $state[$name] = $property.Value }
    }
    # POST 超时或进程在返回 ID 前退出时，服务器可能已经创建任务；禁止自动再次提交。
    # 保留已知任务链及未确认状态，供只读核对任务列表后再恢复，避免产生重复采集任务。
    if (($state.Contains('crawlRetryStatus') -and $state.crawlRetryStatus -in @('SUBMITTING','SUBMISSION_UNCONFIRMED')) -or
        ($state.Contains('etfSubmissionStatus') -and $state.etfSubmissionStatus -in @('SUBMITTING','SUBMISSION_UNCONFIRMED'))) {
        $script:preservePreviousStateOnFailure = $true
        throw '上次增量补采提交结果尚未确认，请先核对采集任务列表；本次未重复提交。'
    }
}

function Wait-StockCrawlWithRetries {
    <# 原任务计为第一轮；失败后优先继续同一断点链，最多三轮。用户暂停不消耗失败重试额度。 #>
    $maxRounds = 3
    while ($true) {
        $activeTaskId = [long]$state.crawlTaskId
        $state.stage = 'CRAWL'
        $checkpoint = Get-CrawlCheckpoint $activeTaskId
        if ($null -ne $checkpoint) {
            Assert-CrawlCheckpointPlan $checkpoint 'stock'
            $state.crawlRootTaskId = [long]$checkpoint.rootTaskId
            # 用户可能已从页面继续过旧日志，只跟随服务端同一固定任务链的最新 ID，不能再提交第二次继续。
            if ([long]$checkpoint.latestTaskId -ne $activeTaskId) {
                $state.crawlTaskId = [long]$checkpoint.latestTaskId
                Save-PipelineState -Event
                continue
            }
        }
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
        $checkpoint = Get-CrawlCheckpoint $activeTaskId
        if ($null -ne $checkpoint) {
            Assert-CrawlCheckpointPlan $checkpoint 'stock'
            if ([long]$checkpoint.latestTaskId -ne $activeTaskId) {
                $state.crawlTaskId = [long]$checkpoint.latestTaskId
                Save-PipelineState -Event
                continue
            }
        }
        $explicitPauseContinue = $false
        if ($currentCrawl.status -eq 'paused' -or ($null -ne $checkpoint -and $checkpoint.status -eq 'paused')) {
            if (!$ContinuePaused -or $script:continuePausedConsumed) {
                $state.status = 'PAUSED'
                $state.stage = 'CRAWL_PAUSED'
                $state.crawlRetryStatus = 'PAUSED'
                Save-PipelineState -Event
                return $null
            }
            if ($null -eq $checkpoint -or !$checkpoint.resumable) {
                throw '已暂停任务没有可继续的持久参数，请核对任务记录；未新建替代任务。'
            }
            # 显式继续仅适用于启动时遇到的这一次暂停；本进程随后再次遇到暂停仍必须尊重用户选择。
            $script:continuePausedConsumed = $true
            $explicitPauseContinue = $true
        }
        if ($currentCrawl.totalCount -gt 0 -and $currentCrawl.status -eq 'done' -and
            $currentCrawl.failCount -eq 0 -and $currentCrawl.successCount -eq $currentCrawl.totalCount -and
            ($null -eq $checkpoint -or $checkpoint.status -eq 'done')) {
            $state.crawlRetryStatus = 'COMPLETE'
            Save-PipelineState -Event
            return $currentCrawl
        }
        # 持久断点可恢复“尚未取得证券池”的失败；旧版只有已知证券池和明确参数才允许新建补采。
        if (!$explicitPauseContinue -and ($currentCrawl.status -notin @('done','failed') -or
            ($null -eq $checkpoint -and $currentCrawl.totalCount -le 0))) {
            throw "采集任务 $activeTaskId 未取得有效股票池或状态异常：$($currentCrawl.remark)"
        }
        $failedRounds = @($state.crawlAttempts | Where-Object { $_.status -ne 'paused' }).Count
        if (!$explicitPauseContinue -and $failedRounds -ge $maxRounds) {
            throw "股票增量采集已达到 $maxRounds 轮上限，任务 $activeTaskId 仍未完整成功：$($currentCrawl.remark)"
        }
        $legacyRequest = $null
        if ($null -ne $checkpoint) {
            if (!$checkpoint.resumable) { throw '服务端断点当前不可继续，未创建其它任务替代。' }
        } else {
            $legacyRequest = Get-ExplicitLegacyCrawlRequest
        }

        # 自动失败重试保留 60 秒轮间退避；人工继续立即交给后端，逐证券 1500ms 的礼貌限速保持不变。
        if (!$explicitPauseContinue) {
            if (!$state.Contains('crawlRetryStatus') -or $state.crawlRetryStatus -ne 'WAITING') {
                $state.crawlRetryNotBefore = [DateTimeOffset]::UtcNow.AddSeconds(60).ToString('o')
            }
            $state.crawlRetryStatus = 'WAITING'
            $state.stage = 'CRAWL_RETRY_WAIT'
            Save-PipelineState -Event
            $remaining = [Math]::Max(0, [Math]::Ceiling(([DateTimeOffset]$state.crawlRetryNotBefore - [DateTimeOffset]::UtcNow).TotalSeconds))
            if ($remaining -gt 0) { Start-Sleep -Seconds ([int]$remaining) }
            if ($null -ne $checkpoint) {
                # 轮间等待期间用户可能已从页面继续；再次只读核对，避免基于旧 ID 提交续跑。
                $checkpoint = Get-CrawlCheckpoint $activeTaskId
                if ($null -eq $checkpoint) { throw '等待期间持久断点不可见，停止补采。' }
                Assert-CrawlCheckpointPlan $checkpoint 'stock'
                if ([long]$checkpoint.latestTaskId -ne $activeTaskId) {
                    $state.crawlTaskId = [long]$checkpoint.latestTaskId
                    Save-PipelineState -Event
                    continue
                }
                if (!$checkpoint.resumable) { throw '等待期间任务状态已变化，未提交继续。' }
            }
        }
        $state.crawlRetryStatus = 'SUBMITTING'
        Save-PipelineState -Event
        try {
            if ($null -ne $checkpoint) {
                # 服务端沿用固定日期/参数/证券快照；原始价补精确缺口，复权价格每个新 attempt 保守全窗重验。
                $nextCrawl = Invoke-ProjectApi "/api/data-crawler/tasks/$activeTaskId/resume" 'Post'
            } else {
                # 旧版无可证明的成功区间和固定证券快照；只使用调用者明确核对过的固定请求降级新建。
                $nextCrawl = Invoke-ProjectApi '/api/data-crawler/tasks' 'Post' $legacyRequest
            }
            if ($null -eq $nextCrawl -or !$nextCrawl.PSObject.Properties['id'] -or [long]$nextCrawl.id -le 0 -or [long]$nextCrawl.id -eq $activeTaskId) {
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

function Wait-EtfCrawl {
    <# ETF 单独建任务且仅保存真实原始价；恢复执行器时复用已保存任务，不重复 POST 新任务。 #>
    $state.stage = 'ETF_CRAWL'
    $state.etfPlan.status = 'RUNNING'
    if (!$state.Contains('etfCrawlTaskId')) {
        if ($state.Contains('etfSubmissionStatus') -and $state.etfSubmissionStatus -in @('SUBMITTING','SUBMISSION_UNCONFIRMED')) {
            throw 'ETF 提交结果未确认，请先核对任务列表；本次未重发。'
        }
        $state.etfSubmissionStatus = 'SUBMITTING'
        Save-PipelineState -Event
        try {
            $created = Invoke-ProjectApi '/api/data-crawler/tasks' 'Post' @{
                limit=0;codePrefixes='58';fqType=0;storeRawPrices=$false;requestDelayMillis=1500
                refreshStockList=$true;from=$HistoryStart;to=$AsOf
            }
            if ($null -eq $created -or !$created.PSObject.Properties['id'] -or [long]$created.id -le 0) {
                throw 'ETF 接口没有返回有效任务 ID。'
            }
            $state.etfCrawlTaskId = [long]$created.id
            $state.etfSubmissionStatus = 'SUBMITTED'
            Save-PipelineState -Event
        } catch {
            $state.etfSubmissionStatus = 'SUBMISSION_UNCONFIRMED'
            Save-PipelineState -Event
            throw "ETF 提交结果未确认，请先核对任务列表，未自动重发：$($_.Exception.Message)"
        }
    }
    while ($true) {
        $taskId = [long]$state.etfCrawlTaskId
        $checkpoint = Get-CrawlCheckpoint $taskId
        if ($null -ne $checkpoint) {
            Assert-CrawlCheckpointPlan $checkpoint 'etf'
            if ([long]$checkpoint.latestTaskId -ne $taskId) {
                $state.etfCrawlTaskId = [long]$checkpoint.latestTaskId
                Save-PipelineState -Event
                continue
            }
        }
        $current = Invoke-ProjectApi "/api/data-crawler/tasks/$taskId"
        $state.etfCrawl = $current | Select-Object id,totalCount,successCount,failCount,status,startTime,endTime,remark
        Save-PipelineState
        if ($current.status -eq 'running') { Start-Sleep -Seconds 30; continue }
        # 查询日志与查询断点不是同一个事务快照；取得终态后重新读断点，避免刚完成暂停时仍使用旧 running 摘要。
        $checkpoint = Get-CrawlCheckpoint $taskId
        if ($null -ne $checkpoint) {
            Assert-CrawlCheckpointPlan $checkpoint 'etf'
            if ([long]$checkpoint.latestTaskId -ne $taskId) {
                $state.etfCrawlTaskId = [long]$checkpoint.latestTaskId
                Save-PipelineState -Event
                continue
            }
        }
        if ($current.status -eq 'paused' -or ($null -ne $checkpoint -and $checkpoint.status -eq 'paused')) {
            if (!$ContinuePaused -or $script:continuePausedConsumed) {
                $state.status='PAUSED';$state.stage='ETF_CRAWL_PAUSED';$state.etfPlan.status='PAUSED'
                Save-PipelineState -Event
                return $null
            }
            if ($null -eq $checkpoint -or !$checkpoint.resumable) { throw 'ETF 暂停任务没有可继续的持久参数。' }
            $script:continuePausedConsumed = $true
            $state.etfSubmissionStatus = 'SUBMITTING'
            Save-PipelineState -Event
            try {
                $next = Invoke-ProjectApi "/api/data-crawler/tasks/$taskId/resume" 'Post'
                if ($null -eq $next -or !$next.PSObject.Properties['id'] -or [long]$next.id -le 0 -or [long]$next.id -eq $taskId) { throw 'ETF 继续接口没有返回有效任务 ID。' }
                $state.etfCrawlTaskId = [long]$next.id
                $state.etfSubmissionStatus = 'SUBMITTED'
                Save-PipelineState -Event
            } catch {
                $state.etfSubmissionStatus = 'SUBMISSION_UNCONFIRMED'
                Save-PipelineState -Event
                throw "ETF 继续结果未确认，请先核对任务列表，未自动重发：$($_.Exception.Message)"
            }
            continue
        }
        if ($current.status -ne 'done' -or $current.totalCount -le 0 -or $current.failCount -gt 0 -or
            $current.successCount -ne $current.totalCount -or ($null -ne $checkpoint -and $checkpoint.status -ne 'done')) {
            throw "ETF 采集未完整成功，任务 ${taskId}：$($current.remark)"
        }
        return $current
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
    # 已暂停是正常、可持久恢复的终态；释放 watcher 锁后等待用户继续，不误报失败也不启动模型。
    if ($null -eq $crawl) { return }

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

    if ($IncludeEtf58) {
        # ETF 与股票串行共享 BaoStock 连接租约；与已部署服务直接协作，不再隐式构建或重启后端。
        $etfCrawl = Wait-EtfCrawl
        if ($null -eq $etfCrawl) { return }
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
    if ($guard -and !$script:preservePreviousStateOnFailure) {
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
