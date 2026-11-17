<#
.SYNOPSIS
统一运行平台离线验收，不启动应用服务、采集或模型任务。
.DESCRIPTION
依次检查前端任务观察器、界面规则、Python 等效性、Java 单元契约及部署脚本。
Java 显式排除需要真实 Spring/数据库的两个集成类；Maven 使用 -o，缺少缓存时失败，
依赖准备应在安装阶段完成。部署脚本只做语法解析及内存替身测试。
.PARAMETER SkipJava
由协调者正在构建同一 target 目录时使用，避免并发 Maven。使用后报告标记 PARTIAL，
不能作为全平台验收通过的证据；默认不跳过 Java。
.PARAMETER ConfigPath
与部署入口使用同一平台配置文件；省略时优先读取 deployment/platform.local.json，
不存在时回退到公开的 deployment/platform.config.json。
自定义 dataRoot 仅决定 Maven 离线缓存位置，不改变测试范围。
#>
[CmdletBinding()]
param([switch]$SkipJava, [string]$ConfigPath='')

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$PlatformConfigPath = if ([string]::IsNullOrWhiteSpace($ConfigPath)) {
    # 与启动器保持相同优先级，避免测试误用另一套 Maven 缓存或数据根目录。
    $localConfigPath = Join-Path $ProjectRoot 'deployment\platform.local.json'
    if (Test-Path -LiteralPath $localConfigPath) { $localConfigPath } else { Join-Path $ProjectRoot 'deployment\platform.config.json' }
} elseif ([IO.Path]::IsPathRooted($ConfigPath)) {
    [IO.Path]::GetFullPath($ConfigPath)
} else {
    [IO.Path]::GetFullPath((Join-Path $ProjectRoot $ConfigPath))
}
$ReportRoot = Join-Path $ProjectRoot '.local\runtime\verification\reports'
$checks = New-Object 'System.Collections.Generic.List[object]'

function Invoke-CheckedNative {
    <# 用参数数组传递含空格路径与 Maven 过滤表达式；只在目标目录执行已命名的工具。 #>
    param([string]$Executable, [string[]]$Arguments, [string]$Directory)
    Push-Location -LiteralPath $Directory
    try {
        & $Executable @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "检查工具退出码为 ${LASTEXITCODE}：$([IO.Path]::GetFileName($Executable))"
        }
    } finally {
        Pop-Location
    }
}

function Invoke-OfflineCheck {
    <# 每一步独立计时；失败立即结束后续检查，并在 finally 中保存已执行步骤的真实结果。 #>
    param([string]$Name, [scriptblock]$Action)
    Write-Host "正在检查：$Name"
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try {
        & $Action
        $checks.Add([pscustomobject]@{name=$Name;status='PASS';seconds=[math]::Round($watch.Elapsed.TotalSeconds, 3)})
    } catch {
        # 只记录当前离线步骤的可读失败原因，不转储环境变量或连接配置。
        $checks.Add([pscustomobject]@{name=$Name;status='FAIL';seconds=[math]::Round($watch.Elapsed.TotalSeconds, 3);message=$_.Exception.Message})
        throw
    } finally {
        $watch.Stop()
    }
}

try {
    Invoke-OfflineCheck '前端任务观察器离线测试' {
        $node = (Get-Command node -ErrorAction Stop).Source
        Invoke-CheckedNative $node @('--test','src/prob/runMonitor.test.js','src/pages/crawlerMonitor.test.js') `
            (Join-Path $ProjectRoot 'apps\web')
    }

    Invoke-OfflineCheck '前端界面规则' {
        $npm = (Get-Command npm.cmd -ErrorAction Stop).Source
        Invoke-CheckedNative $npm @('run','check:ui') $ProjectRoot
    }

    Invoke-OfflineCheck 'Python 原测试与迁移等效契约' {
        $python = Join-Path $ProjectRoot 'engines\probability\.venv\Scripts\python.exe'
        if (!(Test-Path -LiteralPath $python)) { throw '缺少新项目 Python 虚拟环境，请先完成依赖准备。' }
        Invoke-CheckedNative $python @('-X','utf8','tests/run_probability_acceptance.py') $ProjectRoot
    }

    if ($SkipJava) {
        $checks.Add([pscustomobject]@{name='Java 离线单元与进程契约';status='SKIPPED';seconds=0;message='由调用者显式跳过，不能视为完整验收。'})
    } else {
        Invoke-OfflineCheck 'Java 离线单元与进程契约' {
            $maven = (Get-Command mvn.cmd -ErrorAction Stop).Source
            # 与部署入口复用同一离线仓库和镜像设置；不使用用户目录中的隐式 Maven 缓存。
            $config = Get-Content -LiteralPath $PlatformConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
            $configuredDataRoot = [string]$config.dataRoot
            $dataPath = if ([IO.Path]::IsPathRooted($configuredDataRoot)) { $configuredDataRoot } else { Join-Path $ProjectRoot $configuredDataRoot }
            $mavenRepository = Join-Path ([IO.Path]::GetFullPath($dataPath)) 'maven'
            $userSettings = Join-Path $ProjectRoot 'deployment\maven-settings.xml'
            $globalSettings = Join-Path $ProjectRoot 'deployment\maven-global-settings.xml'
            # 这两个类会创建真实 Spring 上下文，必须由隔离数据库集成入口另行运行。
            # 其余测试使用 mock/内存对象；单一过滤参数防止 PowerShell 将逗号拆为参数。
            Invoke-CheckedNative $maven @('-o','-s',$userSettings,'-gs',$globalSettings,
                "-Dmaven.repo.local=$mavenRepository",'-Dtest=*,!GaApiContractTest,!StockApplicationTests','test') `
                (Join-Path $ProjectRoot 'apps\api')
        }
    }

    Invoke-OfflineCheck 'PowerShell 入口语法' {
        $scriptFiles = @(Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'deployment'),$PSScriptRoot `
            -Filter '*.ps1' -File -Recurse)
        foreach ($file in $scriptFiles) {
            $tokens = $null
            $errors = $null
            [System.Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$errors) | Out-Null
            if ($errors.Count -gt 0) {
                $first = $errors[0]
                throw "脚本语法错误：$($file.FullName):$($first.Extent.StartLineNumber) $($first.Message)"
            }
        }
        Write-Host "语法通过：$($scriptFiles.Count) 个 PowerShell 文件。"
    }

    Invoke-OfflineCheck '部署脚本内存替身回归' {
        # 独立子进程隔离测试中对 Get-Process 等函数的替换，防止其污染后续检查作用域。
        $powershell = (Get-Command powershell.exe -ErrorAction Stop).Source
        Invoke-CheckedNative $powershell @('-NoProfile','-ExecutionPolicy','Bypass','-File',
            (Join-Path $ProjectRoot 'deployment\Test-PlatformScripts.ps1')) $ProjectRoot
    }
} finally {
    New-Item -ItemType Directory -Path $ReportRoot -Force | Out-Null
    $failed = @($checks | Where-Object status -eq 'FAIL').Count
    $skipped = @($checks | Where-Object status -eq 'SKIPPED').Count
    $status = if ($failed -gt 0) { 'FAIL' } elseif ($skipped -gt 0) { 'PARTIAL' } else { 'PASS' }
    $report = [ordered]@{generatedAt=[DateTimeOffset]::UtcNow.ToString('o');status=$status;checks=@($checks.ToArray());offline=$true}
    $destination = Join-Path $ReportRoot 'offline-checks.json'
    [IO.File]::WriteAllText($destination, ($report | ConvertTo-Json -Depth 8), (New-Object Text.UTF8Encoding($false)))
    Write-Host "离线检查状态：$status；报告：$destination"
}
