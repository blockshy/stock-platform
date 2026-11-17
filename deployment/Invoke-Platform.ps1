<# 统一命令入口；所有写操作均由明确Action触发，默认仅查看状态。 #>
param(
    [ValidateSet('Check','Build','Start','Status','Stop','Backup','Adopt','Switch','Rollback')][string]$Action='Status',
    [ValidateSet('production','verification')][string]$Profile='production',
    [string]$ConfigPath='', [switch]$FrontendOnly, [switch]$SkipTests
)
. (Join-Path $PSScriptRoot 'Common.ps1') -Profile $Profile -ConfigPath $ConfigPath
. (Join-Path $PSScriptRoot 'Platform.Operations.ps1')
switch ($Action) {
    'Check' { Test-PlatformEnvironment }
    'Build' { Build-Platform -SkipTests:$SkipTests }
    'Start' { Start-Platform -FrontendOnly:$FrontendOnly }
    'Status' { Get-PlatformStatus | ConvertTo-Json -Depth 12 }
    'Stop' { Stop-Platform -FrontendOnly:$FrontendOnly }
    'Backup' { Backup-Platform }
    'Adopt' { Adopt-ExistingBackend }
    'Switch' { Switch-Platform -FrontendOnly:$FrontendOnly }
    'Rollback' { Rollback-Platform }
}
