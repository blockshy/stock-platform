<# 显式初始化所选库的幂等表结构；默认验证库，绝不复制生产数据文件。 #>
param([ValidateSet('production','verification')][string]$Profile='verification',[string]$ConfigPath='')
. (Join-Path $PSScriptRoot 'Common.ps1') -Profile $Profile -ConfigPath $ConfigPath
Assert-DatabaseIdentity
$files = @((Join-Path $PSScriptRoot 'stock-info.sql'))
foreach ($name in @('score_schema.sql','t_lab_schema.sql','ga_schema.sql','quant_schema.sql','prob_schema.sql')) {
    $files += Join-Path $ApiRoot ('src\main\resources\'+$name)
}
foreach ($file in $files) {
    $sqlPath = $file.Replace('\','/')
    Invoke-LocalSql "SOURCE $sqlPath;"
    Write-Host "已核验/初始化 $Database：$([IO.Path]::GetFileName($file))"
}