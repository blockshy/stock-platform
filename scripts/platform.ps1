<# 从任意当前目录调用统一Windows入口；不依赖子目录的Git元数据。 #>
param(
    [ValidateSet('Check','Build','Start','Status','Stop','Backup','Adopt','Switch','Rollback')][string]$Action='Status',
    [ValidateSet('production','verification')][string]$Profile='production',
    [string]$ConfigPath='', [switch]$FrontendOnly, [switch]$SkipTests
)
& (Join-Path $PSScriptRoot '..\deployment\Invoke-Platform.ps1') @PSBoundParameters
