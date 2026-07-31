@echo off
chcp 65001 >nul
title Agentic Workflow v4.0

echo ========================================
echo  Agentic Workflow v4.0
echo  基于 DeepSeek API 的本地 AI 编程助手
echo ========================================
echo.

REM 检查 Java 是否可用
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo ❌ 未检测到 Java 运行环境
    echo.
    echo 请安装 Java 21 或更高版本：
    echo https://www.oracle.com/java/technologies/downloads/
    echo.
    echo 安装后重启 start.bat
    echo.
    pause
    exit /b 1
)

REM 检查 Java 版本是否 >= 21
for /f "tokens=3" %%g in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set JAVAVER=%%g
)
set JAVAVER=%JAVAVER:"=%
set JAVAVER=%JAVAVER:version=%
for /f "delims=. " %%a in ("%JAVAVER%") do set JAVAMAJOR=%%a
if %JAVAMAJOR% LSS 21 (
    echo ⚠️ Java 版本过低（当前：%JAVAVER%），需要 Java 21 或更高
    echo.
    echo 请升级 Java：https://adoptium.net/temurin/releases/?version=21
    echo.
    pause
    exit /b 1
)

echo ✅ Java 环境正常（版本：%JAVAVER%）
echo.

:check_key
reg query "HKCU\Environment" /v DEEPSEEK_API_KEY >nul 2>&1
if %errorlevel%==0 (
    echo ✅ 已检测到 API Key
    echo.
    goto :start
)

:input_key
echo ⚠️ 请输入（或重新输入）API Key
echo.
echo 获取方式：https://platform.deepseek.com
echo.
set /p DEEPSEEK_API_KEY="请输入你的 DeepSeek API Key："

setx DEEPSEEK_API_KEY "%DEEPSEEK_API_KEY%" >nul
set DEEPSEEK_API_KEY=%DEEPSEEK_API_KEY%

echo.
echo ✅ API Key 已保存
echo.

:start
echo 🚀 启动服务中...
echo 📂 工作目录: %cd%
echo 🌐 访问地址: http://localhost:8080
echo.
echo 💡 提示：关闭命令行窗口即可停止服务
echo.

REM ===== 查找 JAR 文件 =====
set JAR_FILE=
if exist "AgenticWorkflow.jar" (
    set JAR_FILE=AgenticWorkflow.jar
) else if exist "target\agentic-workflow-1.0-SNAPSHOT-jar-with-dependencies.jar" (
    set JAR_FILE=target\agentic-workflow-1.0-SNAPSHOT-jar-with-dependencies.jar
) else if exist "agentic-workflow-1.0-SNAPSHOT-jar-with-dependencies.jar" (
    set JAR_FILE=agentic-workflow-1.0-SNAPSHOT-jar-with-dependencies.jar
)else (
    echo ❌ 未找到可执行的 JAR 文件
    echo.
    pause
    exit /b 1
)

echo 📦 使用 JAR: %JAR_FILE%
echo.

java --add-modules jdk.compiler ^
     --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED ^
     --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED ^
     --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED ^
     --add-exports jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED ^
     --add-exports jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED ^
     -jar %JAR_FILE%

:: ===== 退出码处理 =====
if %errorlevel%==42 (
    echo.
    echo 🔑 用户请求清除 API Key，正在删除环境变量...
    reg delete "HKCU\Environment" /v DEEPSEEK_API_KEY /f >nul 2>&1
    echo ✅ API Key 已清除，请重新输入新的 Key
    echo.
    goto :input_key
)

if %errorlevel%==43 (
    echo.
    echo 🔄 配置已更新，正在重启服务...
    echo.
    goto :start
)

if %errorlevel%==12 (
    echo.
    echo ❌ 已有程序正在运行，请先关闭额外程序
    echo.
    pause
    exit /b %errorlevel%
)

if %errorlevel%==10 (
    echo.
    echo ❌ API Key 无效，请重新输入正确的 Key
    echo.
    reg delete "HKCU\Environment" /v DEEPSEEK_API_KEY /f >nul 2>&1
    goto :input_key
)

if %errorlevel% neq 0 (
    echo.
    echo ❌ 程序异常退出，错误码：%errorlevel%
    echo.
    pause
    exit /b %errorlevel%
)

echo.
echo ✅ 服务已停止
pause