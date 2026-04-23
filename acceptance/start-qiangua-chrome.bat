@echo off
REM =========================================================================
REM  PaiSmart - 一键启动千瓜 / 小红书浏览器自动化桥接
REM  双击运行：
REM    1) 启动一个专用的 Chrome（带 9222 调试端口，独立用户目录）
REM    2) 启动 CDP 代理，把 host.docker.internal:9223 转发到 Chrome 127.0.0.1:9222
REM    3) 打开千瓜登录页
REM  请登录账号：17733738352  密码：sk333111
REM  登录后保持这个 Chrome 窗口 + 本 cmd 窗口都不要关
REM =========================================================================

setlocal

REM ---------- 1. 定位 Chrome ----------
set CHROME=
if exist "%ProgramFiles%\Google\Chrome\Application\chrome.exe"        set "CHROME=%ProgramFiles%\Google\Chrome\Application\chrome.exe"
if exist "%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe"   set "CHROME=%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe"
if exist "%LOCALAPPDATA%\Google\Chrome\Application\chrome.exe"        set "CHROME=%LOCALAPPDATA%\Google\Chrome\Application\chrome.exe"
if "%CHROME%"=="" (
    echo [ERROR] 没有找到 Google Chrome，请先安装后再运行本脚本
    pause
    exit /b 1
)

REM ---------- 2. 定位 Node ----------
where node >nul 2>&1
if errorlevel 1 (
    echo [ERROR] 没有找到 node，请先安装 Node.js 18+ ^( https://nodejs.org ^)
    pause
    exit /b 1
)

set "PROFILE_DIR=%LOCALAPPDATA%\PaiSmart\chrome-profile-qiangua"
if not exist "%PROFILE_DIR%" mkdir "%PROFILE_DIR%"

set "SCRIPT_DIR=%~dp0"

echo ==========================================================
echo  Chrome    : %CHROME%
echo  Profile   : %PROFILE_DIR%
echo  CDP port  : 9222 (chrome loopback)
echo  Proxy port: 9223 (container -> host.docker.internal:9223)
echo ==========================================================
echo.

REM ---------- 3. 启动 Chrome ----------
start "" "%CHROME%" --remote-debugging-port=9222 --user-data-dir="%PROFILE_DIR%" --no-first-run --no-default-browser-check https://www.qian-gua.com/

REM ---------- 4. 前台启动 CDP 代理（关闭本窗口即结束代理）----------
echo.
echo [INFO] 正在启动 CDP 代理，窗口请保持开着...
echo        要停止：关闭 Chrome 窗口 + 关闭本 cmd 窗口
echo.
node "%SCRIPT_DIR%cdp-proxy.mjs"

endlocal
