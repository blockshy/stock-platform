<# 覆盖审计公共函数：只读数据库和写出本地证据，不创建任务、不补造行情或日历。 #>
function Export-CoverageAuditCsv {
    <# UTF-8 BOM 兼容 Windows PowerShell 5.1/Excel；空结果也保留固定列头。 #>
    param([object[]]$Rows,[string]$Path,[object]$Template)
    $lines = if ($Rows.Count -gt 0) { @($Rows | ConvertTo-Csv -NoTypeInformation) } else { @(($Template | ConvertTo-Csv -NoTypeInformation)[0]) }
    [IO.File]::WriteAllLines($Path,[string[]]$lines,[Text.UTF8Encoding]::new($true))
}

function Get-CoverageAuditCalendar {
    <#
    独立检查缓存日历的每个自然日。表存在但缺行/值非法必须失败，不能退回行情推导并掩盖共同漏日。
    只有老版本完全没有该表时才保留原始行情推导统计；该路径明确不具备严格完整性证明。
    #>
    param([string]$Database,[string]$HistoryStart,[string]$AsOf,[string]$EvidenceFile)
    if ($Database -cnotmatch '^[a-z][a-z0-9_]*$') { throw '审计数据库名称不合法。' }
    $first=[datetime]::ParseExact($HistoryStart,'yyyy-MM-dd',[Globalization.CultureInfo]::InvariantCulture)
    $last=[datetime]::ParseExact($AsOf,'yyyy-MM-dd',[Globalization.CultureInfo]::InvariantCulture)
    if ($first -gt $last) { throw 'HistoryStart 不能晚于 AsOf。' }
    $exists=@(Invoke-LocalSql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$Database' AND table_name='crawl_trade_calendar';")
    if ($exists.Count -ne 1 -or [string]$exists[0] -notin @('0','1')) { throw '无法确定独立交易日历是否存在。' }
    $template=[pscustomobject][ordered]@{date='';isTradingDay='';issue=''}
    if ([int]$exists[0] -eq 0) {
        $legacy=@(Invoke-LocalSql "SELECT COALESCE(CAST(MAX(trade_date) AS CHAR),''),COUNT(DISTINCT trade_date) FROM ${Database}.stock_daily_all WHERE adjustflag=3 AND trade_date BETWEEN '$HistoryStart' AND '$AsOf';")
        if ($legacy.Count -ne 1) { throw '旧版行情推导日历没有返回唯一汇总行。' }
        $fields=([string]$legacy[0]) -split "`t"
        if ($fields.Count -ne 2) { throw '旧版行情推导日历字段异常。' }
        $end=if ($fields[0]) { [datetime]::ParseExact($fields[0],'yyyy-MM-dd',[Globalization.CultureInfo]::InvariantCulture).ToString('yyyy-MM-dd') } else { '' }
        Export-CoverageAuditCsv @([pscustomobject][ordered]@{date='';isTradingDay='UNKNOWN';issue='旧版本没有独立日历；行情推导统计不能证明完整'}) $EvidenceFile $template
        return [pscustomobject]@{
            calendarSource='observed_raw_prices';strictCalendar=$false;lastTradingDate=$end;tradingDays=[int]$fields[1];calendarFile=$EvidenceFile
            calendarSql="SELECT DISTINCT trade_date FROM ${Database}.stock_daily_all WHERE adjustflag=3 AND trade_date BETWEEN '$HistoryStart' AND '$AsOf'"
        }
    }
    $rows=@(Invoke-LocalSql "SELECT CAST(calendar_date AS CHAR),is_trading_day FROM ${Database}.crawl_trade_calendar WHERE calendar_date BETWEEN '$HistoryStart' AND '$AsOf' ORDER BY calendar_date;")
    $known=@{};$evidence=New-Object 'System.Collections.Generic.List[object]';$issues=0;$tradingDays=0;$end=''
    foreach ($line in $rows) {
        $fields=([string]$line) -split "`t";$issue='';$dayText='';$value=''
        if ($fields.Count -ne 2) { $issue='日历字段数量异常' } else {
            $dayText=$fields[0];$value=$fields[1];$parsed=[datetime]::MinValue
            if (![datetime]::TryParseExact($dayText,'yyyy-MM-dd',[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::None,[ref]$parsed) -or $parsed -lt $first -or $parsed -gt $last) {
                $issue='日历日期无效或越界'
            } elseif ($known.ContainsKey($dayText)) { $issue='自然日重复' }
            elseif ($value -notin @('0','1')) { $issue='交易状态必须明确为0或1' }
            else { $known[$dayText]=[int]$value }
        }
        if ($issue) { $issues++ }
        $evidence.Add([pscustomobject][ordered]@{date=$dayText;isTradingDay=$value;issue=$issue})
    }
    for ($day=$first; $day -le $last; $day=$day.AddDays(1)) {
        $key=$day.ToString('yyyy-MM-dd')
        if (!$known.ContainsKey($key)) {
            $issues++
            $evidence.Add([pscustomobject][ordered]@{date=$key;isTradingDay='UNKNOWN';issue='缺少自然日；不能推断为休市'})
        } elseif ($known[$key] -eq 1) { $tradingDays++;$end=$key }
    }
    Export-CoverageAuditCsv @($evidence.ToArray()) $EvidenceFile $template
    if ($issues -gt 0) { throw "独立交易日历不完整或无效；已保存证据：$EvidenceFile" }
    return [pscustomobject]@{
        calendarSource='crawl_trade_calendar';strictCalendar=$true;lastTradingDate=$end;tradingDays=$tradingDays;calendarFile=$EvidenceFile
        calendarSql="SELECT calendar_date AS trade_date FROM ${Database}.crawl_trade_calendar WHERE is_trading_day=1 AND calendar_date BETWEEN '$HistoryStart' AND '$AsOf'"
    }
}

function Complete-CoverageAudit {
    <#
    先落盘最终结论，再中止调用者。证据完整与审计通过是两回事，严格验证失败不会向上返回成功对象。
    旧版日历仅提供兼容统计，strictValidated 永远为 false，不把行情共同漏日包装为已验收。
    #>
    param([object]$Summary,[string[]]$Violations,[string]$SummaryFile,[bool]$StrictCalendar)
    $Summary | Add-Member NoteProperty strictValidated ($StrictCalendar -and $Violations.Count -eq 0)
    $Summary | Add-Member NoteProperty validationIssues @($Violations)
    [IO.File]::WriteAllText($SummaryFile,($Summary | ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
    if ($StrictCalendar -and $Violations.Count -gt 0) {
        throw ('行情覆盖严格核验未通过：'+($Violations -join '；')+'。CSV及汇总证据已保存：'+$SummaryFile)
    }
    return $Summary
}

function Save-IncompleteCoverageAudit {
    <# 查询/日历检查提前失败也覆盖旧汇总，避免把上次成功文件误读成本次结果；不复制数据库错误原文。 #>
    param([string]$SummaryFile,[string]$HistoryStart,[string]$AsOf)
    $incomplete=[ordered]@{historyStart=$HistoryStart;asOf=$AsOf;calendarSource='unconfirmed';strictValidated=$false;
        validationIssues=@('本次审计未完成，不能进入模型；请核对日历CSV及命令错误。')}
    [IO.File]::WriteAllText($SummaryFile,($incomplete | ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
}
