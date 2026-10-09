# =============================================================================
# 校园二手交易平台 · Windows 原生部署一键启动（v0.15）
#
# 用法（普通用户权限即可，无需管理员）：
#   powershell -ExecutionPolicy Bypass -File deploy\windows\start.ps1
#
# 它做五件事：
#   ① 检查 MySQL / Redis 是否已启动（未启动给出提示）
#   ② 首次运行自动生成 JWT 密钥并写入 env.ps1（密钥不进代码仓库）
#   ③ 用 prod 配置启动后端 jar（环境变量注入，日志写文件）
#   ④ 等待后端健康检查通过
#   ⑤ 启动 Nginx 反向代理（80 端口 → 前端 dist + /api + /ws）
# =============================================================================

param(
    [string]$NginxHome = 'D:\major\tool\nginx-1.27.5',
    [switch]$SkipNginx
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

# 目录定位兜底：$PSScriptRoot 在个别调用方式下可能为空（例如被其他宿主加载、
# 或以 -Command 方式间接调用），此时脚本会在 Join-Path 处直接崩掉。
# 这里退回到 $MyInvocation.MyCommand.Path，保证任何调用方式都能正确定位自身目录。
$DeployDir   = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
$Root        = Split-Path -Parent (Split-Path -Parent $DeployDir)   # 项目根目录
$BackendDir  = Join-Path $Root 'backend'
$DbDocDir    = Join-Path $Root 'docs'
$LogDir      = Join-Path $DeployDir 'logs'
$UploadDir   = Join-Path $DeployDir 'uploads'
$JarPath     = Join-Path $BackendDir 'target\campus-trade-backend-0.0.3.jar'
$EnvFile     = Join-Path $DeployDir 'env.ps1'
$PidFile     = Join-Path $LogDir 'backend.pid'

function Write-Step($msg) { Write-Host "`n[$(Get-Date -Format 'HH:mm:ss')] $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "  √ $msg" -ForegroundColor Green }
function Write-Warn2($msg){ Write-Host "  ! $msg" -ForegroundColor Yellow }

function Test-Port([string]$TargetHost, [int]$Port, [int]$TimeoutMs = 1500) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($TargetHost, $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne($TimeoutMs)) { return $false }
        $client.EndConnect($async)
        return $true
    } catch { return $false } finally { $client.Close() }
}

# ---------------------------------------------------------------- ① 依赖检查
Write-Step '① 检查依赖服务（MySQL 3306 / Redis 6379）'

if (Test-Port '127.0.0.1' 3306) {
    Write-Ok 'MySQL 已就绪（3306）'
} else {
    Write-Warn2 'MySQL 未启动。请启动服务：net start MySQL84（或你机器上的 MySQL 服务名）'
    $svc = Get-Service -ErrorAction SilentlyContinue | Where-Object { $_.Name -match 'mysql' } | Select-Object -First 1
    if ($svc) { Write-Warn2 "检测到服务：$($svc.Name)（当前状态 $($svc.Status)）" }
}

if (Test-Port '127.0.0.1' 6379) {
    Write-Ok 'Redis 已就绪（6379）'
} else {
    Write-Warn2 'Redis 未启动。请启动服务：net start RedisCampus'
}

# ---------------------------------------------------------------- ② 环境变量
Write-Step '② 准备环境变量（env.ps1）'

if (-not (Test-Path $EnvFile)) {
    $secret = -join ((1..48) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) })
    @"
# =============================================================================
# 本机部署环境变量（v0.15）—— 由 start.ps1 首次运行时自动生成
# ⚠️ 本文件包含密钥，已被 .gitignore 忽略，不要提交到代码仓库
# =============================================================================
# 数据库：本机演示用 root；生产请用最小权限账号（见 docs/部署说明.md）
`$env:MYSQL_HOST = '127.0.0.1'
`$env:MYSQL_PORT = '3306'
`$env:MYSQL_DB   = 'campus_trade'
`$env:MYSQL_USER = 'root'
`$env:MYSQL_PASSWORD = '123456'

# Redis（本机无密码）
`$env:REDIS_HOST = '127.0.0.1'
`$env:REDIS_PORT = '6379'
`$env:REDIS_PASSWORD = ''

# JWT 密钥（≥32 字节，首次运行自动生成）
`$env:JWT_SECRET = '$secret'

# 对外来源与业务开关
# ⚠️ CORS/WebSocket 来源必须包含实际访问地址（含端口），否则浏览器发起的 WebSocket 握手会被拒绝
`$env:CORS_ORIGINS = 'http://localhost,http://localhost:8081,http://127.0.0.1:8081'
`$env:AUDIT_ENABLED = 'false'
`$env:CACHE_ENABLED = 'true'
`$env:HEALTH_DETAIL_ENABLED = 'false'

# 上传目录与日志目录（本机路径）
`$env:UPLOAD_PATH = '$UploadDir'
`$env:LOG_PATH = '$LogDir'
"@ | Set-Content -Path $EnvFile -Encoding UTF8
    Write-Ok "已生成 $EnvFile（含随机 JWT 密钥）"
} else {
    Write-Ok "使用已有 $EnvFile"
}
. $EnvFile

New-Item -ItemType Directory -Path $LogDir, $UploadDir -Force | Out-Null

# ---------------------------------------------------------------- ③ 启动后端
Write-Step '③ 启动后端（prod 配置 + jar）'

if (-not (Test-Path $JarPath)) {
    Write-Warn2 "未找到 jar：$JarPath"
    Write-Host '  正在打包：mvnw.cmd -DskipTests package' -ForegroundColor Yellow
    Push-Location $BackendDir
    cmd.exe /c 'mvnw.cmd -B -DskipTests package' | Select-Object -Last 5
    Pop-Location
}

# jar 完整性检查：Spring Boot 可执行 jar 内嵌全部依赖，正常约 41 MB。
# 若在后端运行中执行 repackage（Windows 会锁住 jar），会留下几百 KB 的"瘦包"，
# 表现为进程启动后立刻退出、健康检查一直失败 —— 这里提前拦掉并给出明确提示。
if (Test-Path $JarPath) {
    $jarMB = [math]::Round((Get-Item $JarPath).Length / 1MB, 1)
    if ($jarMB -lt 10) {
        Write-Warn2 "jar 大小异常（$jarMB MB，正常约 41 MB）：可能是在后端运行中打包导致的残次品。"
        Write-Warn2 "请先执行 stop.ps1 停止后端，再执行：cd backend; mvnw.cmd -DskipTests clean package"
        exit 1
    }
    Write-Ok "jar 校验通过（$jarMB MB）"
}

if (Test-Port '127.0.0.1' 8080) {
    Write-Warn2 '8080 已被占用（可能是开发态后端），先停止它再运行本脚本'
} else {
    $backendLog = Join-Path $LogDir 'backend-console.log'
    $proc = Start-Process -FilePath 'java' `
        -ArgumentList @('-jar', $JarPath, '--spring.profiles.active=prod') `
        -RedirectStandardOutput $backendLog `
        -RedirectStandardError (Join-Path $LogDir 'backend-error.log') `
        -PassThru -WindowStyle Hidden
    $proc.Id | Set-Content $PidFile
    Write-Ok "后端已启动（PID $($proc.Id)），日志：$backendLog"

    Write-Host '  等待健康检查通过…' -NoNewline
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 2
        try {
            $r = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/health' -TimeoutSec 3
            if ($r.code -eq 200) { $ready = $true; break }
        } catch { }
        Write-Host '.' -NoNewline
    }
    Write-Host ''
    if ($ready) { Write-Ok "后端就绪（profile=$($r.data.profile)，版本 $($r.data.version)）" }
    else { Write-Warn2 "后端 120 秒内未就绪，请查看 $backendLog" }
}

# ---------------------------------------------------------------- ④ 启动 Nginx
$WebPort = 8081     # 本机 80 端口被 Steam++ 占用，改用 8081；生产环境改回 80
if (-not $SkipNginx) {
    Write-Step "④ 启动 Nginx（$WebPort 端口）"
    $nginxExe = Join-Path $NginxHome 'nginx.exe'
    if (-not (Test-Path $nginxExe)) {
        Write-Warn2 "未找到 $nginxExe，请用 -NginxHome 指定 nginx 解压目录，或加 -SkipNginx 只起后端"
    } else {
        if (Test-Port '127.0.0.1' $WebPort) {
            Write-Warn2 "$WebPort 端口已在监听，执行 reload"
            & $nginxExe -p $NginxHome -s reload
        } else {
            # 注意：必须重定向 stdout/stderr。
            # 否则 nginx 会继承调用方（终端/CI/脚本宿主）的标准输出句柄，
            # 表现为"脚本明明已经跑完，调用方却一直等不到管道结束"——
            # 之前就踩过这个坑：在外部工具里执行本脚本会一直挂着不返回。
            # 这里用 splatting 传参，避免反引号续行受"行尾空格"影响而语法出错。
            $nginxStartArgs = @{
                FilePath               = $nginxExe
                ArgumentList           = @('-p', $NginxHome)
                RedirectStandardOutput = (Join-Path $LogDir 'nginx-console.log')
                RedirectStandardError  = (Join-Path $LogDir 'nginx-error.log')
                WindowStyle            = 'Hidden'
            }
            Start-Process @nginxStartArgs
            Start-Sleep -Seconds 2
        }
        if (Test-Port '127.0.0.1' $WebPort) { Write-Ok "Nginx 已就绪：http://localhost:$WebPort" }
        else { Write-Warn2 "Nginx 启动失败，请查看 $NginxHome\logs\campus-error.log" }
    }
}

# ---------------------------------------------------------------- ⑤ 汇总
Write-Step '⑤ 部署完成，访问入口'
Write-Host "  前端（经 Nginx）  http://localhost:$WebPort" -ForegroundColor Green
Write-Host "  后端存活探针      http://localhost:$WebPort/health" -ForegroundColor Green
Write-Host "  实时私信          ws://localhost:$WebPort/ws/chat?token=<JWT>" -ForegroundColor Green
Write-Host '  演示账号          admin/123456、stu_test01/abc12345、stu_demo/123456' -ForegroundColor Green
Write-Host "`n  停止服务：powershell -ExecutionPolicy Bypass -File deploy\windows\stop.ps1" -ForegroundColor Yellow
