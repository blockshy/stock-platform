<#
平台操作函数。只定义函数，不在导入时执行启停、数据库写入或构建。
生产与验证各有独立进程记录；记录包含PID、UTC启动时间、可执行文件和命令路径。
#>
function Get-ProcessRecordPath {
    param([string]$Name)
    return Join-Path $RuntimeRoot "$Name-process.json"
}
function Get-VerifiedProcess {
    <# PID可能被系统复用，必须同时核对启动时间、程序路径和命令中的项目文件。 #>
    param([object]$Record)
    if (!$Record) { return $null }
    $process = Get-Process -Id ([int]$Record.id) -ErrorAction SilentlyContinue
    if (!$process) { return $null }
    if ($process.StartTime.ToUniversalTime().Ticks -ne ([datetime]$Record.startedAt).ToUniversalTime().Ticks -or $process.Path -ine $Record.executable) { throw '进程身份已变化，拒绝接管或停止。' }
    $native = Get-CimInstance Win32_Process -Filter "ProcessId=$($Record.id)"
    if (!$native -or !$native.CommandLine -or $native.CommandLine.IndexOf([string]$Record.commandMarker,[StringComparison]::OrdinalIgnoreCase) -lt 0) { throw '进程命令不属于记录中的项目，拒绝操作。' }
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort ([int]$Record.port) -ErrorAction SilentlyContinue)
    if ($listeners.Count -gt 0 -and @($listeners | Where-Object OwningProcess -ne $process.Id).Count -gt 0) { throw '记录端口被其它进程占用，拒绝操作。' }
    return $process
}
function Get-JarPayloadHash {
    <# 比较可执行代码及依赖的内容，忽略ZIP时间戳与打包元数据；不会解压或执行JAR。 #>
    param([string]$Path,[switch]$LegacyCultureSort)
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $parts = New-Object 'System.Collections.Generic.List[string]'
        $entries = @($archive.Entries | Where-Object { $_.Name -and ($_.FullName.StartsWith('BOOT-INF/classes/') -or $_.FullName.StartsWith('BOOT-INF/lib/')) })
        if ($LegacyCultureSort) {
            # 仅用于读取早期未标算法版本的切换记录，保持当时同一PowerShell版本的比较口径。
            $entries = @($entries | Sort-Object FullName)
        } else {
            # PS5.1/.NET Framework与PS7/.NET的文化排序不同；固定Ordinal才能跨版本重现摘要。
            [string[]]$names = @($entries | ForEach-Object FullName)
            [Array]::Sort($names,[StringComparer]::Ordinal)
            $entries = @($names | ForEach-Object { $archive.GetEntry($_) })
        }
        foreach ($entry in $entries) {
            $stream = $entry.Open()
            try { $digest = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','').ToLowerInvariant() } finally { $stream.Dispose() }
            $parts.Add($entry.FullName + ':' + $digest)
        }
        if ($parts.Count -eq 0) { throw 'JAR缺少Spring Boot代码及依赖，不能确认等效。' }
        return [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes(($parts -join "`n")))).Replace('-','').ToLowerInvariant()
    } finally { $sha.Dispose(); $archive.Dispose() }
}
function Get-LegacyRecord {
    <# 只读取旧记录并补充固定路径，不信任任意命令路径或陈旧PID。 #>
    param([ValidateSet('backend','frontend')][string]$Name)
    $saved = Read-PlatformJson (Join-Path $DataRoot "$Name-process.json")
    if (!$saved) { return $null }
    $marker = if ($Name -eq 'backend') { Join-Path $LegacyRoot 'stock\target\stock-0.0.1-SNAPSHOT.jar' } else { Join-Path $LegacyRoot 'stock-web\node_modules\vite\bin\vite.js' }
    $port = if ($Name -eq 'backend') { $ApiPort } else { $WebPort }
    $record = [pscustomobject]@{ id=$saved.id;startedAt=$saved.startedAt;executable=$saved.executable;commandMarker=$marker;port=$port;mode='legacy';profile=$Profile;sourceRoot=$LegacyRoot;engineDirectory=(Join-Path $LegacyRoot 'stock-probability-engine') }
    $process = Get-VerifiedProcess $record
    if (!$process) { return $null }
    return $record
}
function Test-VerifiedWatcherCaller {
    <#
    只有真实watcher自身或其子进程可执行自然阶段重启；不接受环境变量或命令行放行口令。
    watcher须同时匹配已保存PID、启动时间及白名单脚本路径。祖先链逐级核对创建时间，
    防止原父进程退出后PID被复用，误把普通用户调用识别为watcher后代。
    #>
    param([object]$Runner,[int]$CallerId=$PID)
    $runnerProcess = Get-Process -Id ([int]$Runner.id) -ErrorAction SilentlyContinue
    if (!$runnerProcess -or $runnerProcess.StartTime.ToUniversalTime().Ticks -ne ([datetime]$Runner.startedAt).ToUniversalTime().Ticks) { return $false }
    $nativeRunner = Get-CimInstance Win32_Process -Filter "ProcessId=$($Runner.id)"
    if (!$nativeRunner -or !$nativeRunner.CommandLine -or $nativeRunner.CommandLine -notmatch '(?i)(?:^|\s)-File\s+(?:"([^"]+)"|(\S+))') { return $false }
    $fileArgument = if ($Matches[1]) { $Matches[1] } else { $Matches[2] }
    try { $actualFile = [IO.Path]::GetFullPath($fileArgument) } catch { return $false }
    $allowed = @((Join-Path $LegacyRoot 'deployment\Run-MarketPipeline.ps1'),(Join-Path $ProjectRoot 'deployment\Run-MarketPipeline.ps1'))
    if (@($allowed | Where-Object { [IO.Path]::GetFullPath($_) -ieq $actualFile }).Count -ne 1) { return $false }
    $currentId = $CallerId
    $seen = @{}
    for ($depth=0; $depth -lt 32 -and $currentId -gt 0; $depth++) {
        if ($seen.ContainsKey($currentId)) { return $false }
        $seen[$currentId]=$true
        if ($currentId -eq [int]$Runner.id) { return $true }
        $child = Get-CimInstance Win32_Process -Filter "ProcessId=$currentId"
        if (!$child -or [int]$child.ParentProcessId -le 0) { return $false }
        $parent = Get-CimInstance Win32_Process -Filter "ProcessId=$($child.ParentProcessId)"
        if (!$parent -or !$child.CreationDate -or !$parent.CreationDate -or ([datetime]$parent.CreationDate).ToUniversalTime() -gt ([datetime]$child.CreationDate).ToUniversalTime()) { return $false }
        $currentId=[int]$child.ParentProcessId
    }
    return $false
}
function Assert-WatcherIdle {
    <# 外部调用必须等watcher结束；经核验的watcher调用链仍须继续通过数据库/GA/jcmd空闲检查。 #>
    if ($Profile -ne 'production') { return }
    $runner = Read-PlatformJson (Join-Path $DataRoot 'market-pipeline\runner-process.json')
    if (!$runner) { return }
    $process = Get-Process -Id ([int]$runner.id) -ErrorAction SilentlyContinue
    if ($process -and $process.StartTime.ToUniversalTime().Ticks -eq ([datetime]$runner.startedAt).ToUniversalTime().Ticks) {
        if (Test-VerifiedWatcherCaller $runner) { return }
        throw '现有市场流水线仍在运行；须等待补采、ETF及后续计算全部结束后再切换。'
    }
}
function Assert-NoActiveTasks {
    <# 全部检查成功且为空闲才允许停机；状态未知时不终止任何应用进程。 #>
    param([object]$BackendRecord)
    Assert-WatcherIdle
    Assert-DatabaseIdentity
    $busy = [int](Invoke-LocalSql "SELECT (SELECT COUNT(*) FROM crawl_log WHERE status='running')+(SELECT COUNT(*) FROM prob_run WHERE status='RUNNING')+(SELECT COUNT(*) FROM quant_backtest_task WHERE status IN ('PENDING','RUNNING'));")
    if ($busy -gt 0) { throw '数据库中仍有采集、概率或量化任务，拒绝停止/切换。' }
    if (!$BackendRecord) { return }
    $backend = Get-VerifiedProcess $BackendRecord
    if (!$backend) { return }
    $ga = Invoke-PlatformGet '/api/ga/jobs/status'
    if (@($ga.data | Where-Object running -eq $true).Count -gt 0) { throw '仍有GA任务运行。' }
    # 同步评分与短期计算无可靠任务表，保留已核验JDK同目录jcmd只读线程检查。
    $jcmd = Join-Path (Split-Path -Parent $backend.Path) 'jcmd.exe'
    if (!(Test-Path -LiteralPath $jcmd)) { throw '缺少jcmd，无法确认同步计算状态。' }
    $dump = @(& $jcmd $backend.Id Thread.print 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $dump -notmatch '(?m)^Full thread dump ') { throw '线程检查失败，服务保持运行。' }
    if ($dump -match '(?m)^\s+at\s+com\.li\.stock\.service\.(?:ScoreCalculationService|ScoreScheduleService|ShortTermForecastHistoryService|ShortTermForecastService)\.(?:rebuild|rebuildRecent)\(') { throw '评分或短期预测仍在计算，拒绝停止。' }
}
function Test-PlatformEnvironment {
    <# 只检查程序版本和文件存在；不自动安装、不启动Spring或运行模型。 #>
    foreach ($name in @('java','node','npm.cmd','mvn.cmd')) {
        if (!(Get-Command $name -ErrorAction SilentlyContinue)) { throw "缺少命令：$name" }
    }
    foreach ($path in @($PythonExe,$MysqlExe,$MysqlDumpExe,$MysqlClientConfig,$SecretsFile,(Join-Path $WebRoot 'node_modules'))) {
        if (!(Test-Path -LiteralPath $path)) { throw "缺少依赖或配置：$path" }
    }
    # Java版本写到stderr；PS5.1把重定向stderr包装为ErrorRecord，不能将正常版本信息当作失败。
    $savedPreference = $ErrorActionPreference
    try { $ErrorActionPreference = 'Continue'; $javaVersion = @(& java -version 2>&1) -join ' ' } finally { $ErrorActionPreference = $savedPreference }
    if ($javaVersion -notmatch 'version "21[.\"]') { throw '需要JDK21。' }
    $nodeVersion = [string](& node --version)
    if ($nodeVersion -notmatch '^v24\.') { throw '当前基线使用Node24，请保持依赖运行版本一致。' }
    $pythonVersion = [string](& $PythonExe --version)
    if ($pythonVersion -notmatch '^Python 3\.10\.') { throw '当前引擎基线使用Python3.10。' }
    [pscustomobject]@{profile=$Profile;sourceRoot=$ProjectRoot;dataRoot=$DataRoot;runtimeRoot=$RuntimeRoot;database=$Database;databasePort=$DbPort;apiPort=$ApiPort;webPort=$WebPort;java='21';node=$nodeVersion;python=$pythonVersion}
}
function Invoke-PlatformAcceptance {
    <#
    唯一完整离线验收入口：包含前端任务观察器/check:ui、Python原测试与迁移契约、
    Java单元及进程契约、部署回归。独立PS5.1进程隔离验收脚本的变量和测试替身。
    不传SkipJava，退出码非零或报告不是PASS时禁止继续生成部署产物。
    #>
    $runner = Join-Path $ProjectRoot 'tests\run_checks.ps1'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $runner -ConfigPath $ConfigPath | Out-Host
    if ($LASTEXITCODE -ne 0) { throw '统一离线验收失败，未继续打包。' }
    $reportPath = Join-Path $ProjectRoot '.local\runtime\verification\reports\offline-checks.json'
    $report = Read-PlatformJson $reportPath
    if (!$report -or $report.status -cne 'PASS') { throw '统一验收报告缺失或不是完整PASS，未继续打包。' }
    return @{status='PASS';report=$reportPath;generatedAt=$report.generatedAt}
}
function Build-Platform {
    param([switch]$SkipTests)
    Test-PlatformEnvironment | Out-Null
    # 两个配置共用源码与构建产物；任一配置正在使用新路径时禁止原地覆盖JAR或dist。
    foreach ($profileName in @('production','verification')) {
        $root = Resolve-ProjectPath $PlatformConfig.profiles.$profileName.runtimeRoot
        foreach ($name in @('backend','frontend')) {
            $record = Read-PlatformJson (Join-Path $root "$name-process.json")
            if ($record -and $record.commandMarker.StartsWith($ProjectRoot,[StringComparison]::OrdinalIgnoreCase) -and (Get-VerifiedProcess $record)) { throw '新项目仍在使用构建产物，请先停止对应配置后构建。' }
        }
    }
    # 验收默认运行完整入口一次，不维护会随新增测试遗漏的第二份测试类清单。
    $acceptance = @{status='SKIPPED';report=$null;generatedAt=$null}
    if (!$SkipTests) { $acceptance = Invoke-PlatformAcceptance }
    $mavenArgs = @('-B','-ntp','-s',(Join-Path $PSScriptRoot 'maven-settings.xml'),'-gs',(Join-Path $PSScriptRoot 'maven-global-settings.xml'),"-Dmaven.repo.local=$(Join-Path $DataRoot 'maven')")
    Push-Location $ApiRoot
    # 上一步已完成Java验收，打包阶段不重复执行测试，也不触发默认Spring集成测试。
    try { & mvn.cmd @mavenArgs -DskipTests package; if ($LASTEXITCODE -ne 0) { throw 'Java构建失败。' } } finally { Pop-Location }
    Push-Location $WebRoot
    try {
        & npm.cmd run build; if ($LASTEXITCODE -ne 0) { throw '前端构建失败。' }
    } finally { Pop-Location }
    New-Item -ItemType Directory -Path $RuntimeRoot -Force | Out-Null
    Save-PlatformJson (Join-Path $RuntimeRoot 'build.json') @{ builtAt=[DateTimeOffset]::Now.ToString('o');jar=$JarPath;jarSha256=(Get-FileHash -LiteralPath $JarPath -Algorithm SHA256).Hash;jarPayloadHash=(Get-JarPayloadHash $JarPath);jarPayloadHashAlgorithm='boot-inf-sha256-ordinal-v1';testsSkipped=[bool]$SkipTests;acceptance=$acceptance }
}
function Start-ManagedProcess {
    param([string]$Name,[int]$Port,[string]$Executable,[string]$Arguments,[string]$Directory,[string]$Marker)
    $statePath = Get-ProcessRecordPath $Name
    $record = Read-PlatformJson $statePath
    if (Test-LocalPort $Port) {
        if ($record -and (Get-VerifiedProcess $record)) { Write-Host "$Name 已运行（$($record.mode)），未重启或切换代码。"; return }
        throw "端口$Port已由未接管进程占用；不能启动第二后端或覆盖前端。"
    }
    if ($record -and (Get-VerifiedProcess $record)) { throw "$Name进程仍存活但未监听，拒绝启动重复进程。" }
    $process = Start-Process -FilePath $Executable -ArgumentList $Arguments -WorkingDirectory $Directory -WindowStyle Hidden -RedirectStandardOutput (Join-Path $LogRoot "$Name.out.log") -RedirectStandardError (Join-Path $LogRoot "$Name.err.log") -PassThru
    Save-PlatformJson $statePath @{ id=$process.Id;startedAt=$process.StartTime.ToUniversalTime().ToString('o');executable=$Executable;commandMarker=$Marker;port=$Port;profile=$Profile;sourceRoot=$ProjectRoot;mode='native';engineDirectory=$EngineRoot;database=$Database }
    Wait-LocalPort $Port 90
    Get-VerifiedProcess (Read-PlatformJson $statePath) | Out-Null
}
function Start-Platform {
    param([switch]$FrontendOnly)
    Test-PlatformEnvironment | Out-Null
    foreach ($path in @($JarPath,$VitePath,(Join-Path $WebRoot 'dist\index.html'))) { if (!(Test-Path -LiteralPath $path)) { throw "缺少构建产物：$path" } }
    New-Item -ItemType Directory -Path $RuntimeRoot,$LogRoot,$EngineRuntimeRoot -Force | Out-Null
    Start-LocalDatabase
    if (!$FrontendOnly -and !(Test-LocalPort $ApiPort) -and $Profile -eq 'production') {
        Assert-NoActiveTasks $null
        Sync-LegacyEngineAssets
    }
    $secrets = Read-LocalSecrets
    $variables = @{
        LOCAL_STOCK_DB_USER=[string]$secrets.dbUser;LOCAL_STOCK_DB_PASSWORD=[string]$secrets.dbPassword;
        LOCAL_STOCK_DB_PORT=[string]$DbPort;LOCAL_STOCK_DB_NAME=$Database;LOCAL_STOCK_API_PORT=[string]$ApiPort;
        LOCAL_STOCK_SCORE_SCHEDULE=([bool]($Profile -eq 'production' -and $ProfileConfig.scoreScheduleEnabled)).ToString().ToLowerInvariant();
        LOCAL_STOCK_ENGINE=$EngineRoot;LOCAL_STOCK_PYTHON=$PythonExe;
        LOCAL_STOCK_PROB_CONFIG=(Join-Path $EngineRoot 'config.yaml');LOCAL_STOCK_PROB_FEATURES=(Join-Path $EngineRoot 'features.yaml');
        PROB_CONFIG=(Join-Path $EngineRoot 'config.yaml');PROB_FEATURES=(Join-Path $EngineRoot 'features.yaml');PROB_RUNTIME_ROOT=$EngineRuntimeRoot;
        PROB_DB_URL=('mysql+pymysql://'+[uri]::EscapeDataString([string]$secrets.dbUser)+':'+[uri]::EscapeDataString([string]$secrets.dbPassword)+"@127.0.0.1:$DbPort/$Database");
        PYTHONUTF8='1';PYTHONIOENCODING='utf-8';VITE_BACKEND="http://127.0.0.1:$ApiPort"
    }
    $previous = @{}
    foreach ($key in $variables.Keys) { $previous[$key] = [Environment]::GetEnvironmentVariable($key,'Process') }
    try {
        foreach ($key in $variables.Keys) { [Environment]::SetEnvironmentVariable($key,[string]$variables[$key],'Process') }
        if (!$FrontendOnly) {
            if ([string]$ProfileConfig.javaMaxHeap -notmatch '^[1-9][0-9]*[mg]$') { throw 'Java堆配置格式错误。' }
            $configFile = Join-Path $PSScriptRoot 'application-local.yaml'
            Start-ManagedProcess 'backend' $ApiPort (Get-Command java).Source "-Xms256m -Xmx$($ProfileConfig.javaMaxHeap) -Dfile.encoding=UTF-8 -jar `"$JarPath`" --spring.config.additional-location=`"file:$($configFile.Replace('\','/'))`"" $ApiRoot $JarPath
        }
        # 前端只需要代理地址，必须清除所有数据库及引擎环境变量后再创建Node进程。
        foreach ($key in $variables.Keys) { if ($key -ne 'VITE_BACKEND') { [Environment]::SetEnvironmentVariable($key,$null,'Process') } }
        Start-ManagedProcess 'frontend' $WebPort (Get-Command node).Source "`"$VitePath`" preview --host 127.0.0.1 --port $WebPort --strictPort" $WebRoot $VitePath
    } finally {
        foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key,$previous[$key],'Process') }
    }
    Write-Host "页面：http://127.0.0.1:$WebPort；数据库：$Database（$Profile）。"
}
function Stop-RecordedProcess {
    param([object]$Record)
    $process = Get-VerifiedProcess $Record
    if ($process) { Stop-Process -Id $process.Id; if (!$process.WaitForExit(15000)) { throw '应用尚未退出，未继续切换。' } }
}
function Stop-Platform {
    param([switch]$FrontendOnly)
    $backend = Read-PlatformJson (Get-ProcessRecordPath 'backend')
    if (!$FrontendOnly -and !$backend -and $Profile -eq 'production' -and (Get-LegacyRecord 'backend')) { throw '旧后端仍运行但尚未认证接管；不能报告已经停止，请先Adopt或按切换流程处理。' }
    if (!$FrontendOnly) { Assert-NoActiveTasks $backend }
    $names = if ($FrontendOnly) { @('frontend') } else { @('frontend','backend') }
    $records=@{}
    foreach ($name in $names) {
        $records[$name]=Read-PlatformJson (Get-ProcessRecordPath $name)
        if (!$records[$name] -and $name -eq 'frontend' -and $Profile -eq 'production') { $records[$name]=Get-LegacyRecord 'frontend' }
    }
    # 先验证全部进程身份，避免停止第一个之后才发现第二个记录不可信。
    foreach ($name in $names) { Get-VerifiedProcess $records[$name] | Out-Null }
    foreach ($name in $names) {
        $path = Get-ProcessRecordPath $name
        $record = $records[$name]
        if ($record) { Stop-RecordedProcess $record; if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path } }
    }
    Write-Host '所选应用已停止；共享MySQL实例保持运行。'
}
function Get-PlatformStatus {
    $result = [ordered]@{ profile=$Profile;database=$Database;databasePort=$DbPort;databaseListening=(Test-LocalPort $DbPort);dataRoot=$DataRoot;runtimeRoot=$RuntimeRoot }
    foreach ($name in @('backend','frontend')) {
        $record = Read-PlatformJson (Get-ProcessRecordPath $name)
        if (!$record -and $Profile -eq 'production') { $record = Get-LegacyRecord $name }
        $result[$name] = if ($record) { @{running=[bool](Get-VerifiedProcess $record);id=$record.id;mode=$record.mode;commandPath=$record.commandMarker;port=$record.port;engineDirectory=$record.engineDirectory} } else { @{running=$false;mode='not-managed'} }
    }
    $pipeline = Read-PlatformJson (Join-Path $DataRoot 'market-pipeline\state.json')
    if ($pipeline -and $Profile -eq 'production') { $result.pipeline = $pipeline }
    return [pscustomobject]$result
}
function Adopt-ExistingBackend {
    <#
    只接管管理记录，不停止或重启任何进程。要求旧运行JAR未在进程启动后被改写，且
    新旧BOOT-INF/classes与BOOT-INF/lib内容一致；ZIP时间戳差异不影响判定。
    记录明确标为legacy-adopted，保留真实旧命令及引擎路径，不能宣称新代码已经启动。
    #>
    if ($Profile -ne 'production') { throw '只有生产配置可接管现有后端。' }
    Assert-DatabaseIdentity
    $record = Get-LegacyRecord 'backend'
    if (!$record -or !(Test-LocalPort $ApiPort)) { throw '找不到可认证的旧后端。' }
    $process = Get-VerifiedProcess $record
    $oldJar = Get-Item -LiteralPath $record.commandMarker
    if ($oldJar.LastWriteTimeUtc -gt $process.StartTime.ToUniversalTime()) { throw '旧JAR在进程启动后被修改，磁盘文件不能证明运行内容，拒绝认证接管。' }
    $oldPayload = Get-JarPayloadHash $oldJar.FullName
    $newPayload = Get-JarPayloadHash $JarPath
    if ($oldPayload -cne $newPayload) { throw '新旧JAR代码或依赖不一致，只能等待任务结束后正式切换。' }
    $record.mode = 'legacy-adopted'
    $record | Add-Member -NotePropertyName payloadHash -NotePropertyValue $oldPayload -Force
    $record | Add-Member -NotePropertyName payloadHashAlgorithm -NotePropertyValue 'boot-inf-sha256-ordinal-v1' -Force
    $record | Add-Member -NotePropertyName verifiedTargetJar -NotePropertyValue $JarPath -Force
    $record | Add-Member -NotePropertyName adoptedAt -NotePropertyValue ([DateTimeOffset]::Now.ToString('o')) -Force
    $record | Add-Member -NotePropertyName database -NotePropertyValue $Database -Force
    New-Item -ItemType Directory -Path $RuntimeRoot -Force | Out-Null
    Save-PlatformJson (Get-ProcessRecordPath 'backend') $record
    Write-Host "已认证接管旧后端PID $($record.id)，未重启；Java仍使用旧JAR和旧引擎目录。"
    return $record
}
function Sync-LegacyEngineAssets {
    <#
    只在生产后端真正启动前且任务已空闲时同步旧运行产物。接管期间旧流水线可能继续
    训练，因此不能依赖整合开始时的一次性拷贝。保留旧文件及数据库旧绝对路径可读性。
    新运行目录已有更晚文件时不覆盖；回滚后旧引擎新生成的文件仍可再次增量同步。
    #>
    if ($Profile -ne 'production') { return }
    $copied = 0
    $sourceRoot = Join-Path $LegacyRoot 'stock-probability-engine'
    foreach ($name in @('artifacts','logs')) {
        $source = Join-Path $sourceRoot $name
        if (!(Test-Path -LiteralPath $source)) { continue }
        foreach ($file in @(Get-ChildItem -LiteralPath $source -File -Recurse)) {
            $relative = $file.FullName.Substring($sourceRoot.TrimEnd('\').Length + 1)
            $target = Assert-ChildPath (Join-Path $EngineRuntimeRoot $relative) $EngineRuntimeRoot
            $existing = Get-Item -LiteralPath $target -ErrorAction SilentlyContinue
            if (!$existing -or $file.LastWriteTimeUtc -gt $existing.LastWriteTimeUtc) {
                New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
                Copy-Item -LiteralPath $file.FullName -Destination $target -Force
                $copied++
            }
        }
    }
    Save-PlatformJson (Join-Path $RuntimeRoot 'engine-sync.json') @{at=[DateTimeOffset]::Now.ToString('o');source=$sourceRoot;destination=$EngineRuntimeRoot;copiedFiles=$copied;oldPathsRetained=$true}
}
function Backup-Platform {
    <# 一致性逻辑备份，不复制InnoDB活动文件。模型及配置备份必须在任务空闲后进行。 #>
    $backend = Read-PlatformJson (Get-ProcessRecordPath 'backend')
    if (!$backend -and $Profile -eq 'production') { $backend = Get-LegacyRecord 'backend' }
    Assert-NoActiveTasks $backend
    $backupRoot = Join-Path $ProjectRoot '.local\backups'
    $path = Assert-ChildPath (Join-Path $backupRoot ($Profile + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))) $backupRoot
    New-Item -ItemType Directory -Path $path -Force | Out-Null
    $sqlFile = Join-Path $path 'database.sql'
    # --result-file由MySQL按UTF-8直接写文件，避免PS5.1重定向编码损坏中文。
    & $MysqlDumpExe "--defaults-file=$MysqlClientConfig" --host=127.0.0.1 "--port=$DbPort" --single-transaction --routines --events --triggers --hex-blob --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 "--result-file=$sqlFile" $Database
    if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $sqlFile) -or (Get-Item -LiteralPath $sqlFile).Length -eq 0) { throw "数据库备份未完成，不能用于恢复：$path" }
    foreach ($item in @(@{source=$EngineRuntimeRoot;name='engine-runtime'},@{source=(Join-Path $LegacyRoot 'stock-probability-engine\artifacts');name='legacy-artifacts'},@{source=(Join-Path $EngineRoot 'engine');name='engine-source'},@{source=(Join-Path $EngineRoot 'config.yaml');name='engine-config.yaml'},@{source=(Join-Path $EngineRoot 'features.yaml');name='engine-features.yaml'},@{source=(Join-Path $EngineRoot 'requirements.txt');name='engine-requirements.txt'},@{source=$PSScriptRoot;name='deployment'},@{source=$SecretsFile;name='application-secrets.json'},@{source=$MysqlClientConfig;name='mysql-client.ini'},@{source=$JarPath;name='application.jar'},@{source=(Join-Path $WebRoot 'dist');name='web-dist'})) {
        if (Test-Path -LiteralPath $item.source) { Copy-Item -LiteralPath $item.source -Destination (Join-Path $path $item.name) -Recurse -Force }
    }
    $manifest = @{schema=1;createdAt=[DateTimeOffset]::Now.ToString('o');profile=$Profile;database=$Database;dataRoot=$DataRoot;sqlSha256=(Get-FileHash -LiteralPath $sqlFile -Algorithm SHA256).Hash;backend=$backend;sourceRoot=$ProjectRoot;containsPrivateCredentials=$true;complete=$true}
    Save-PlatformJson (Join-Path $path 'manifest.json') $manifest
    Write-Host "备份完成：$path（含本机凭据，不得提交或分享）。"
    return $path
}
function Switch-Platform {
    param([switch]$FrontendOnly)
    if ($Profile -ne 'production') { throw '验证环境直接Start/Stop，无需生产切换。' }
    Test-PlatformEnvironment | Out-Null
    foreach ($path in @($JarPath,$VitePath,(Join-Path $WebRoot 'dist\index.html'))) { if (!(Test-Path -LiteralPath $path)) { throw "缺少构建产物：$path" } }
    $backend = Read-PlatformJson (Get-ProcessRecordPath 'backend')
    if (!$backend) { $backend = Get-LegacyRecord 'backend' }
    $frontend = Read-PlatformJson (Get-ProcessRecordPath 'frontend')
    if (!$frontend) { $frontend = Get-LegacyRecord 'frontend' }
    Get-VerifiedProcess $frontend | Out-Null
    Get-VerifiedProcess $backend | Out-Null
    $backup = $null
    if (!$FrontendOnly) { Assert-NoActiveTasks $backend; $backup = Backup-Platform }
    New-Item -ItemType Directory -Path $RuntimeRoot -Force | Out-Null
    $legacyEngine = Join-Path $LegacyRoot 'stock-probability-engine'
    $cutover = @{at=[DateTimeOffset]::Now.ToString('o');phase='PREPARED';frontendOnly=[bool]$FrontendOnly;previousBackend=$backend;previousFrontend=$frontend;backup=$backup;legacyRoot=$LegacyRoot;legacyJarPayloadHash=(Get-JarPayloadHash (Join-Path $LegacyRoot 'stock\target\stock-0.0.1-SNAPSHOT.jar'));legacyJarPayloadHashAlgorithm='boot-inf-sha256-ordinal-v1';legacyConfigSha256=(Get-FileHash -LiteralPath (Join-Path $legacyEngine 'config.yaml') -Algorithm SHA256).Hash;legacyFeaturesSha256=(Get-FileHash -LiteralPath (Join-Path $legacyEngine 'features.yaml') -Algorithm SHA256).Hash}
    Save-PlatformJson (Join-Path $RuntimeRoot 'cutover.json') $cutover
    # 前端单独切换不影响Java或数据库任务；后端切换必须先通过全部空闲检查。
    Stop-RecordedProcess $frontend
    $frontRecord = Get-ProcessRecordPath 'frontend'
    if (Test-Path -LiteralPath $frontRecord) { Remove-Item -LiteralPath $frontRecord }
    if (!$FrontendOnly) {
        Stop-RecordedProcess $backend
        $backRecord = Get-ProcessRecordPath 'backend'
        if (Test-Path -LiteralPath $backRecord) { Remove-Item -LiteralPath $backRecord }
    }
    $cutover.phase = 'OLD_APPLICATION_STOPPED'
    Save-PlatformJson (Join-Path $RuntimeRoot 'cutover.json') $cutover
    Start-Platform -FrontendOnly:$FrontendOnly
    $cutover.phase = 'NEW_APPLICATION_STARTED'
    Save-PlatformJson (Join-Path $RuntimeRoot 'cutover.json') $cutover
    Write-Host '应用切换已完成；请继续执行接口与页面验收，运行模式以Status输出为准。'
}
function Rollback-Platform {
    <# 回滚只切换应用，不用旧数据库备份覆盖切换后产生的新数据；数据库恢复需单独审议。 #>
    if ($Profile -ne 'production') { throw '只支持生产应用回滚。' }
    $cutover = Read-PlatformJson (Join-Path $RuntimeRoot 'cutover.json')
    if (!$cutover) { throw '缺少切换记录，不能猜测回滚目标。' }
    $legacyJar = Join-Path $LegacyRoot 'stock\target\stock-0.0.1-SNAPSHOT.jar'
    $legacyEngine = Join-Path $LegacyRoot 'stock-probability-engine'
    $algorithm = $cutover.PSObject.Properties['legacyJarPayloadHashAlgorithm']
    if ($algorithm -and $algorithm.Value -cne 'boot-inf-sha256-ordinal-v1') { throw '未知JAR摘要算法，拒绝猜测回滚目标。' }
    $rollbackPayload = Get-JarPayloadHash $legacyJar -LegacyCultureSort:(!$algorithm)
    if ($rollbackPayload -cne $cutover.legacyJarPayloadHash -or (Get-FileHash -LiteralPath (Join-Path $legacyEngine 'config.yaml') -Algorithm SHA256).Hash -cne $cutover.legacyConfigSha256 -or (Get-FileHash -LiteralPath (Join-Path $legacyEngine 'features.yaml') -Algorithm SHA256).Hash -cne $cutover.legacyFeaturesSha256) { throw '旧JAR或模型配置摘要不匹配；早期未标算法版本的记录须使用切换时的PowerShell版本核验。' }
    if ($cutover.frontendOnly) {
        Stop-Platform -FrontendOnly
        $WebRoot = Join-Path $LegacyRoot 'stock-web'
        $VitePath = Join-Path $WebRoot 'node_modules\vite\bin\vite.js'
        Start-Platform -FrontendOnly
        $record = Read-PlatformJson (Get-ProcessRecordPath 'frontend')
        $record.mode = 'legacy-rollback'; $record.sourceRoot = $LegacyRoot
        Save-PlatformJson (Get-ProcessRecordPath 'frontend') $record
    } else {
        Stop-Platform
        # 显式使用旧模块路径，不依赖可能已改成兼容代理的旧Start.ps1；管理记录仍在新runtime。
        # PowerShell动态作用域让Start-Platform读取本函数的局部路径，函数返回后不污染新配置。
        $ApiRoot = Join-Path $LegacyRoot 'stock'
        $WebRoot = Join-Path $LegacyRoot 'stock-web'
        $EngineRoot = $legacyEngine
        $EngineRuntimeRoot = $legacyEngine
        $JarPath = $legacyJar
        $PythonExe = Join-Path $legacyEngine '.venv\Scripts\python.exe'
        $VitePath = Join-Path $WebRoot 'node_modules\vite\bin\vite.js'
        Start-Platform
        foreach ($name in @('backend','frontend')) {
            $record = Read-PlatformJson (Get-ProcessRecordPath $name)
            $record.mode = 'legacy-rollback'; $record.sourceRoot = $LegacyRoot
            Save-PlatformJson (Get-ProcessRecordPath $name) $record
        }
    }
    $cutover.phase = 'ROLLED_BACK'
    Save-PlatformJson (Join-Path $RuntimeRoot 'cutover.json') $cutover
    Write-Host '已回滚应用，数据库未恢复或覆盖。'
}
