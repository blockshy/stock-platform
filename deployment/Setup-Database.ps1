<# 整合部署复用既有独立MySQL。本入口只核验，不重新初始化或重置任何账号。 #>
param([ValidateSet('production','verification')][string]$Profile='production',[string]$ConfigPath='')
. (Join-Path $PSScriptRoot 'Common.ps1') -Profile $Profile -ConfigPath $ConfigPath
Assert-DatabaseIdentity
Write-Host "既有实例归属核验通过：127.0.0.1:$DbPort/$Database。未初始化、复制数据或更改账号。"