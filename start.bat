@echo off
setlocal EnableDelayedExpansion
chcp 65001 >nul
title Agentic Workflow Launcher

:: -r / --reset-config  强制重新探测环境，覆盖 agent-config.properties
set RESET_CONFIG=0

:parse_args
if "%~1"=="" goto :args_done
if /I "%~1"=="-r"             goto :arg_reset
if /I "%~1"=="--reset-config" goto :arg_reset
shift
goto :parse_args

:arg_reset
set RESET_CONFIG=1
shift
goto :parse_args

:args_done

REM ============================================================
REM  快速检测：launcher 已在运行则直接打开对应页面
REM ============================================================
netstat -ano | findstr ":8081" | findstr "LISTENING" >nul
if %errorlevel%==0 (
    echo ✅ 检测到 launcher 已在运行，直接打开页面 ...

    set "AGENT_BIND="
    set "TS_EXE=C:\Program Files\Tailscale\tailscale.exe"
    if not exist "!TS_EXE!" set "TS_EXE=tailscale"
    for /f "delims=" %%i in ('""!TS_EXE!" ip -4" 2^>nul') do (
        if not defined AGENT_BIND set "AGENT_BIND=%%i"
    )

    if not defined AGENT_BIND (
        set "AGENT_BIND=127.0.0.1"
        echo ⚠️  未检测到 Tailscale，按本机模式打开页面
    )

    netstat -ano | findstr ":8080" | findstr "LISTENING" >nul
    if !errorlevel!==0 (
        echo ▶ 主服务运行中，打开工作台: http://!AGENT_BIND!:8080/
        start "" "http://!AGENT_BIND!:8080/"
    ) else (
        echo ▶ launcher 运行中，打开控制页: http://!AGENT_BIND!:8081/
        start "" "http://!AGENT_BIND!:8081/"
    )
    timeout /t 1 /nobreak >nul
    exit /b 0
)

set CONFIG_FILE=agent-config.properties

echo ========================================
echo  Agentic Workflow Launcher
echo  基于 DeepSeek API 的本地 AI 编程助手
echo ========================================
echo.

REM ============================================================
REM  1. 检查 Java 版本 >= 21
REM ============================================================
for /f "tokens=3" %%g in ('java -version 2^>^&1 ^| findstr /i "version"') do set JAVAVER=%%g
if not defined JAVAVER (
    echo ❌ 未检测到 Java 运行环境
    echo    请安装 Java 21+：https://adoptium.net/temurin/releases/?version=21
    echo.
    pause
    exit /b 1
)
set JAVAVER=%JAVAVER:"=%
for /f "delims=. " %%a in ("%JAVAVER%") do set JAVAMAJOR=%%a
if %JAVAMAJOR% LSS 21 (
    echo ⚠️ Java 版本过低（当前 %JAVAVER%），需要 21+
    echo    请升级：https://adoptium.net/temurin/releases/?version=21
    echo.
    pause
    exit /b 1
)
echo ✅ Java %JAVAVER%

REM ============================================================
REM  2. 查找 JAR
REM ============================================================
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
    echo.
    pause
    exit /b 1
)
echo ✅ JAR: %JAR_FILE%

REM ============================================================
REM  3. 环境配置（生成 / 复用）
REM ============================================================
if "%RESET_CONFIG%"=="1" if exist "%CONFIG_FILE%" (
    echo 🔄 -r 生效，删除旧配置
    del /q "%CONFIG_FILE%"
)

if exist "%CONFIG_FILE%" (
    echo ✅ 配置: %CONFIG_FILE%
) else (
    echo 🔍 未找到配置，正在探测本机环境 ...
    java -cp "%JAR_FILE%" com.myagent.workflow.core.EnvDetector "%CONFIG_FILE%"
    if exist "%CONFIG_FILE%" (
        echo ✅ 已生成 %CONFIG_FILE%
        echo    💡 可编辑该文件自定义路径，之后重新运行本脚本生效
    ) else (
        echo ⚠️ 探测失败，程序将以内置默认值启动
    )
)
echo.

REM ============================================================
REM  4. 检查 API Key
REM ============================================================
:check_key
if defined DEEPSEEK_API_KEY (
    echo ✅ 已检测到 API Key
    echo.
    goto :resolve_bind
)

:input_key
echo ⚠️ 未检测到 API Key
echo    获取方式：https://platform.deepseek.com
echo.
set /p DEEPSEEK_API_KEY="请输入你的 DeepSeek API Key："
if not defined DEEPSEEK_API_KEY goto :input_key

setx DEEPSEEK_API_KEY "%DEEPSEEK_API_KEY%" >nul
echo.
echo ✅ API Key 已保存
echo.

REM ============================================================
REM  5. 获取 Tailscale IP
REM ============================================================
:resolve_bind
echo 📡 获取 Tailscale IP ...
set "AGENT_BIND="
set "TS_EXE=C:\Program Files\Tailscale\tailscale.exe"
if not exist "!TS_EXE!" set "TS_EXE=tailscale"

for /f "delims=" %%i in ('""!TS_EXE!" ip -4" 2^>nul') do (
    if not defined AGENT_BIND set "AGENT_BIND=%%i"
)

if not defined AGENT_BIND (
    echo.
    echo ⚠️  未检测到 Tailscale IP，降级为本机模式（127.0.0.1）
    echo.
    echo    电脑端可正常使用，手机端将无法访问。
    echo    需要手机访问请先启动 Tailscale，再重跑本脚本。
    echo    下载: https://tailscale.com/download
    echo.
    set "AGENT_BIND=127.0.0.1"
    timeout /t 3 /nobreak >nul
)

echo ✅ Tailscale IP: %AGENT_BIND%
echo.

echo ========================================
echo  Agentic Workflow Launcher
echo  监听: %AGENT_BIND%:8081
echo  主服务: %AGENT_BIND%:8080
echo ========================================
echo.

REM 延迟 2 秒后自动打开 launcher 按钮页（后台异步，不阻塞下面的 java）
start "" cmd /c "timeout /t 2 /nobreak >nul && start http://%AGENT_BIND%:8081/"

java --add-modules jdk.compiler ^
     --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED ^
     --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED ^
     --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED ^
     --add-exports jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED ^
     --add-exports jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED ^
     -cp "%JAR_FILE%" com.myagent.workflow.launcher.LauncherMain

echo.
echo 💡 主服务会被自动关闭；如需手动关闭，请访问 http://<ip>:8081/ 点"停止主服务"
echo.
echo launcher 已退出。

pause