<#
.SYNOPSIS
    生产数据库备份脚本（v0.18）：mysqldump 全库备份 + 保留策略 + 恢复演练。

.DESCRIPTION
    为什么单独写这个脚本：项目里原本只有"开发时的安全网"——
    tools/seed-demo-data.py 在改动演示数据前会顺手 mysqldump 一份。
    那是给开发用的，没有保留策略、没有恢复验证，也不适合挂计划任务。
    生产环境需要的是：定期自动备份 + 自动清理旧文件 + **确认备份真的能恢复**。

    本脚本做四件事：
      ① 用 mysqldump 一致性导出全库（--single-transaction，不锁表）
      ② 校验导出文件存在且体积合理（防止"生成了一个空文件"这种假成功）
      ③ 按保留策略清理：普通备份保留 N 天；每月另存一份长期备份保留 M 个月
      ④ -Verify 时执行"恢复演练"：把备份导入临时库、比对关键表行数、再删掉临时库
         —— 备份的价值只有在能恢复时才成立，这一步是它的证明

    ★ 两个刻意的安全设计（都是踩过坑才加的）：
      1) 导出**不使用** --databases，因此 dump 里不含 CREATE DATABASE / USE 语句。
         否则恢复演练时会把临时库的导入直接打到生产库上（这是很危险的写法）。
         脚本还会在导入前扫描 dump 内容，发现 CREATE DATABASE / USE 就拒绝执行。
      2) 导出与导入都用 cmd 重定向，而不是 PowerShell 管道 ——
         PowerShell 的 Out-File 会写入 UTF-8 BOM，破坏 SQL 文件首行。

.PARAMETER Verify
    执行恢复演练（会创建并删除一个临时数据库 campus_trade_restore_test_<时间戳>）。

.PARAMETER DryRun
    只打印将要执行的动作，不真正备份与删除。

.EXAMPLE
    # 日常备份（挂计划任务用这一条）
    powershell -File tools\backup-db.ps1

.EXAMPLE
    # 备份并做恢复演练（建议每周跑一次）
    powershell -File tools\backup-db.ps1 -Verify

.EXAMPLE
    # 只看会做什么，不动数据
    powershell -File tools\backup-db.ps1 -DryRun
#>
[CmdletBinding()]
param(
    [string]$MysqlHome  = 'D:\major\tool\mysql-8.4.4-winx64\bin',
    [string]$DbName     = 'campus_trade',
    [string]$DbHost     = '127.0.0.1',
    [int]   $DbPort     = 3306,
    [string]$DbUser     = 'root',
    [string]$DbPassword = '123456',
    [string]$OutDir     = '',
    [int]   $KeepDays   = 14,
    [int]   $KeepMonths = 12,
    [int]   $MinSizeKB  = 10,
    [switch]$Verify,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$mysqldump = Join-Path $MysqlHome 'mysqldump.exe'
$mysql     = Join-Path $MysqlHome 'mysql.exe'
$stamp     = Get-Date
# 注意：$PSScriptRoot 在 param() 的默认值里可能取不到（取决于调用方式，实测为空），
#      因此默认值留空、在这里再解析 —— 这是踩过的坑之一。
if ([string]::IsNullOrWhiteSpace($OutDir)) { $OutDir = Join-Path $PSScriptRoot '..\db\backups' }
$OutDir    = [System.IO.Path]::GetFullPath($OutDir)
$keyTables = @('user', 'product', 'category', 'favorite', 'orders', 'message', 'moderation_log')


function Write-Log([string]$Message, [string]$Level = 'INFO') {
    $line = "[{0}] [{1}] {2}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level, $Message
    Write-Host $line
    $logDir = Join-Path $PSScriptRoot '..\deploy\windows\logs'
    if (Test-Path $logDir) {
        Add-Content -Path (Join-Path $logDir 'backup-db.log') -Value $line -Encoding UTF8
    }
}

function Invoke-Mysql([string]$Sql, [string]$Database) {
    # 注意：mysql 会把 "[Warning] Using a password..." 写到 stderr，而本脚本设了
    #      $ErrorActionPreference='Stop' —— PS 5.1 下原生命令的 stderr 会被当成
    #      错误直接中断脚本。所以这里临时放宽，并把 stderr 合并后过滤掉该警告。
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out  = & $mysql -h $DbHost -P $DbPort -u $DbUser "-p$DbPassword" `
                        --default-character-set=utf8mb4 -N -B $Database -e $Sql 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
    if ($code -ne 0) { throw "mysql 执行失败（退出码 $code）：$($out -join ' ')" }
    return @($out | Where-Object { "$_" -notmatch 'Using a password' })
}

function Get-TableCounts([string]$Database) {
    $counts = @{}
    foreach ($t in $keyTables) {
        $v = Invoke-Mysql "SELECT COUNT(*) FROM ``$t``" $Database
        $counts[$t] = [int]($v | Select-Object -First 1)
    }
    return $counts
}


# ---------------------------------------------------------------- 前置检查
Write-Log "=== 数据库备份开始（库：$DbName，输出：$OutDir）==="

foreach ($exe in @($mysqldump, $mysql)) {
    if (-not (Test-Path $exe)) { throw "找不到可执行文件：$exe（可用 -MysqlHome 指定 MySQL bin 目录）" }
}
if (-not (Test-Path $OutDir)) {
    if ($DryRun) { Write-Log "[DryRun] 将创建目录 $OutDir" }
    else { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
}

$beforeCounts = Get-TableCounts $DbName
Write-Log ("备份前关键表行数：" + (($keyTables | ForEach-Object { "$_=$($beforeCounts[$_])" }) -join ', '))


# ---------------------------------------------------------------- ① 导出
$fileName = "campus_trade_{0:yyyyMMdd-HHmm}.sql" -f $stamp
$filePath = Join-Path $OutDir $fileName

if ($DryRun) {
    Write-Log "[DryRun] 将执行 mysqldump 导出到 $fileName"
} else {
    Write-Log "正在导出（--single-transaction，不锁表；不含 CREATE DATABASE / USE）..."
    # 注意：库名作为参数传入而不是用 --databases，这样 dump 里不会出现
    #      CREATE DATABASE / USE 语句 —— 恢复时可以导入到任意库，避免误打生产库
    $rc = cmd.exe /c "$mysqldump -h $DbHost -P $DbPort -u $DbUser -p$DbPassword --single-transaction --routines --triggers --events --default-character-set=utf8mb4 $DbName > $filePath 2>nul"
    if ($LASTEXITCODE -ne 0) { throw "mysqldump 失败（退出码 $LASTEXITCODE）：$($rc -join ' ')" }

    # ② 校验文件
    $sizeKB = [math]::Round((Get-Item $filePath).Length / 1KB, 1)
    if ($sizeKB -lt $MinSizeKB) {
        throw "备份文件过小（$sizeKB KB < $MinSizeKB KB），疑似导出失败：$filePath"
    }
    # ★ 安全断言：dump 里绝不能出现这两类语句
    $head = Get-Content $filePath -TotalCount 60 -Encoding UTF8
    if ($head -match 'CREATE DATABASE' -or $head -match '^USE ') {
        throw "备份文件中包含 CREATE DATABASE / USE 语句，恢复时可能误写生产库，已中止：$filePath"
    }
    Write-Log "导出完成：$fileName（$sizeKB KB），已确认不含 CREATE DATABASE / USE"
}


# ---------------------------------------------------------------- ③ 月度长期备份
$monthlyName = "campus_trade_{0:yyyyMM}-monthly.sql" -f $stamp
$monthlyPath = Join-Path $OutDir $monthlyName
if (-not (Test-Path $monthlyPath)) {
    if ($DryRun) { Write-Log "[DryRun] 本月尚无长期备份，将额外保存一份 $monthlyName" }
    else {
        Copy-Item $filePath $monthlyPath
        Write-Log "已保存本月长期备份：$monthlyName"
    }
}


# ---------------------------------------------------------------- ④ 恢复演练
$verifyOk = $null
if ($Verify) {
    $tempDb = "campus_trade_restore_test_{0:HHmmss}" -f $stamp
    if ($DryRun) {
        Write-Log "[DryRun] 将恢复演练：导入到临时库 $tempDb 并比对行数"
    } else {
        Write-Log "恢复演练开始：导入到临时库 $tempDb ..."
        Invoke-Mysql "DROP DATABASE IF EXISTS ``$tempDb``" 'mysql' | Out-Null
        Invoke-Mysql "CREATE DATABASE ``$tempDb`` DEFAULT CHARACTER SET utf8mb4" 'mysql' | Out-Null

        $rc = cmd.exe /c "$mysql -h $DbHost -P $DbPort -u $DbUser -p$DbPassword --default-character-set=utf8mb4 $tempDb < $filePath 2>nul"
        if ($LASTEXITCODE -ne 0) { throw "恢复导入失败（退出码 $LASTEXITCODE）：$($rc -join ' ')" }

        $afterCounts = Get-TableCounts $tempDb
        $mismatch = @()
        foreach ($t in $keyTables) {
            if ($beforeCounts[$t] -ne $afterCounts[$t]) {
                $mismatch += "$t($($beforeCounts[$t])→$($afterCounts[$t]))"
            }
        }
        if ($mismatch.Count -gt 0) {
            $verifyOk = $false
            Write-Log ("恢复演练失败：行数不一致 " + ($mismatch -join ' ')) 'ERROR'
        } else {
            $verifyOk = $true
            Write-Log ("恢复演练通过：$($keyTables.Count) 张关键表行数与源库一致（" +
                       (($keyTables | ForEach-Object { "$_=$($afterCounts[$_])" }) -join ', ') + "）")
        }
        Invoke-Mysql "DROP DATABASE ``$tempDb``" 'mysql' | Out-Null
        Write-Log "临时库已清理：$tempDb"
    }
}


# ---------------------------------------------------------------- ⑤ 保留策略
$cutoffDaily   = $stamp.AddDays(-$KeepDays)
$cutoffMonthly = $stamp.AddMonths(-$KeepMonths)
$removed = @()

foreach ($f in @(Get-ChildItem -Path $OutDir -Filter 'campus_trade_*-monthly.sql' -ErrorAction SilentlyContinue |
                 Where-Object { $_.LastWriteTime -lt $cutoffMonthly })) {
    $removed += $f.Name
    if (-not $DryRun) { Remove-Item $f.FullName -Force }
}

foreach ($f in @(Get-ChildItem -Path $OutDir -Filter 'campus_trade_*.sql' -ErrorAction SilentlyContinue |
                 Where-Object { $_.Name -notlike '*-monthly.sql' -and $_.LastWriteTime -lt $cutoffDaily })) {
    $removed += $f.Name
    if (-not $DryRun) { Remove-Item $f.FullName -Force }
}

if ($removed.Count -gt 0) {
    Write-Log "按保留策略清理 $($removed.Count) 个过期备份（普通保留 $KeepDays 天 / 月度保留 $KeepMonths 个月）"
} else {
    Write-Log "无需清理（普通保留 $KeepDays 天 / 月度保留 $KeepMonths 个月）"
}


# ---------------------------------------------------------------- 汇总
$all = @(Get-ChildItem -Path $OutDir -Filter '*.sql' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)
$sumMB = 0
if ($all.Count -gt 0) { $sumMB = [math]::Round((($all | Measure-Object Length -Sum).Sum / 1MB), 1) }
Write-Log "当前备份文件共 $($all.Count) 个，合计 $sumMB MB"
$all | Select-Object -First 5 | ForEach-Object {
    Write-Log ("  {0}  {1} KB  {2}" -f $_.LastWriteTime.ToString('MM-dd HH:mm'), [math]::Round($_.Length / 1KB, 1), $_.Name)
}

if ($Verify -and $verifyOk -eq $false) {
    Write-Log "备份完成，但恢复演练失败，请检查！" 'ERROR'
    exit 2
}
Write-Log "=== 备份结束（成功）==="
exit 0
