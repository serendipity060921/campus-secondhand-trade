# =============================================================================
# 校园二手交易平台 · Windows 原生部署停止脚本（v0.15）
#
# 用法：powershell -ExecutionPolicy Bypass -File deploy\windows\stop.ps1
# =============================================================================

param(
    [string]$NginxHome = 'D:\major\tool\nginx-1.27.5'
)

[Console]::OutputEncoding = [Text.Encoding]::UTF8
$DeployDir = $PSScriptRoot
$LogDir    = Join-Path $DeployDir 'logs'
$PidFile   = Join-Path $LogDir 'backend.pid'

Write-Host "`n[停止] Nginx" -ForegroundColor Cyan
$nginxExe = Join-Path $NginxHome 'nginx.exe'
if (Test-Path $nginxExe) {
    & $nginxExe -p $NginxHome -s quit 2>$null
    Start-Sleep -Seconds 1
    $left = Get-Process -Name 'nginx' -ErrorAction SilentlyContinue
    if ($left) { $left | Stop-Process -Force -ErrorAction SilentlyContinue; Write-Host '  √ 已强制结束 nginx 进程' -ForegroundColor Green }
    else { Write-Host '  √ Nginx 已退出' -ForegroundColor Green }
} else {
    Write-Host "  ! 未找到 $nginxExe（可能未部署 Nginx）" -ForegroundColor Yellow
}

Write-Host "`n[停止] 后端" -ForegroundColor Cyan
$stopped = $false
if (Test-Path $PidFile) {
    $pid0 = (Get-Content $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    if ($pid0) {
        $proc = Get-Process -Id ([int]$pid0) -ErrorAction SilentlyContinue
        if ($proc) {
            Stop-Process -Id $proc.Id -Force
            Write-Host "  √ 已停止后端进程（PID $($proc.Id)）" -ForegroundColor Green
            $stopped = $true
        }
    }
    Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
}
if (-not $stopped) {
    # 兜底：按命令行匹配（只结束本项目的 jar，避免误杀其它 java 进程）
    $targets = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
        Where-Object { $_.CommandLine -like '*campus-trade-backend*' }
    if ($targets) {
        $targets | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        Write-Host "  √ 已按命令行匹配停止 $($targets.Count) 个后端进程" -ForegroundColor Green
    } else {
        Write-Host '  ! 未发现运行中的后端进程' -ForegroundColor Yellow
    }
}

Write-Host "`n  MySQL / Redis 作为 Windows 服务仍在运行（如需停止：net stop MySQL84 / net stop RedisCampus）`n" -ForegroundColor DarkGray
