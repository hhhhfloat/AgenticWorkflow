@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion
title Agentic Workflow v4.0

:: -r / --reset-config  强制重新探测环境，覆盖 agent-config.properties
:: -m / --mobile        绑定 Tailscale IP，供手机远程访问
set RESET_CONFIG=0
set MOBILE_MODE=0

:parse_args
if "%~1"=="" goto :args_done
if /I "%~1"=="-r"             goto :arg_reset
if /I "%~1"=="--reset-config" goto :arg_reset
if /I "%~1"=="-m"             goto :arg_mobile
if /I "%~1"=="--mobile"       goto :arg_mobile
shift
goto :parse_args

:arg_reset
set RESET_CONFIG=1
shift
goto :parse_args

:arg_mobile
set MOBILE_MODE=1
shift
goto :parse_args

:args_done

set CONFIG_FILE=agent-config.properties

echo ========================================
echo  Agentic Workflow v5.0
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
    goto :start
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

:start

REM 检查是否请求切回本地模式（由 /switch-to-local 写入）
if exist "%~dp0.next-launch-local" (
    del "%~dp0.next-launch-local"
    set "MOBILE_MODE=0"
    echo 🔄 检测到切回本地模式请求
)

REM ============================================================
REM  5.5 解析绑定地址
REM ============================================================
if "%MOBILE_MODE%"=="1" (
    echo 📱 移动模式：获取 Tailscale IP ...
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
        echo   3. 或者手动设置：set AGENT_BIND=100.x.x.x
        echo.
        pause
        exit /b 1
    )
    echo ✅ Tailscale IP: !AGENT_BIND!
) else (
    set "AGENT_BIND=127.0.0.1"
)
echo.

java --add-modules jdk.compiler ^
     --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED ^
     --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED ^
     --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED ^
     --add-exports jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED ^
     --add-exports jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED ^
     -jar "%JAR_FILE%"

set RC=%errorlevel%

REM ============================================================
REM  6. 退出码处理
REM ============================================================
if %RC%==0 (
    echo.
    echo ✅ 服务已停止
    pause
    exit /b 0
)

if %RC%==10 (
    echo.
    echo ❌ API Key 无效，请重新输入
    reg delete "HKCU\Environment" /v DEEPSEEK_API_KEY /f >nul 2>&1
    set DEEPSEEK_API_KEY=
    goto :input_key
)

if %RC%==12 (
    echo.
    echo ❌ 已有程序正在运行，请先关闭其他实例
    pause
    exit /b 12
)

if %RC%==42 (
    echo.
    echo 🔑 用户请求清除 API Key
    reg delete "HKCU\Environment" /v DEEPSEEK_API_KEY /f >nul 2>&1
    set DEEPSEEK_API_KEY=
    goto :input_key
)

if %RC%==43 (
    echo.
    echo 🔄 配置已更新，正在重启 ...
    goto :start
)

echo.
echo ❌ 程序异常退出，错误码：%RC%
pause
exit /b %RC%