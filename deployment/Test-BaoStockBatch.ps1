<#
.SYNOPSIS
核验方案 A 的在线取样前提，或在明确空闲后调用独立取样程序。
.DESCRIPTION
默认只读取任务与进程状态，不登录 BaoStock。只有显式 -Online 且所有检查通过，
才运行不含 Spring/数据库连接的 Java main。不会自动停止旧任务、服务或市场流水线。
输出及可复用的逐请求断点只允许保存在项目 .local；源响应不写生产行情表。
本入口只取样，不代表数据、复权或模型验收通过，不会自动切换生产采集来源。
#>
[CmdletBinding()]
param(
    [string]$PlanFile='',
    [string]$OutputDirectory='.local/runtime/verification/batch-probe',
    [string]$ConfigPath='',
    [switch]$Online
)
. (Join-Path $PSScriptRoot 'Common.ps1') -Profile production -ConfigPath $ConfigPath
. (Join-Path $PSScriptRoot 'Platform.Operations.ps1')
$outputPath = Assert-ChildPath (Resolve-ProjectPath $OutputDirectory) (Join-Path $ProjectRoot '.local')
# 规范化字符串路径还不足以防止 junction 越界；创建/写入前检查路径中已存在的各级目录。
$cursorPath = $outputPath
while ($cursorPath -and $cursorPath.Length -ge $ProjectRoot.Length) {
    if (Test-Path -LiteralPath $cursorPath) {
        $existing = Get-Item -LiteralPath $cursorPath -Force
        if (($existing.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw '验证输出路径含目录联接，请使用项目内真实 .local 目录。' }
    }
    $cursorPath = Split-Path -Parent $cursorPath
}
New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
$reasons = New-Object 'System.Collections.Generic.List[string]'

try {
    # 原采集服务尚未支持共享会话锁，必须同时核验持久任务、自动流水线、真实后台进程。
    # 不能只看到一个 API 返回空闲，就和旧版定时器建立第二个行情连接。
    Assert-DatabaseIdentity
    $active = [int](Invoke-LocalSql "SELECT (SELECT COUNT(*) FROM crawl_log WHERE status='running')+(SELECT COUNT(*) FROM prob_run WHERE status='RUNNING')+(SELECT COUNT(*) FROM quant_backtest_task WHERE status IN ('PENDING','RUNNING'));")
    if ($active -gt 0) { $reasons.Add('现有采集或计算任务尚未结束') }
    try { Assert-WatcherIdle } catch { $reasons.Add('市场流水线仍在运行或身份尚未确认') }
    foreach ($profileName in @('production','verification')) {
        $profileRoot = Resolve-ProjectPath $PlatformConfig.profiles.$profileName.runtimeRoot
        $record = Read-PlatformJson (Join-Path $profileRoot 'backend-process.json')
        if ($record -and (Get-VerifiedProcess $record)) { $reasons.Add("$profileName 后端仍在运行；须等任务结束后通过正常入口停止，再在线取样") }
    }
    # 未接管的旧后端也不能遗漏；这里只读核验，不结束任何进程。
    if (Get-LegacyRecord 'backend') { $reasons.Add('旧目录后端仍存活，尚不具备独占验证条件') }
    foreach ($profileName in @('production','verification')) {
        if (Test-LocalPort ([int]$PlatformConfig.profiles.$profileName.apiPort)) {
            $reasons.Add("$profileName API 端口仍有监听者")
        }
    }
    $sourceConnections = @(Get-NetTCPConnection -ErrorAction Stop | Where-Object {
        $_.RemotePort -in @(10030,10031) -and $_.State -notin @('Closed','TimeWait','Listen')
    })
    if ($sourceConnections.Count -gt 0) { $reasons.Add('仍有行情 TCP 连接，禁止并行登录探针') }
} catch {
    # 不打印数据库连接、凭据或完整环境；诊断失败时保守拒绝在线连接。
    $reasons.Add('任务、数据库或进程状态核验失败，请检查本地配置后重试')
}

$ready = $reasons.Count -eq 0
$readiness = [ordered]@{checkedAt=[DateTimeOffset]::UtcNow.ToString('o');ready=$ready;onlineRequested=[bool]$Online;reasons=@($reasons.ToArray());sourceLoggedIn=$false;productionWrites=$false}
Save-PlatformJson (Join-Path $outputPath 'readiness.json') $readiness
if (!$Online) {
    $readiness | ConvertTo-Json -Depth 4
    return
}
if (!$ready) { throw '当前尚未具备独占验证条件，未登录 BaoStock；详见 .local 中的 readiness.json。' }
if (!$PlanFile) { throw '在线取样必须显式指定已经审查的计划文件。' }
$planPath = Resolve-ProjectPath $PlanFile
if (!(Test-Path -LiteralPath $planPath -PathType Leaf)) { throw '取样计划文件不存在。' }
if (!(Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw '缺少已通过离线检查的构建产物，请先构建。' }

# PropertiesLauncher 只调用指定 main，不启动应用的自动建表、任务恢复或调度器。
# 参数均为独立数组项，凭据不进入环境变量、命令行或取样文件。
& java '-Dstock.batch.validation.online=true' '-Dloader.main=com.li.stock.validation.BaoStockBatchProbe' `
    '-cp' $JarPath 'org.springframework.boot.loader.launch.PropertiesLauncher' $planPath $ProjectRoot $outputPath
if ($LASTEXITCODE -ne 0) { throw "在线取样未完成（退出码 $LASTEXITCODE），已成功的独立快照仍保留，可修复后续跑。" }
Write-Host '取样已结束；请执行离线完整性/复权比较和隔离模型验收，尚未允许生产切换。'
