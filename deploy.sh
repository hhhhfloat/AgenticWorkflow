#!/usr/bin/env bash
set -e

echo "📦 开始编译源码..."
mvn clean package -DskipTests

echo "🚀 发布到 WSL2 运行目录..."
TARGET_DIR="/root/agentic-workflow"
mkdir -p "$TARGET_DIR"

# 拷贝 fat jar
cp target/agentic-workflow-1.0-SNAPSHOT-jar-with-dependencies.jar "$TARGET_DIR/AgenticWorkflow.jar"

# 拷贝启动脚本
cp start.sh "$TARGET_DIR/start.sh"
chmod +x "$TARGET_DIR/start.sh"

echo "✅ 发布完成！你可以这样启动："
echo "   cd $TARGET_DIR && ./start.sh"