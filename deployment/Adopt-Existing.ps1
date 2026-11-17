<# 认证接管旧后端管理记录，不重启进程；默认生产配置。 #>
param([string]$ConfigPath='')
& (Join-Path $PSScriptRoot 'Invoke-Platform.ps1') -Action Adopt -Profile production -ConfigPath $ConfigPath
