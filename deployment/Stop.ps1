<# 兼容直接调用的薄入口；实现集中在统一平台命令中。 #>
param([ValidateSet('production','verification')][string]$Profile='production',[string]$ConfigPath='',[switch]$FrontendOnly,[switch]$SkipTests)
& (Join-Path $PSScriptRoot 'Invoke-Platform.ps1') -Action 'Stop' @PSBoundParameters