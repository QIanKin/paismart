@echo off
REM ============================================================
REM 启动 frpc 客户端（双击运行，或放到开机启动目录）
REM
REM 开机启动方法：
REM   1. 按 Win+R 输入 shell:startup 回车
REM   2. 把本文件的快捷方式丢进去
REM
REM 或者用 NSSM 注册成 Windows 服务（见 DEPLOY-WINDOWS.md）
REM ============================================================

REM 切到脚本所在目录
cd /d %~dp0

REM 如果你把 frpc.exe 放在别的地方，改这里的路径
set FRPC_EXE=frpc.exe
set FRPC_CFG=frpc.toml

if not exist %FRPC_EXE% (
    echo [错误] 找不到 %FRPC_EXE%，请先把 frp 解压到当前目录。
    echo 下载地址：https://github.com/fatedier/frp/releases/latest
    pause
    exit /b 1
)

if not exist %FRPC_CFG% (
    echo [错误] 找不到 %FRPC_CFG%，请先复制 frpc.toml.example 为 frpc.toml 并修改。
    pause
    exit /b 1
)

echo [信息] 启动 frpc 客户端...
%FRPC_EXE% -c %FRPC_CFG%
pause
