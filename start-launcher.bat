@echo off
setlocal EnableDelayedExpansion
chcp 65001 >nul
title Agentic Workflow Launcher

set JAR_FILE=
for %%f in (
    "AgenticWorkflow.jar"
    "target\agentic-workflow-1.0-SNAPSHOT-jar-with-dependencies.jar"
    "agentic-workflow-1.0-SNAPSHOT-jar-with-dependencies.jar"
) do (
    if not defined JAR_FILE if exist %%f set JAR_FILE=%%~f
)
if not defined JAR_FILE (
    echo ❌ 未找到可执行的 JAR 文件
    pause
    exit /b 1
)

REM ============================================================
REM  获取 Tailscale IP
REM ============================================================
echo 📡 获取 Tailscale IP ...
set "AGENT_BIND="
set "TS_EXE=C:\Program Files\Tailscale\tailscale.exe"
if not exist "!TS_EXE!" set "TS_EXE=tailscale"

for /f "delims=" %%i in ('""!TS_EXE!" ip -4" 2^>nul') do (
    if not defined AGENT_BIND set "AGENT_BIND=%%i"
)

if not defined AGENT_BIND (
    echo.
    echo [错误] 未能获取 Tailscale IP
    echo.
    echo 请检查：
    echo   1. Tailscale 客户端已启动
    echo   2. 已登录账号
    echo   3. 或手动指定：set AGENT_BIND=100.x.x.x 后再运行
    echo.
    pause
    exit /b 1
)

echo ✅ Tailscale IP: %AGENT_BIND%
echo.

echo ========================================
echo  Agentic Workflow Launcher
echo  监听: %AGENT_BIND%:8081
echo  主服务: %AGENT_BIND%:8080
echo ========================================
echo.

java --add-modules jdk.compiler ^
     --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED ^
     --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED ^
     --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED ^
     --add-exports jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED ^
     --add-exports jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED ^
     -cp "%JAR_FILE%" com.myagent.workflow.launcher.LauncherMain

echo.
echo 💡 关闭本窗口前，建议先访问 /stop 停止主服务，避免残留进程占用文件
pause
