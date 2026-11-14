# 一键启动演示环境（后端 8080 + Nginx 8081）
# 双击运行；已在运行时不会重复启动
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot        # D:\campus-secondhand-trade
$backend = Join-Path $root 'backend'
$jar = Join-Path $backend 'target\campus-trade-backend-0.0.3.jar'
$ngDir = 'D:\major\tool\nginx-1.27.5'
$logs = Join-Path $root 'deploy\windows\logs'

Write-Host ''
Write-Host '  ===== 校园二手交易平台 · 演示环境启动 =====' -ForegroundColor Cyan
Write-Host ''

# 0. 依赖检查
foreach ($pair in @(@('MySQL', 3306), @('Redis', 6379))) {
    $ok = [bool](Get-NetTCPConnection -LocalPort $pair[1] -State Listen -ErrorAction SilentlyContinue)
    if ($ok) { Write-Host ("  [OK]   " + $pair[0] + " 已运行 (" + $pair[1] + ")") -ForegroundColor Green }
    else { Write-Host ("  [!!]   " + $pair[0] + " 未运行 (" + $pair[1] + ") —— 请先在服务里启动它") -ForegroundColor Yellow }
}

if (-not (Test-Path $jar)) {
    Write-Host "  [XX]   找不到 jar：$jar" -ForegroundColor Red
    Write-Host "         请先执行：cd backend; mvnw -B `"-DskipTests`" `"-Djacoco.skip=true`" package"
    Read-Host '  按回车退出'
    exit 1
}

# 1. 后端
$health = $null
try { $health = Invoke-RestMethod 'http://127.0.0.1:8080/api/health' -TimeoutSec 3 } catch { }
if ($health -and $health.code -eq 200) {
    Write-Host '  [OK]   后端 8080 已在运行' -ForegroundColor Green
} else {
    Write-Host '  ...    启动后端 8080 ...'
    # 载入部署环境变量（JWT_SECRET / UPLOAD_PATH 等）
    $envFile = Join-Path $root 'deploy\windows\env.ps1'
    if (Test-Path $envFile) {
        Get-Content $envFile -Encoding UTF8 | ForEach-Object {
            if ($_ -match 'env:([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.+)$') {
                Set-Item -Path ('env:' + $Matches[1]) -Value $Matches[2].Trim().Trim("'").Trim('"')
            }
        }
    }
    $java = 'java'
    $jdk = 'D:\major\tool\openjdk-17.0.2_windows-x64_bin\jdk-17.0.2\bin\java.exe'
    if (Test-Path $jdk) { $java = $jdk; $env:JAVA_HOME = 'D:\major\tool\openjdk-17.0.2_windows-x64_bin\jdk-17.0.2' }
    New-Item -ItemType Directory -Force -Path $logs | Out-Null
    Start-Process -FilePath $java `
        -ArgumentList '-jar', 'target\campus-trade-backend-0.0.3.jar', '--spring.profiles.active=prod' `
        -WorkingDirectory $backend `
        -RedirectStandardOutput (Join-Path $logs 'backend.out.log') `
        -RedirectStandardError (Join-Path $logs 'backend.err.log') `
        -WindowStyle Hidden

    $health = $null
    for ($i = 0; $i -lt 25; $i++) {
        Start-Sleep -Seconds 3
        try { $health = Invoke-RestMethod 'http://127.0.0.1:8080/api/health' -TimeoutSec 4 } catch { }
        if ($health -and $health.code -eq 200) { break }
        Write-Host ("        等待启动 " + (($i + 1) * 3) + "s ...")
    }
    if ($health -and $health.code -eq 200) { Write-Host '  [OK]   后端已就绪' -ForegroundColor Green }
    else {
        Write-Host '  [XX]   后端启动失败，请查看 deploy\windows\logs\backend.err.log' -ForegroundColor Red
        Read-Host '  按回车退出'
        exit 1
    }
}

# 2. Nginx
if (Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue) {
    Write-Host '  [OK]   Nginx 8081 已在运行' -ForegroundColor Green
} else {
    Write-Host '  ...    启动 Nginx 8081 ...'
    $exe = Join-Path $ngDir 'nginx.exe'
    if (-not (Test-Path $exe)) {
        Write-Host "  [XX]   找不到 $exe" -ForegroundColor Red
    } else {
        Start-Process -FilePath $exe -WorkingDirectory $ngDir -WindowStyle Hidden
        for ($i = 0; $i -lt 10; $i++) {
            Start-Sleep -Seconds 1
            if (Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue) { break }
        }
        if (Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue) {
            Write-Host '  [OK]   Nginx 已就绪' -ForegroundColor Green
        } else { Write-Host '  [!!]   Nginx 未监听 8081，检查配置：nginx -t -p ...' -ForegroundColor Yellow }
    }
}

# 3. 冒烟
Write-Host ''
try {
    $r = Invoke-WebRequest 'http://127.0.0.1:8081' -TimeoutSec 8 -UseBasicParsing
    $l = Invoke-RestMethod 'http://127.0.0.1:8081/api/product/list?page=1&size=50' -TimeoutSec 8
    Write-Host ("  [OK]   首页 HTTP " + $r.StatusCode + "　在售商品 " + $l.data.records.Count + " 件") -ForegroundColor Green
} catch {
    Write-Host '  [!!]   冒烟失败：' -ForegroundColor Yellow
    Write-Host ('         ' + $_.Exception.Message)
}

Write-Host ''
Write-Host '  ===== 就绪 =====' -ForegroundColor Cyan
Write-Host '  前台： http://127.0.0.1:8081'
Write-Host '  后端： http://127.0.0.1:8080/api/health'
Write-Host '  账号： admin/123456　stu_demo/123456　stu_test01/abc12345'
Write-Host ''
Write-Host '  接着双击 打开校园演示（无痕）.cmd 即可上台。'
Write-Host ''
Read-Host '  按回车关闭本窗口'
