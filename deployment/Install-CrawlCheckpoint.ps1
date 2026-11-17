<#
.SYNOPSIS
显式安装采集断点表，默认只操作隔离验证库。
.DESCRIPTION
仅新增四张断点/覆盖/日历表，不重写行情、模型和旧任务。生产安装必须显式选择 production，
并通过既有活动任务/流水线检查；本脚本不停止应用，也不自动把旧任务标成可恢复。
#>
param([ValidateSet('production','verification')][string]$Profile='verification',[string]$ConfigPath='')
. (Join-Path $PSScriptRoot 'Common.ps1') -Profile $Profile -ConfigPath $ConfigPath
. (Join-Path $PSScriptRoot 'Platform.Operations.ps1')
Assert-DatabaseIdentity
if ($Profile -eq 'production') {
    $backendRecord = Read-PlatformJson (Join-Path $RuntimeRoot 'backend-process.json')
    Assert-NoActiveTasks $backendRecord
}
$schemaPath = (Join-Path $ApiRoot 'src\main\resources\crawl_checkpoint_schema.sql').Replace('\','/')
Invoke-LocalSql "SOURCE $schemaPath;"
if ($LASTEXITCODE -ne 0) { throw '采集断点表安装失败，未启动采集服务。' }
$count = [int](Invoke-LocalSql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('crawl_job_checkpoint','crawl_job_attempt','crawl_coverage','crawl_trade_calendar') AND engine='InnoDB';")
if ($count -ne 4) { throw '断点表或事务引擎不完整，不能启用可续采服务。' }
Write-Host "已核验四张采集断点事务表（$Profile），尚未切换生产数据源。"
