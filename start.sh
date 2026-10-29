#!/usr/bin/env bash
set -euo pipefail

# ===== 解析参数 =====
RESET_CONFIG=0
for arg in "$@"; do
    case "$arg" in
        -r|--reset-config) RESET_CONFIG=1 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ===== 0. launcher 已在跑则直接开页面 =====
if (echo > /dev/tcp/127.0.0.1/8081) 2>/dev/null; then
    echo "✅ launcher 已在运行，打开控制页..."
    xdg-open "http://127.0.0.1:8081/" >/dev/null 2>&1 || true
    exit 0
fi

# ===== 1. Java 版本 =====
if ! command -v java &>/dev/null; then
    echo "❌ 未检测到 Java，请先安装 openjdk-21-jdk"
    exit 1
fi
JAVAVER=$(java -version 2>&1 | awk -F'"' '/version/{print $2}')
JAVAMAJOR=${JAVAVER%%.*}
if [ "${JAVAMAJOR%%-*}" -lt 21 ] 2>/dev/null; then
    echo "❌ Java 版本过低: $JAVAVER，需要 21+"
    exit 1
fi
echo "✅ Java $JAVAVER"

# ===== 2. 找 JAR =====
JAR_FILE=""
for f in \
    "$SCRIPT_DIR/AgenticWorkflow.jar" \
    "$SCRIPT_DIR/target/agentic-workflow-1.0-SNAPSHOT-jar-with-dependencies.jar" \
    "$SCRIPT_DIR/agentic-workflow-1.0-SNAPSHOT-jar-with-dependencies.jar"
do
    if [ -z "$JAR_FILE" ] && [ -f "$f" ]; then JAR_FILE="$f"; fi
done
if [ -z "$JAR_FILE" ]; then
    echo "❌ 未找到 JAR，请先 mvn package"
    exit 1
fi
echo "✅ JAR: $JAR_FILE"

# ===== 3. 配置 =====
CONFIG_FILE="agent-config.properties"
if [ "$RESET_CONFIG" = "1" ] && [ -f "$CONFIG_FILE" ]; then
    echo "🔄 -r 生效，删除旧配置"
    rm -f "$CONFIG_FILE"
fi
if [ ! -f "$CONFIG_FILE" ]; then
    echo "🔍 未找到配置，探测本机环境..."
    java -cp "$JAR_FILE" com.myagent.workflow.core.config.env.EnvDetector "$CONFIG_FILE" || true
fi
[ -f "$CONFIG_FILE" ] && echo "✅ 配置: $CONFIG_FILE"

# ===== 4. API Key =====
if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
    echo "⚠️ 未检测到 API Key（获取：https://platform.deepseek.com）"
    read -rsp "请输入 DEEPSEEK_API_KEY: " DEEPSEEK_API_KEY
    echo
    if [ -z "$DEEPSEEK_API_KEY" ]; then
        echo "❌ API Key 为空"
        exit 1
    fi
    export DEEPSEEK_API_KEY
fi
echo "✅ 已检测到 API Key"

# ===== 5. 绑定地址（Tailscale 不需要，默认本机）=====
export AGENT_BIND="${AGENT_BIND:-0.0.0.0}"
echo "✅ 监听地址: $AGENT_BIND"

# ===== 6. 启动 =====
echo "========================================"
echo " Agentic Workflow"
echo " launcher: http://$AGENT_BIND:8081/"
echo " 主服务  : http://$AGENT_BIND:8080/"
echo "========================================"

# 如果绑定的是 0.0.0.0，浏览器去访问 127.0.0.1
OPEN_HOST="$AGENT_BIND"
if [ "$OPEN_HOST" = "0.0.0.0" ]; then OPEN_HOST="127.0.0.1"; fi
( sleep 2 && xdg-open "http://$OPEN_HOST:8081/" >/dev/null 2>&1 ) &

exec java --add-modules jdk.compiler \
    --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
    --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
    --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
    --add-exports jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED \
    --add-exports jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED \
    -cp "$JAR_FILE" com.myagent.workflow.launcher.LauncherMain