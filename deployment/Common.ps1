# 公共入口保持旧脚本名称，实际实现统一放在 Platform.Common.ps1。
param([ValidateSet('production','verification')][string]$Profile='production',[string]$ConfigPath='')
. (Join-Path $PSScriptRoot 'Platform.Common.ps1') -Profile $Profile -ConfigPath $ConfigPath