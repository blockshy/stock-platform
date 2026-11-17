<#
市场流水线的离线状态机回归。只从生产脚本 AST 提取函数，绝不执行其入口、数据库、进程或网络操作。
接口、持久化与等待均使用内存替身；临时 JSON 只用于验证旧版请求必须由调用者明确提供。
#>
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$temporaryRoot = Join-Path $root ('.local\script-tests\'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporaryRoot -Force | Out-Null
$passed = 0
function Assert-PipelineTest { param([bool]$Value,[string]$Message); if (!$Value) { throw $Message } }
function Assert-PipelineThrows {
    param([scriptblock]$Action,[string]$Expected)
    $message = ''
    try { & $Action | Out-Null } catch { $message=$_.Exception.Message }
    Assert-PipelineTest ($message -like "*$Expected*") "预期异常未发生或原因错误：$Expected；实际：$message"
}
try {
    $tokens=$null;$errors=$null
    $ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Run-MarketPipeline.ps1'),[ref]$tokens,[ref]$errors)
    Assert-PipelineTest (@($errors).Count -eq 0) '生产流水线不能被当前 PowerShell 正确解析'
    foreach ($definition in $ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]},$false)) {
        . ([scriptblock]::Create($definition.Extent.Text))
    }
    $AsOf='2026-11-16';$HistoryStart='2023-11-17';$CrawlTaskId=101
    $IncludeEtf58=$false;$ContinuePaused=$false;$LegacyCrawlRequestPath=''
    function New-FakeCrawl {
        param([long]$Id,[string]$Status='done',[int]$Success=2,[int]$Fail=0)
        return [pscustomobject]@{id=$Id;status=$Status;totalCount=2;successCount=$Success;failCount=$Fail;
            startTime='2026-11-18T10:00:00';endTime='2026-11-18T10:01:00';remark='内存测试'}
    }
    function New-FakeCheckpoint {
        param([long]$Id,[string]$Status='partial',[switch]$Etf)
        return [pscustomobject]@{rootTaskId=101;latestTaskId=$Id;status=$Status;resumable=($Status -in @('paused','partial','failed'));
            from=$HistoryStart;to=$AsOf;codePrefixes=$(if ($Etf) {'58'} else {'00,30,60'});
            fqTypes=$(if ($Etf) {@(0)} else {@(1,0)});requestDelayMillis=1500}
    }
    function Reset-PipelineFixture {
        $script:crawlerCapabilitiesLoaded=$false;$script:durableResumeSupported=$false
        $script:continuePausedConsumed=$false;$script:preservePreviousStateOnFailure=$false
        $script:supportsResume=$true;$script:failPost=$false;$script:nextTaskId=102
        $script:apiCalls=New-Object 'System.Collections.Generic.List[object]'
        $script:snapshots=New-Object 'System.Collections.Generic.List[string]'
        $script:sleeps=New-Object 'System.Collections.Generic.List[int]'
        $script:checkpoints=@{};$script:tasks=@{}
        $script:checkpoints[101L]=New-FakeCheckpoint 101
        $script:checkpoints[102L]=New-FakeCheckpoint 102 'done'
        $script:tasks[101L]=New-FakeCrawl 101 'done' 1 1
        $script:tasks[102L]=New-FakeCrawl 102
        return [ordered]@{status='RUNNING';stage='CRAWL';crawlTaskId=101L;crawlRootTaskId=101L;asOf=$AsOf;
            historyStart=$HistoryStart;crawlAttempts=@()}
    }
    # 以下三个替身彻底切断函数与磁盘状态、真实 HTTP 和阻塞等待的联系。
    function Save-PipelineState { param([switch]$Event); $script:snapshots.Add(($state | ConvertTo-Json -Depth 10 -Compress)) }
    function Start-Sleep { param([int]$Seconds); $script:sleeps.Add($Seconds) }
    function Invoke-ProjectApi {
        param([string]$Path,[string]$Method='Get',[object]$Body=$null,[switch]$AllowNotFound)
        $script:apiCalls.Add([pscustomobject]@{path=$Path;method=$Method;body=$Body})
        if ($Method -eq 'Post') {
            if ($script:failPost) { throw '模拟响应丢失' }
            return $script:tasks[[long]$script:nextTaskId]
        }
        if ($Path -eq '/api/data-crawler/capabilities') {
            if (!$script:supportsResume) { return $null }
            return [pscustomobject]@{durableResume=$true}
        }
        if ($Path -match '^/api/data-crawler/tasks/(\d+)/checkpoint$') { return $script:checkpoints[[long]$Matches[1]] }
        if ($Path -match '^/api/data-crawler/tasks/(\d+)$') { return $script:tasks[[long]$Matches[1]] }
        throw "测试未声明的接口：$Path"
    }

    $state=Reset-PipelineFixture
    $result=Wait-StockCrawlWithRetries
    $posts=@($script:apiCalls | Where-Object method -eq 'Post')
    Assert-PipelineTest ($result.id -eq 102 -and $posts.Count -eq 1 -and $posts[0].path -eq '/api/data-crawler/tasks/101/resume' -and $null -eq $posts[0].body) '失败后未只提交一次同链 resume'
    Assert-PipelineTest ($state.crawlAttempts.Count -eq 2 -and $state.crawlRetryStatus -eq 'COMPLETE' -and $script:sleeps.Count -eq 1) '重试轮次或轮间退避没有持久维护'
    $passed++

    $state=Reset-PipelineFixture
    $script:tasks[101L]=New-FakeCrawl 101 'paused' 1 0
    $script:checkpoints[101L]=New-FakeCheckpoint 101 'paused'
    $result=Wait-StockCrawlWithRetries
    Assert-PipelineTest ($null -eq $result -and $state.status -eq 'PAUSED' -and $state.stage -eq 'CRAWL_PAUSED') '暂停被误报失败或继续向后执行'
    Assert-PipelineTest (@($script:apiCalls | Where-Object method -eq 'Post').Count -eq 0 -and $script:sleeps.Count -eq 0) '普通 watcher 启动自动继续了暂停任务'
    $passed++

    $state=Reset-PipelineFixture;$ContinuePaused=$true
    $script:tasks[101L]=New-FakeCrawl 101 'paused' 1 0
    $script:checkpoints[101L]=New-FakeCheckpoint 101 'paused'
    $script:tasks[102L]=New-FakeCrawl 102 'paused' 1 0
    $script:checkpoints[102L]=New-FakeCheckpoint 102 'paused'
    $result=Wait-StockCrawlWithRetries
    Assert-PipelineTest ($null -eq $result -and $state.status -eq 'PAUSED' -and $state.crawlTaskId -eq 102) '第二次用户暂停没有保持'
    Assert-PipelineTest (@($script:apiCalls | Where-Object method -eq 'Post').Count -eq 1 -and $script:sleeps.Count -eq 0) '显式继续被重复消耗或增加多余等待'
    $ContinuePaused=$false;$passed++

    $state=Reset-PipelineFixture
    $script:checkpoints[101L]=New-FakeCheckpoint 102 'done'
    $result=Wait-StockCrawlWithRetries
    Assert-PipelineTest ($result.id -eq 102 -and @($script:apiCalls | Where-Object method -eq 'Post').Count -eq 0) '用户已从页面继续后又提交了重复 resume'
    $passed++

    $state=Reset-PipelineFixture;$script:failPost=$true
    Assert-PipelineThrows { Wait-StockCrawlWithRetries } '提交结果未确认'
    Assert-PipelineTest (@($script:apiCalls | Where-Object method -eq 'Post').Count -eq 1 -and $state.crawlRetryStatus -eq 'SUBMISSION_UNCONFIRMED') '结果未知时重发 POST 或遗失状态'
    $previous=$state | ConvertTo-Json -Depth 10 | ConvertFrom-Json
    $state=Reset-PipelineFixture
    Assert-PipelineThrows { Restore-StockCrawlState $previous } '提交结果尚未确认'
    Assert-PipelineTest ($script:preservePreviousStateOnFailure -and $script:apiCalls.Count -eq 0) '重启后未保留未知提交证据'
    $passed++

    $state=Reset-PipelineFixture;$script:supportsResume=$false
    Assert-PipelineThrows { Wait-StockCrawlWithRetries } 'LegacyCrawlRequestPath'
    Assert-PipelineTest (@($script:apiCalls | Where-Object method -eq 'Post').Count -eq 0) '旧任务无明确请求仍新建补采'
    $LegacyCrawlRequestPath=Join-Path $temporaryRoot 'legacy-request.json'
    $legacy=@{limit=0;codePrefixes='00,30,60';fqType=1;storeRawPrices=$true;requestDelayMillis=1500;refreshStockList=$false;from=$HistoryStart;to=$AsOf}
    [IO.File]::WriteAllText($LegacyCrawlRequestPath,($legacy | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    $result=Wait-StockCrawlWithRetries
    $posts=@($script:apiCalls | Where-Object method -eq 'Post')
    Assert-PipelineTest ($result.id -eq 102 -and $posts.Count -eq 1 -and $posts[0].path -eq '/api/data-crawler/tasks' -and $posts[0].body.requestDelayMillis -eq 1500) '显式旧版降级未保持固定参数'
    $legacy.to='2026-11-17'
    [IO.File]::WriteAllText($LegacyCrawlRequestPath,($legacy | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    Assert-PipelineThrows { Get-ExplicitLegacyCrawlRequest } '固定请求必须'
    $LegacyCrawlRequestPath='';$passed++

    $state=Reset-PipelineFixture
    $state.crawlAttempts=@((New-FakeCrawl 99 'failed' 0 2),(New-FakeCrawl 100 'done' 1 1))
    Assert-PipelineThrows { Wait-StockCrawlWithRetries } '3 轮上限'
    Assert-PipelineTest (@($script:apiCalls | Where-Object method -eq 'Post').Count -eq 0) '超过持久化重试上限仍创建任务'
    $passed++

    foreach ($blockedStage in @('SCORE','SHORT_TERM','PROBABILITY','COMPLETE')) {
        $state=Reset-PipelineFixture
        $previous=$state | ConvertTo-Json -Depth 10 | ConvertFrom-Json
        $previous.stage=$blockedStage
        Assert-PipelineThrows { Restore-StockCrawlState $previous } '不自动续跑或重放'
        Assert-PipelineTest $script:preservePreviousStateOnFailure '拒绝重放时覆盖了原计算状态'
    }
    $state=Reset-PipelineFixture
    $previous=$state | ConvertTo-Json -Depth 10 | ConvertFrom-Json
    $previous.status='PAUSED_FOR_BATCH_VALIDATION'
    Assert-PipelineThrows { Restore-StockCrawlState $previous } '不自动续跑或重放'
    $passed++

    $state=Reset-PipelineFixture
    $script:checkpoints[101L].from='2024-01-01'
    Assert-PipelineThrows { Wait-StockCrawlWithRetries } '持久参数'
    Assert-PipelineTest (@($script:apiCalls | Where-Object method -eq 'Post').Count -eq 0) '固定日期不符仍继续'
    $passed++

    $state=Reset-PipelineFixture;$state.etfPlan=[ordered]@{status='RUNNING'};$state.etfCrawlTaskId=101L
    $script:checkpoints[101L]=New-FakeCheckpoint 101 'paused' -Etf
    $script:tasks[101L]=New-FakeCrawl 101 'paused' 1 0
    $result=Wait-EtfCrawl
    Assert-PipelineTest ($null -eq $result -and $state.stage -eq 'ETF_CRAWL_PAUSED' -and $state.etfPlan.status -eq 'PAUSED') 'ETF 暂停未持久记录'
    Assert-PipelineTest (@($script:apiCalls | Where-Object method -eq 'Post').Count -eq 0) '恢复 watcher 时重复创建 ETF 任务'
    $ContinuePaused=$true;$script:checkpoints[102L]=New-FakeCheckpoint 102 'done' -Etf
    $state.status='RUNNING'
    $result=Wait-EtfCrawl
    $posts=@($script:apiCalls | Where-Object method -eq 'Post')
    Assert-PipelineTest ($result.id -eq 102 -and $posts.Count -eq 1 -and $posts[0].path -eq '/api/data-crawler/tasks/101/resume') 'ETF 显式继续没有使用固定断点'
    $ContinuePaused=$false;$passed++

    $state=Reset-PipelineFixture;$state.etfPlan=[ordered]@{status='QUEUED'};$script:failPost=$true
    Assert-PipelineThrows { Wait-EtfCrawl } 'ETF 提交结果未确认'
    Assert-PipelineThrows { Wait-EtfCrawl } 'ETF 提交结果未确认'
    Assert-PipelineTest ($state.etfSubmissionStatus -eq 'SUBMISSION_UNCONFIRMED' -and @($script:apiCalls | Where-Object method -eq 'Post').Count -eq 1) 'ETF 结果不明时重复提交'
    $passed++

    # 日志 done 只是旧接口的轮次终态；若持久断点仍为 partial/failed，不能用看似齐全的计数进入模型。
    $state=Reset-PipelineFixture
    $script:tasks[101L]=New-FakeCrawl 101 'done' 2 0
    $result=Wait-StockCrawlWithRetries
    Assert-PipelineTest ($result.id -eq 102 -and @($script:apiCalls | Where-Object method -eq 'Post').Count -eq 1) '股票断点未完成却被作为完整结果返回'
    $state=Reset-PipelineFixture;$state.etfPlan=[ordered]@{status='RUNNING'};$state.etfCrawlTaskId=101L
    $script:tasks[101L]=New-FakeCrawl 101 'done' 2 0
    $script:checkpoints[101L]=New-FakeCheckpoint 101 'partial' -Etf
    Assert-PipelineThrows { Wait-EtfCrawl } 'ETF 采集未完整成功'
    Assert-PipelineTest (@($script:apiCalls | Where-Object method -eq 'Post').Count -eq 0) 'ETF 非完整断点被误判完成或擅自重建任务'
    $passed++

    $state=Reset-PipelineFixture
    $previous=$state | ConvertTo-Json -Depth 10 | ConvertFrom-Json
    $previous.historyStart='2024-01-01'
    Assert-PipelineThrows { Restore-StockCrawlState $previous } '不能更换固定日期'
    $previous.historyStart=$HistoryStart
    $previous | Add-Member NoteProperty etfPlan ([pscustomobject]@{status='PAUSED'})
    Assert-PipelineThrows { Restore-StockCrawlState $previous } 'IncludeEtf58'
    $passed++

    # 再单独装载真实 HTTP 包装器，但底层请求仍为内存替身。验证写请求超时只发一次，
    # 同时区分“明确 404 旧版接口”与普通只读故障，避免故障被误解为不支持持久断点。
    & {
        $httpDefinition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-ProjectApi'},$false)
        . ([scriptblock]::Create($httpDefinition.Extent.Text))
        $ApiPort=8080;$script:httpCount=0;$script:missingEndpoint=$false
        function Invoke-WebRequest {
            param($Uri,$Method,$TimeoutSec,$ContentType,$Body,[switch]$UseBasicParsing)
            $script:httpCount++
            $failure=[InvalidOperationException]::new('模拟 HTTP 结果未知')
            if ($script:missingEndpoint) { $failure | Add-Member NoteProperty Response ([pscustomobject]@{StatusCode=404}) }
            throw $failure
        }
        Assert-PipelineThrows { Invoke-ProjectApi '/api/data-crawler/tasks/101/resume' 'Post' } '结果未知'
        Assert-PipelineTest ($script:httpCount -eq 1) '真实 HTTP 包装器自动重发写请求'
        $script:httpCount=0
        Assert-PipelineThrows { Invoke-ProjectApi '/api/data-crawler/capabilities' -AllowNotFound } '结果未知'
        Assert-PipelineTest ($script:httpCount -eq 3) '普通能力查询故障被当作旧版服务'
        $script:httpCount=0;$script:missingEndpoint=$true
        $value=Invoke-ProjectApi '/api/data-crawler/capabilities' -AllowNotFound
        Assert-PipelineTest ($null -eq $value -and $script:httpCount -eq 1) '明确 404 没有立即转为旧版只读结果'
    }
    $passed++
    Write-Host "PASS: $passed 组市场流水线离线回归；没有连接网络/数据库、启动服务、执行模型或真实等待。"
} finally {
    # 只清理本次 GUID 目录，先验证规范化路径在既定测试根内，避免误删项目或既有本地状态。
    $safeRoot=[IO.Path]::GetFullPath((Join-Path $root '.local\script-tests')).TrimEnd('\')+'\'
    $resolved=[IO.Path]::GetFullPath($temporaryRoot)
    if ($resolved.StartsWith($safeRoot,[StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolved)) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
