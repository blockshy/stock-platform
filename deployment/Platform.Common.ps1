<# 统一部署公共函数。加载配置不连接数据库、不写文件、不启动进程；兼容 PowerShell 5.1。 #>
param([ValidateSet('production','verification')][string]$Profile='production',[string]$ConfigPath='')
# 从PS7经npm/cmd启动PS5.1时，继承的PSModulePath可能优先指向PS7模块，导致自带
# Get-FileHash等脚本函数无法自动加载。显式绑定当前Windows PowerShell自带模块，
# 不修改用户/系统PSModulePath，不以重新实现摘要或跳过校验的方式掩盖环境问题。
if ($PSVersionTable.PSVersion.Major -eq 5) {
    $platformUtilityManifest = Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Utility\Microsoft.PowerShell.Utility.psd1'
    Import-Module -Name $platformUtilityManifest -ErrorAction Stop
}
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$ConfigPath) {
    # 机器安装路径只写入被 Git 忽略的本地配置。显式 -ConfigPath 优先级最高；
    # 未传入时优先读取完整的本地配置，否则回退到可公开的通用配置。
    # 两份文件不做隐式合并，防止局部覆盖使生产与验收环境混用端口、账号或目录。
    $localConfigPath = Join-Path $PSScriptRoot 'platform.local.json'
    $ConfigPath = if (Test-Path -LiteralPath $localConfigPath) { $localConfigPath } else { Join-Path $PSScriptRoot 'platform.config.json' }
}
$ConfigPath = [IO.Path]::GetFullPath($ConfigPath)
$PlatformConfig = Get-Content -LiteralPath $ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
$ProfileConfig = $PlatformConfig.profiles.$Profile
function Resolve-ProjectPath {
    <# 相对路径以新项目根目录为基准，不受调用者当前工作目录影响。 #>
    param([string]$Path)
    if ([IO.Path]::IsPathRooted($Path)) { return [IO.Path]::GetFullPath($Path) }
    return [IO.Path]::GetFullPath((Join-Path $ProjectRoot $Path))
}
function Assert-ChildPath {
    <# 写入前核对规范化绝对路径，防止备份或状态路径越出指定目录。 #>
    param([string]$Path,[string]$Root)
    $absolute = [IO.Path]::GetFullPath($Path)
    $prefix = [IO.Path]::GetFullPath($Root).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    if (!$absolute.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)) { throw '目标路径不属于指定目录。' }
    return $absolute
}
$DataRoot = Resolve-ProjectPath $PlatformConfig.dataRoot
$LocalRoot = $DataRoot # 兼容行情覆盖/流水线脚本；既有数据仍保存在原位置。
$RuntimeRoot = Resolve-ProjectPath $ProfileConfig.runtimeRoot
$LogRoot = Join-Path $RuntimeRoot 'logs'
$LegacyRoot = Resolve-ProjectPath $PlatformConfig.legacyRoot
$ApiRoot = Join-Path $ProjectRoot 'apps\api'
$WebRoot = Join-Path $ProjectRoot 'apps\web'
$EngineRoot = Join-Path $ProjectRoot 'engines\probability'
$EngineRuntimeRoot = Join-Path $RuntimeRoot 'engine'
$JarPath = Join-Path $ApiRoot 'target\stock-0.0.1-SNAPSHOT.jar'
$PythonExe = Join-Path $EngineRoot '.venv\Scripts\python.exe'
$VitePath = Join-Path $WebRoot 'node_modules\vite\bin\vite.js'
$MysqlBase = Resolve-ProjectPath $PlatformConfig.mysqlBase
$MysqlExe = Join-Path $MysqlBase 'bin\mysql.exe'
$MysqldExe = Join-Path $MysqlBase 'bin\mysqld.exe'
$MysqlDumpExe = Join-Path $MysqlBase 'bin\mysqldump.exe'
$MysqlConfig = Join-Path $DataRoot 'mysql\my.ini'
$MysqlClientConfig = Join-Path $DataRoot 'mysql\client.ini'
$MysqlPidFile = Join-Path $DataRoot 'mysql\mysqld.pid'
$SecretsFile = Resolve-ProjectPath $ProfileConfig.applicationSecretsFile
$DbPort = [int]$PlatformConfig.databasePort
$Database = [string]$ProfileConfig.database
$ApiPort = [int]$ProfileConfig.apiPort
$WebPort = [int]$ProfileConfig.webPort
if ($Database -cnotmatch '^[a-z][a-z0-9_]*$') { throw '数据库名称只能包含小写英文字母、数字和下划线。' }
foreach ($port in @($DbPort,$ApiPort,$WebPort)) { if ($port -lt 1024 -or $port -gt 65535) { throw '端口必须在1024至65535。' } }
if (@(@($DbPort,$ApiPort,$WebPort) | Select-Object -Unique).Count -ne 3) { throw '数据库、后端和前端端口必须不同。' }
if ($Profile -eq 'verification' -and ($Database -eq $PlatformConfig.profiles.production.database -or $ApiPort -eq $PlatformConfig.profiles.production.apiPort -or $WebPort -eq $PlatformConfig.profiles.production.webPort)) {
    throw '验证配置必须使用独立数据库及独立前后端端口，禁止连接生产库启动第二后端。'
}
if ($Profile -eq 'verification' -and ($RuntimeRoot -ieq (Resolve-ProjectPath $PlatformConfig.profiles.production.runtimeRoot) -or $ApiPort -eq $PlatformConfig.profiles.production.webPort -or $WebPort -eq $PlatformConfig.profiles.production.apiPort)) { throw '验证运行目录和应用端口不能与生产配置交叉复用。' }
if ($RuntimeRoot -ieq $DataRoot -or $RuntimeRoot.StartsWith((Join-Path $DataRoot 'mysql'),[StringComparison]::OrdinalIgnoreCase)) { throw '应用运行目录不能覆盖数据库目录。' }
function Write-Utf8File {
    param([string]$Path,[string]$Content)
    [IO.File]::WriteAllText($Path,$Content,[Text.UTF8Encoding]::new($false))
}
function Read-PlatformJson {
    param([string]$Path)
    if (Test-Path -LiteralPath $Path) { return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json }
    return $null
}
function Save-PlatformJson {
    <# 同目录临时文件原子替换，避免读到半个JSON；读取端始终显式指定UTF-8。 #>
    param([string]$Path,[object]$Value)
    Write-Utf8File "$Path.tmp" ($Value | ConvertTo-Json -Depth 15)
    Move-Item -LiteralPath "$Path.tmp" -Destination $Path -Force
}
function Read-LocalSecrets {
    <# 仅内部传递，不输出凭据；验证环境必须使用独立凭据文件。 #>
    $value = Read-PlatformJson $SecretsFile
    if (!$value -or !$value.dbUser -or !$value.dbPassword) { throw '缺少所选配置的应用凭据文件。' }
    if ($Profile -eq 'verification' -and $SecretsFile -ieq (Resolve-ProjectPath $PlatformConfig.profiles.production.applicationSecretsFile)) { throw '验证环境不能使用生产凭据文件。' }
    if ($Profile -eq 'verification') {
        $productionSecrets = Read-PlatformJson (Resolve-ProjectPath $PlatformConfig.profiles.production.applicationSecretsFile)
        if ($value.dbUser -eq 'root' -or ($productionSecrets -and $value.dbUser -eq $productionSecrets.dbUser)) { throw '验证环境必须使用仅获验证库授权的独立应用账号。' }
    }
    return $value
}
function Test-LocalPort {
    param([int]$Port)
    $client = [Net.Sockets.TcpClient]::new()
    try { return $client.ConnectAsync('127.0.0.1',$Port).Wait(500) -and $client.Connected }
    catch { return $false } finally { $client.Dispose() }
}
function Wait-LocalPort {
    param([int]$Port,[int]$Seconds=60)
    $deadline = [datetime]::UtcNow.AddSeconds($Seconds)
    do { if (Test-LocalPort $Port) { return }; Start-Sleep -Milliseconds 500 } while ([datetime]::UtcNow -lt $deadline)
    throw "服务未按时监听127.0.0.1:$Port，请查看本配置日志。"
}
function Invoke-LocalSql {
    <# 管理凭据仅从原client.ini读取；显式指定端口/库名，防止验证查询误入生产库。 #>
    param([string]$Sql)
    & $MysqlExe "--defaults-file=$MysqlClientConfig" --host=127.0.0.1 "--port=$DbPort" "--database=$Database" --batch --raw --skip-column-names --execute=$Sql
    if ($LASTEXITCODE -ne 0) { throw "MySQL命令失败，退出码$LASTEXITCODE。" }
}
function Assert-DatabaseIdentity {
    <# 只读验证实际端口、数据目录和库名；不创建数据库、不改账号。 #>
    $fields = ([string](Invoke-LocalSql 'SELECT @@port,@@datadir,DATABASE();')).Trim() -split "`t"
    $expected = [IO.Path]::GetFullPath((Join-Path $DataRoot 'mysql\data')).TrimEnd('\','/')
    if ($fields.Count -ne 3 -or [int]$fields[0] -ne $DbPort -or [IO.Path]::GetFullPath($fields[1]).TrimEnd('\','/') -ine $expected -or $fields[2] -cne $Database) { throw '数据库端口、数据目录或库名不匹配，拒绝操作。' }
}
function Start-LocalDatabase {
    <# 复用已有实例，只允许用归属正确的原my.ini启动；不复制、初始化或重置数据。 #>
    if (!(Test-LocalPort $DbPort)) {
        $ini = Get-Content -LiteralPath $MysqlConfig -Encoding UTF8
        $dataLine = @($ini | Where-Object { $_ -match '^datadir=' })
        $portLine = @($ini | Where-Object { $_ -match '^port=' })
        if ($dataLine.Count -ne 1 -or $portLine.Count -ne 1 -or [IO.Path]::GetFullPath($dataLine[0].Substring(8)).TrimEnd('\','/') -ine [IO.Path]::GetFullPath((Join-Path $DataRoot 'mysql\data')).TrimEnd('\','/') -or [int]$portLine[0].Substring(5) -ne $DbPort) { throw '既有my.ini目录或端口不匹配，没有启动或重建数据库。' }
        Start-Process -FilePath $MysqldExe -ArgumentList "--defaults-file=`"$MysqlConfig`"" -WorkingDirectory $DataRoot -WindowStyle Hidden | Out-Null
        Wait-LocalPort $DbPort
    }
    Assert-DatabaseIdentity
}
function Invoke-PlatformGet {
    <# 只读状态接口按UTF-8解析，不访问会预热全量行情的overview。 #>
    param([string]$Path)
    $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$ApiPort$Path" -TimeoutSec 15
    $result = [Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray()) | ConvertFrom-Json
    if (!$result -or $result.code -ne 0) { throw '状态接口未返回成功结果，不能据此判定空闲。' }
    return $result
}
