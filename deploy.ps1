# ============================================================
# PaiSmart Windows 一键部署脚本（PowerShell 版）
#
# 用法（PowerShell 里）：
#   .\deploy.ps1            # 首次部署（交互式）
#   .\deploy.ps1 up         # 构建 + 启动
#   .\deploy.ps1 down       # 停止容器（保留数据）
#   .\deploy.ps1 restart    # 重启
#   .\deploy.ps1 rebuild    # 重建后端/前端镜像
#   .\deploy.ps1 logs       # 看全部日志
#   .\deploy.ps1 logs backend
#   .\deploy.ps1 ps         # 容器状态
#   .\deploy.ps1 status     # 访问地址
#   .\deploy.ps1 clean      # 清空数据卷（危险）
#
# 首次运行如果被 PowerShell 拦截，在管理员 PowerShell 执行一次：
#   Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
# ============================================================

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Command = "up",

    [Parameter(Position = 1)]
    [string]$SubArg = ""
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

$ComposeFile = "docker-compose.prod.yml"
$EnvFile     = ".env"
$EnvTemplate = ".env.deploy.example"

function Log    { param($msg) Write-Host "[$(Get-Date -Format HH:mm:ss)] $msg" -ForegroundColor Cyan }
function Ok     { param($msg) Write-Host "[ OK ] $msg" -ForegroundColor Green }
function Warn   { param($msg) Write-Host "[WARN] $msg" -ForegroundColor Yellow }
function Fail   { param($msg) Write-Host "[FAIL] $msg" -ForegroundColor Red; exit 1 }

function Check-Prereq {
    Log "检查 Docker..."
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Fail "未检测到 docker。请先安装 Docker Desktop：https://www.docker.com/products/docker-desktop/"
    }

    # 测试 daemon
    try { docker info *>$null } catch { Fail "Docker daemon 未运行，请先启动 Docker Desktop。" }

    # 测试 compose
    $null = docker compose version 2>$null
    if ($LASTEXITCODE -ne 0) {
        Fail "docker compose 插件不可用。请升级 Docker Desktop 到 4.x 以上。"
    }
    Ok "Docker 可用"
}

function Get-EnvValue {
    param([string]$Key)
    if (-not (Test-Path $EnvFile)) { return "" }
    $line = Select-String -Path $EnvFile -Pattern "^${Key}=" -SimpleMatch:$false | Select-Object -First 1
    if (-not $line) { return "" }
    return ($line.Line -replace "^${Key}=", "")
}

function Set-EnvValue {
    param([string]$Key, [string]$Value)
    $content = Get-Content $EnvFile -Raw
    $pattern = "(?m)^${Key}=.*$"
    if ($content -match $pattern) {
        $content = [regex]::Replace($content, $pattern, "${Key}=$Value")
    } else {
        $content += "`n${Key}=$Value"
    }
    # 写回时保持 LF 换行（compose 在 Linux 容器里解析，用 LF 更稳）
    $resolvedPath = (Resolve-Path $EnvFile).Path
    $normalized = ($content -replace "`r`n", "`n")
    [System.IO.File]::WriteAllText($resolvedPath, $normalized)
}

function Init-Env {
    if (-not (Test-Path $EnvFile)) {
        Log "首次部署：从模板生成 $EnvFile"
        Copy-Item $EnvTemplate $EnvFile
    }

    # 自动生成 JWT 密钥
    $jwt = Get-EnvValue "JWT_SECRET_KEY"
    if ([string]::IsNullOrWhiteSpace($jwt)) {
        $bytes = New-Object byte[] 32
        [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        $jwt = [Convert]::ToBase64String($bytes)
        Set-EnvValue "JWT_SECRET_KEY" $jwt
        Ok "已自动生成 JWT_SECRET_KEY"
    }

    # 询问 SERVER_IP（Windows 场景下这里填你的 VPS 公网 IP）
    $serverIp = Get-EnvValue "SERVER_IP"
    if ([string]::IsNullOrWhiteSpace($serverIp)) {
        Write-Host ""
        Write-Host "===== SERVER_IP 配置说明 =====" -ForegroundColor Yellow
        Write-Host "  1) 如果只在本机访问，填 127.0.0.1"
        Write-Host "  2) 如果通过 VPS 内网穿透对外暴露，填 VPS 公网 IP"
        Write-Host "  3) 如果局域网访问，填本机内网 IP（如 192.168.1.100）"
        Write-Host ""
        $serverIp = Read-Host "请输入 SERVER_IP"
        if ([string]::IsNullOrWhiteSpace($serverIp)) { Fail "SERVER_IP 不能为空" }
        Set-EnvValue "SERVER_IP" $serverIp
        Ok "SERVER_IP=$serverIp"
    }

    # 根据 SERVER_IP + WEB_PORT 自动生成跨域白名单
    $webPort = Get-EnvValue "WEB_PORT"
    if ([string]::IsNullOrWhiteSpace($webPort)) { $webPort = "80" }
    $origins = "http://localhost:*,http://127.0.0.1:*,http://${serverIp}:*,http://${serverIp}"
    if ($webPort -ne "80") { $origins += ",http://${serverIp}:${webPort}" }
    Set-EnvValue "SECURITY_ALLOWED_ORIGINS" $origins

    # 提示必填 API Key
    $deepseek  = Get-EnvValue "DEEPSEEK_API_KEY"
    $embedding = Get-EnvValue "EMBEDDING_API_KEY"
    if ([string]::IsNullOrWhiteSpace($deepseek)) {
        $deepseek = Read-Host "请输入 DeepSeek API Key (https://platform.deepseek.com/)"
        Set-EnvValue "DEEPSEEK_API_KEY" $deepseek
    }
    if ([string]::IsNullOrWhiteSpace($embedding)) {
        $embedding = Read-Host "请输入百炼 DashScope API Key (https://dashscope.console.aliyun.com/)"
        Set-EnvValue "EMBEDDING_API_KEY" $embedding
    }

    Ok ".env 已就绪"
}

function Show-Banner {
@"
  ____       _ ____                       _
 |  _ \ __ _(_) ___| _ __ ___   __ _ _ __| |_
 | |_) / _`` | \___ \| '_ `` _ \ / _`` | '__| __|
 |  __/ (_| | |___) | | | | | | (_| | |  | |_
 |_|   \__,_|_|____/|_| |_| |_|\__,_|_|   \__|

 派聪明 RAG · Windows + Docker Desktop
"@ | Write-Host -ForegroundColor Cyan
}

function Cmd-Up {
    Log "构建并启动所有容器（首次 5~15 分钟，包含 Maven + pnpm）..."
    docker compose -f $ComposeFile --env-file $EnvFile up -d --build
    if ($LASTEXITCODE -ne 0) { Fail "docker compose up 失败" }
    Ok "容器已启动"
    Cmd-Status
}

function Cmd-Down    { docker compose -f $ComposeFile --env-file $EnvFile down }
function Cmd-Restart { docker compose -f $ComposeFile --env-file $EnvFile restart; Cmd-Status }
function Cmd-Rebuild {
    docker compose -f $ComposeFile --env-file $EnvFile build --no-cache backend frontend
    docker compose -f $ComposeFile --env-file $EnvFile up -d backend frontend
}
function Cmd-Logs {
    param([string]$svc = "")
    if ([string]::IsNullOrWhiteSpace($svc)) {
        docker compose -f $ComposeFile --env-file $EnvFile logs -f --tail=200
    } else {
        docker compose -f $ComposeFile --env-file $EnvFile logs -f --tail=200 $svc
    }
}
function Cmd-Ps      { docker compose -f $ComposeFile --env-file $EnvFile ps }

function Cmd-Clean {
    Write-Host "警告：此操作会删除所有数据卷（MySQL/ES/MinIO 数据都会消失）" -ForegroundColor Red
    $ans = Read-Host "确认继续？输入 YES"
    if ($ans -eq "YES") {
        docker compose -f $ComposeFile --env-file $EnvFile down -v
        Ok "已清理"
    } else {
        Warn "已取消"
    }
}

function Cmd-Status {
    Write-Host ""
    Write-Host "==================== 容器状态 ====================" -ForegroundColor Green
    docker compose -f $ComposeFile --env-file $EnvFile ps
    Write-Host ""

    $serverIp        = Get-EnvValue "SERVER_IP"
    $webPort         = Get-EnvValue "WEB_PORT"
    $adminUser       = Get-EnvValue "ADMIN_BOOTSTRAP_USERNAME"
    $minioConsolePort= Get-EnvValue "MINIO_CONSOLE_PORT"
    $minioApiPort    = Get-EnvValue "MINIO_API_PORT"
    if (-not $webPort)          { $webPort = "80" }
    if (-not $minioConsolePort) { $minioConsolePort = "19001" }
    if (-not $minioApiPort)     { $minioApiPort = "19000" }

    $webUrl = "http://$serverIp"
    if ($webPort -ne "80") { $webUrl = "http://${serverIp}:${webPort}" }

    Write-Host "==================== 访问地址 ====================" -ForegroundColor Green
    Write-Host "前端入口      : $webUrl"
    Write-Host "后端 API      : $webUrl/api/v1/"
    Write-Host "MinIO 控制台  : http://${serverIp}:${minioConsolePort}"
    Write-Host "MinIO API     : http://${serverIp}:${minioApiPort}"
    Write-Host ""
    Write-Host "==================== 初始账号 ====================" -ForegroundColor Green
    Write-Host "管理员用户名  : $(if ($adminUser) { $adminUser } else { 'admin' })"
    Write-Host "管理员密码    : 见 .env 里 ADMIN_BOOTSTRAP_PASSWORD"
    Write-Host ""
    Write-Host "提示：首次启动后端需要 1~3 分钟完成 ES 插件安装 + Kafka 初始化" -ForegroundColor Yellow
    Write-Host "      如果浏览器白屏，先跑 .\deploy.ps1 logs backend 看日志" -ForegroundColor Yellow
}

# ---------- 主入口 ----------
Show-Banner
Check-Prereq

switch -Regex ($Command.ToLower()) {
    '^(up|)$'        { Init-Env; Cmd-Up }
    '^(down|stop)$'  { Cmd-Down }
    '^restart$'      { Cmd-Restart }
    '^rebuild$'      { Cmd-Rebuild }
    '^logs$'         { Cmd-Logs -svc $SubArg }
    '^ps$'           { Cmd-Ps }
    '^status$'       { Cmd-Status }
    '^clean$'        { Cmd-Clean }
    '^(help|-h|--help)$' {
        Get-Content $MyInvocation.MyCommand.Path | Where-Object { $_ -match '^#' } | ForEach-Object { $_ -replace '^#\s?', '' }
    }
    default          { Fail "未知命令: $Command（使用 .\deploy.ps1 help 查看帮助）" }
}
