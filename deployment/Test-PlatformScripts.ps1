<#
离线部署脚本回归：只使用临时JSON/ZIP和函数替身，不连接MySQL、不调用真实进程诊断，
不启动/停止服务。覆盖身份校验、配置隔离、中文编码、JAR等效和异常时环境恢复。
#>
$ErrorActionPreference='Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$temporaryRoot = Join-Path $root ('.local\script-tests\'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporaryRoot -Force | Out-Null
$passed=0
function Assert-Test { param([bool]$Condition,[string]$Message); if (!$Condition) { throw $Message } }
function Assert-Throws {
    param([scriptblock]$Action,[string]$Message)
    $thrown=$false
    try { & $Action | Out-Null } catch { $thrown=$true }
    Assert-Test $thrown $Message
}
function Load-TestFunctions {
    param([string]$Target='production')
    . (Join-Path $PSScriptRoot 'Common.ps1') -Profile $Target
    . (Join-Path $PSScriptRoot 'Platform.Operations.ps1')
}
try {
    # 以点调用保留配置/函数在测试脚本作用域，禁止通过真正的平台入口触发操作。
    . Load-TestFunctions 'production'
    Assert-Test ($Database -eq 'stock_eastmoney' -and $ApiPort -eq 8080 -and $DataRoot -eq [IO.Path]::GetFullPath((Join-Path $root '..\.local'))) '生产默认路径错误'
    . Load-TestFunctions 'verification'
    Assert-Test ($Database -eq 'stock_platform_verify' -and $ApiPort -eq 18080 -and $WebPort -eq 15173 -and !$ProfileConfig.scoreScheduleEnabled) '验证隔离配置错误'
    $passed++

    $badConfig = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'platform.config.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $badConfig.profiles.verification.database='stock_eastmoney'
    $badPath=Join-Path $temporaryRoot 'bad-config.json'
    Write-Utf8File $badPath ($badConfig | ConvertTo-Json -Depth 8)
    Assert-Throws { . (Join-Path $PSScriptRoot 'Common.ps1') -Profile verification -ConfigPath $badPath } '验证库指向生产时未拒绝'
    . Load-TestFunctions 'production'
    $passed++

    $jsonPath=Join-Path $temporaryRoot '中文状态.json'
    Save-PlatformJson $jsonPath @{remark='已完成：证券与"引号"';status='RUNNING'}
    $json=Read-PlatformJson $jsonPath
    $bytes=[IO.File]::ReadAllBytes($jsonPath)
    Assert-Test ($json.remark -ceq '已完成：证券与"引号"' -and !($bytes[0]-eq 239 -and $bytes[1]-eq 187)) '无BOM中文JSON恢复失败'
    Assert-Throws { Assert-ChildPath (Join-Path $temporaryRoot '..\outside') $temporaryRoot } '路径越界未拒绝'
    $passed++

    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    function New-TestJar {
        param([string]$Path,[string]$Payload,[int]$Year)
        $zip=[IO.Compression.ZipFile]::Open($Path,[IO.Compression.ZipArchiveMode]::Create)
        try {
            $entry=$zip.CreateEntry('BOOT-INF/classes/Example.class')
            $entry.LastWriteTime=[DateTimeOffset]::new($Year,1,1,0,0,0,[TimeSpan]::Zero)
            $writer=[IO.StreamWriter]::new($entry.Open())
            try { $writer.Write($Payload) } finally { $writer.Dispose() }
        } finally { $zip.Dispose() }
    }
    $oldJar=Join-Path $temporaryRoot 'old.jar';$sameJar=Join-Path $temporaryRoot 'same.jar';$badJar=Join-Path $temporaryRoot 'changed.jar'
    New-TestJar $oldJar 'same-code' 2020
    New-TestJar $sameJar 'same-code' 2025
    New-TestJar $badJar 'changed-code' 2025
    Assert-Test ((Get-JarPayloadHash $oldJar) -ceq (Get-JarPayloadHash $sameJar)) 'ZIP时间戳导致错误的不等效判定'
    Assert-Test ((Get-JarPayloadHash $oldJar) -cne (Get-JarPayloadHash $badJar)) '类文件差异未被检测'
    $passed++

    $now=[datetime]::Now
    $fakeRecord=[pscustomobject]@{id=12345;startedAt=$now.ToUniversalTime().ToString('o');executable='C:\fake\java.exe';commandMarker='C:\fake\app.jar';port=18080}
    function Get-Process { param($Id,$ErrorAction); return [pscustomobject]@{Id=12345;StartTime=$now.AddMinutes(1);Path='C:\fake\java.exe'} }
    Assert-Throws { Get-VerifiedProcess $fakeRecord } '复用PID未拒绝'
    function Get-Process { param($Id,$ErrorAction); return [pscustomobject]@{Id=12345;StartTime=$now;Path='C:\fake\java.exe'} }
    function Get-CimInstance { param($ClassName,$Filter); return [pscustomobject]@{CommandLine='java -jar C:\fake\app.jar'} }
    function Get-NetTCPConnection { param($State,$LocalPort,$ErrorAction); return [pscustomobject]@{OwningProcess=999} }
    Assert-Throws { Get-VerifiedProcess $fakeRecord } '端口被其它进程占用未拒绝'
    function Get-NetTCPConnection { param($State,$LocalPort,$ErrorAction); return [pscustomobject]@{OwningProcess=12345} }
    Assert-Test ((Get-VerifiedProcess $fakeRecord).Id -eq 12345) '正确身份未通过'
    $passed++

    # 模拟已认证旧进程接管；仅写临时运行记录，不调用真正Get-Process或MySQL。
    $RuntimeRoot=Join-Path $temporaryRoot 'runtime';$JarPath=$sameJar
    $legacy=[pscustomobject]@{id=12345;startedAt=$now.ToUniversalTime().ToString('o');executable='C:\fake\java.exe';commandMarker=$oldJar;port=8080;mode='legacy';profile='production';sourceRoot=$temporaryRoot;engineDirectory='C:\legacy\engine'}
    function Assert-DatabaseIdentity { }
    function Get-LegacyRecord { param($Name); return $legacy }
    function Get-VerifiedProcess { param($Record); return [pscustomobject]@{Id=12345;StartTime=[datetime]::Now.AddSeconds(1);Path='C:\fake\java.exe'} }
    function Test-LocalPort { param($Port); return $true }
    Adopt-ExistingBackend | Out-Null
    $adopted=Read-PlatformJson (Get-ProcessRecordPath 'backend')
    Assert-Test ($adopted.mode -eq 'legacy-adopted' -and $adopted.commandMarker -eq $oldJar -and $adopted.engineDirectory -eq 'C:\legacy\engine') '接管记录错误地声称使用新路径'
    $JarPath=$badJar
    Assert-Throws { Adopt-ExistingBackend } 'JAR不等效仍允许接管'
    $passed++

    # 验证前端启动失败时环境完整恢复，并且创建前端前已清除后端凭据。
    # 用独立子作用域保存Get-Command等替身；它们不能泄漏到后续构建测试，否则PS5.1
    # 首次解析模块导出的Get-FileHash时可能只看见假的命令信息，产生上下文相关失败。
    & {
    . Load-TestFunctions 'verification'
    $RuntimeRoot=Join-Path $temporaryRoot 'start';$LogRoot=Join-Path $RuntimeRoot 'logs';$EngineRuntimeRoot=Join-Path $RuntimeRoot 'engine'
    $JarPath=$sameJar;$VitePath=$sameJar;$WebRoot=$temporaryRoot
    New-Item -ItemType Directory -Path (Join-Path $WebRoot 'dist') -Force | Out-Null
    Write-Utf8File (Join-Path $WebRoot 'dist\index.html') 'test'
    function Test-PlatformEnvironment { }
    function Start-LocalDatabase { }
    function Read-LocalSecrets { return [pscustomobject]@{dbUser='unit_test';dbPassword='unit-test-only'} }
    function Get-Command { param($Name); return [pscustomobject]@{Source='C:\fake\runtime.exe'} }
    $script:backendVisited=$false;$script:frontendVisited=$false
    function Start-ManagedProcess {
        param($Name,$Port,$Executable,$Arguments,$Directory,$Marker)
        if ($Name -eq 'backend') { $script:backendVisited=$true; Assert-Test ($env:LOCAL_STOCK_DB_NAME -eq 'stock_platform_verify' -and $env:LOCAL_STOCK_SCORE_SCHEDULE -eq 'false') '验证后端配置错误' }
        if ($Name -eq 'frontend') {
            $script:frontendVisited=$true
            Assert-Test (!$env:LOCAL_STOCK_DB_PASSWORD -and !$env:PROB_DB_URL -and $env:VITE_BACKEND -eq 'http://127.0.0.1:18080') '前端继承了后端凭据或错误端口'
            throw '模拟前端启动失败'
        }
    }
    $oldPassword=[Environment]::GetEnvironmentVariable('LOCAL_STOCK_DB_PASSWORD','Process')
    $oldBackend=[Environment]::GetEnvironmentVariable('VITE_BACKEND','Process')
    try {
        $env:LOCAL_STOCK_DB_PASSWORD='previous-sentinel';$env:VITE_BACKEND='previous-backend'
        $caught=''
        try { Start-Platform } catch { $caught=$_.Exception.Message }
        Assert-Test ($caught -ceq '模拟前端启动失败' -and $script:backendVisited -and $script:frontendVisited) '启动流程未到达预期故障点'
        Assert-Test ($env:LOCAL_STOCK_DB_PASSWORD -ceq 'previous-sentinel' -and $env:VITE_BACKEND -ceq 'previous-backend') '异常后环境未恢复'
    } finally {
        [Environment]::SetEnvironmentVariable('LOCAL_STOCK_DB_PASSWORD',$oldPassword,'Process')
        [Environment]::SetEnvironmentVariable('VITE_BACKEND',$oldBackend,'Process')
    }
    }
    # 显式验证命令发现已恢复为系统实现；保留真实文件摘要计算，不在生产逻辑中绕过它。
    $resolvedHashCommand=Get-Command Get-FileHash -ErrorAction Stop
    Assert-Test ($resolvedHashCommand.Name -ceq 'Get-FileHash' -and $resolvedHashCommand.Source -ceq 'Microsoft.PowerShell.Utility') '启动测试的命令替身泄漏到后续测试'
    if ($PSVersionTable.PSVersion.Major -eq 5) {
        $expectedUtility=Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Utility\Microsoft.PowerShell.Utility.psd1'
        Assert-Test ($resolvedHashCommand.Module.Path -ieq $expectedUtility) 'PS5.1错误加载了其它PowerShell版本的Utility模块'
    }
    $passed++
    # 构建默认只调用一次完整验收，再执行跳过重复测试的打包；全部工具使用替身，不产生真实构建。
    # Maven/npm等替身也限制在本用例，避免后续用例复用伪造命令或进程检查函数。
    & {
    . Load-TestFunctions 'production'
    $RuntimeRoot=Join-Path $temporaryRoot 'build';$ApiRoot=$temporaryRoot;$WebRoot=$temporaryRoot;$JarPath=$sameJar
    $script:buildCalls=New-Object 'System.Collections.Generic.List[string]'
    $script:acceptanceFails=$false;$script:activeBuildRecord=$false
    function Test-PlatformEnvironment { }
    function Read-PlatformJson {
        param($Path)
        if ($script:activeBuildRecord) { return [pscustomobject]@{commandMarker=(Join-Path $ProjectRoot 'apps\api\target\application.jar')} }
        return $null
    }
    function Get-VerifiedProcess { param($Record); if ($Record) { return [pscustomobject]@{Id=12345} } }
    function Invoke-PlatformAcceptance {
        $script:buildCalls.Add('acceptance')
        if ($script:acceptanceFails) { throw '模拟完整验收失败' }
        return @{status='PASS';report='fixture';generatedAt='2026-11-17T00:00:00Z'}
    }
    function mvn.cmd { $script:buildCalls.Add('maven:'+($args -join ' '));$global:LASTEXITCODE=0 }
    function npm.cmd { $script:buildCalls.Add('web:'+($args -join ' '));$global:LASTEXITCODE=0 }
    Build-Platform
    $built=Get-Content -LiteralPath (Join-Path $RuntimeRoot 'build.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-Test ($script:buildCalls.Count -eq 3 -and $script:buildCalls[0] -eq 'acceptance' -and $script:buildCalls[1] -match '\-DskipTests package$' -and $script:buildCalls[2] -eq 'web:run build') '默认构建未按完整验收、Java打包、Web构建顺序执行一次'
    Assert-Test (!$built.testsSkipped -and $built.acceptance.status -eq 'PASS') '默认构建错误标记验收结果'
    $script:buildCalls.Clear()
    Build-Platform -SkipTests
    $built=Get-Content -LiteralPath (Join-Path $RuntimeRoot 'build.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-Test ($script:buildCalls.Count -eq 2 -and $built.testsSkipped -and $built.acceptance.status -eq 'SKIPPED') '显式跳过测试未准确记录'
    $script:buildCalls.Clear();$script:acceptanceFails=$true
    Assert-Throws { Build-Platform } '完整验收失败仍允许打包'
    Assert-Test ($script:buildCalls.Count -eq 1 -and $script:buildCalls[0] -eq 'acceptance') '验收失败后仍执行了打包命令'
    $script:buildCalls.Clear();$script:activeBuildRecord=$true
    Assert-Throws { Build-Platform -SkipTests } '使用中新产物未阻止构建'
    Assert-Test ($script:buildCalls.Count -eq 0) '运行中构建保护晚于测试或打包'
    }
    $passed++

    # 活跃watcher及持久化任务都必须阻止停机；不调用真实jcmd或SQL。
    . Load-TestFunctions 'production'
    function Read-PlatformJson { param($Path); return [pscustomobject]@{id=12345;startedAt=$now.ToUniversalTime().ToString('o')} }
    function Get-Process { param($Id,$ErrorAction); return [pscustomobject]@{Id=12345;StartTime=$now;Path='C:\fake\powershell.exe'} }
    Assert-Throws { Assert-WatcherIdle } '活跃watcher没有阻止停机'
    $fakeRunner=[pscustomobject]@{id=12345;startedAt=$now.ToUniversalTime().ToString('o')}
    $script:callerParent=12345
    $script:runnerFile=Join-Path $LegacyRoot 'deployment\Run-MarketPipeline.ps1'
    function Get-CimInstance {
        param($ClassName,$Filter)
        $number=[int]($Filter -replace '^ProcessId=','')
        if ($number -eq 12345) { return [pscustomobject]@{ProcessId=12345;ParentProcessId=1;CreationDate=$now;CommandLine=('powershell.exe -NoProfile -File "'+$script:runnerFile+'" -CrawlTaskId 5')} }
        if ($number -eq 22222) { return [pscustomobject]@{ProcessId=22222;ParentProcessId=$script:callerParent;CreationDate=$now.AddSeconds(5);CommandLine='powershell.exe child.ps1'} }
        return $null
    }
    Assert-Test (Test-VerifiedWatcherCaller $fakeRunner -CallerId 12345) '真实watcher自身被错误拒绝'
    Assert-Test (Test-VerifiedWatcherCaller $fakeRunner -CallerId 22222) '已验证子进程链被错误拒绝'
    $script:callerParent=1
    $env:PLATFORM_ALLOW_WATCHER='1'
    try { Assert-Test (!(Test-VerifiedWatcherCaller $fakeRunner -CallerId 22222)) '伪造环境变量可绕过祖先验证' } finally { Remove-Item Env:PLATFORM_ALLOW_WATCHER -ErrorAction SilentlyContinue }
    $script:runnerFile=Join-Path $temporaryRoot 'fake-watcher.ps1'
    Assert-Test (!(Test-VerifiedWatcherCaller $fakeRunner -CallerId 12345)) '冒用PID但脚本路径错误仍被放行'
    function Assert-WatcherIdle { }
    function Assert-DatabaseIdentity { }
    function Invoke-LocalSql { param($Sql); return 1 }
    Assert-Throws { Assert-NoActiveTasks $null } '持久化运行任务没有阻止停机'
    $passed++
    # 用当前PS解析全部入口和流水线，避免只验证公共函数而漏掉PS5.1语法差异。
    foreach ($directory in @('deployment','scripts')) {
        foreach ($file in @(Get-ChildItem -LiteralPath (Join-Path $root $directory) -Filter '*.ps1')) {
            $tokens=$null;$errors=$null
            [void][Management.Automation.Language.Parser]::ParseFile($file.FullName,[ref]$tokens,[ref]$errors)
            Assert-Test (@($errors).Count -eq 0) ('语法解析失败：'+$file.Name)
        }
    }
    $passed++
    # 流水线状态机另有专用纯内存回归；子脚本只提取函数，不执行真实采集、进程或数据库入口。
    & (Join-Path $PSScriptRoot 'Test-MarketPipeline.ps1')
    $passed++
    # 覆盖门禁使用独立日历，专用回归全部采用内存 SQL 替身，不能访问正在采集的数据库。
    & (Join-Path $PSScriptRoot 'Test-CoverageAudit.ps1')
    $passed++
    Write-Host "PASS: $passed 组离线部署回归；没有启动/停止服务、连接数据库或运行模型。"
} finally {
    # 仅删除本测试创建的GUID目录，先验证规范化路径属于固定测试根。
    $safePrefix=[IO.Path]::GetFullPath((Join-Path $root '.local\script-tests')).TrimEnd('\')+'\'
    $resolved=[IO.Path]::GetFullPath($temporaryRoot)
    if ($resolved.StartsWith($safePrefix,[StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolved)) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
