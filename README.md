# Agentic Workflow

基于 DeepSeek API 的本地 Agent 代码生成工作流。

> 项目代码及本说明均由 DeepSeek 辅助生成。

## 核心功能

- **自然语言驱动**：描述需求，Agent 自动拆解并执行，支持单次长任务闭环
- **锚点系统**：精准定位、插入、删除代码块，支持结构化重构
- **语义级工具**：`get_file_structure` 多语言结构解析（Java/Python/C++/JS/HTML/CSS）、`find_references` / `find_callers` / `find_callees` 调用链分析
- **上下文压缩开关**：前端配置面板支持「启用压缩模式」（默认开启）。开启后注入自适应检查点提示词，由 Agent 自主产出状态摘要，有效降低长上下文成本
- **安全扫描**：编译前自动拦截危险代码（系统命令、路径穿越）
- **支持编译运行**：Maven / Java / C++ / Python / Node.js / HTML，图形库支持 JavaFX 和 Pygame
- **侧边栏文件树**：浏览 `sandbox/` 与 `TestProjects/`，支持项目归档、文件上传、新建项目
- **运行日志持久化**：自动保存至 `HistoryOutput/`，自动保留 30 天
- **A/B 测试工具**（testtool）：内置 JavaFX 桌面对照程序，用于量化验证「压缩模式」开关对长任务 Token 成本与迭代轮数的影响

## 快速开始

1. 安装 **Java 21 JDK**（必须 21 或更高版本）
2. 设置环境变量 `DEEPSEEK_API_KEY`，或启动后在网页端输入
3. 双击 `start.bat`（Windows）或运行 `java -jar target/agentic-workflow-1.0-SNAPSHOT-jar-with-dependencies.jar`
4. 浏览器自动打开前端页面（本地 HTTP 服务，绑定 127.0.0.1:8080）

## 工具集

| Agent工具 | 用途 |
|-----------|------|
| `list_directory` / `read_file` / `write_file` | 基础文件操作（沙箱隔离） |
| `get_file_structure` | 获取文件结构（类/方法/字段/锚点），支持 Java/Python/C++/JS/HTML/CSS |
| `read_between_anchors` | 精准读取两个锚点之间的代码 |
| `compile_and_run` | 编译运行 / 浏览器预览（自动识别项目类型） |
| `search_text` / `find_references` / `find_callers` / `find_callees` | 代码搜索与调用关系分析 |
| `build_anchor_index` / `list_anchors` / `insert_at_anchor` / `delete_between_anchors` | 锚点索引构建与精准编辑 |
| `query_history` | 读取迭代历史记录 |
| `switch_model` | 动态切换 DeepSeek 模型（Pro/Flash） |
| `request_checkpoint` | **（压缩模式专用）** 请求 Agent 自压缩上下文，产出项目状态快照 |

## 版本记录

**v4.2** — 语义级结构解析 + 安全扫描 + 上下文压缩重构
- 新增 `get_file_structure`：多语言代码结构解析（Java/Python/C++/JS/HTML/CSS）
- 新增 `read_between_anchors`：精准锚点区间读取
- 新增安全扫描模块：编译前自动拦截危险代码（`CommandExecutionRule` / `FilePathRule`）
- 上下文管理重构：三段式结构（前缀+中间+后缀），中间段支持压缩。
- **新增「压缩模式」开关**：前端配置面板可视化控制，启用时注册 `request_checkpoint` 工具，由 Agent 按提示词自主决定何时产出摘要（自适应间隔），而非强制截断。
- 前端文件树预加载优化，展开更流畅；新增项目运行/归档/上传/新建全流程支持。

**v3.x** — 多语言编译运行 + 锚点体系完善 + 前端配置面板
- 支持 Java（单文件/Maven）、Python、Node.js、C++（MSVC/MinGW）
- `insert_at_anchor` + `delete_between_anchors` 实现代码块精准替换
- 前端配置面板：无需修改代码，直接在界面中调整模型、路径等配置
- 一键编译运行：Agent 编译成功的项目，前端直接生成运行按钮
- API Key 管理：一键清除 API Key，自动删除环境变量并重启

## 技术栈

- Java **21** / Maven
- OkHttp 4.12 + Jackson 2.17
- DeepSeek API（OpenAI 兼容）
- 纯前端 HTML + marked.js（SSE 流式渲染）
- （测试模块）JavaFX 21

## 目录说明

| 目录 | 说明 |
|------|------|
| `sandbox/` | Agent 生成与修改的所有代码（运行时沙箱） |
| `TestProjects/` | 归档的完整项目（可浏览/运行） |
| `HistoryOutput/` | 每次运行的日志文件（保留 30 天） |
| `temp/` | 临时文件目录 |
| `testPortfolio/` | A/B 测试程序生成的对比报告与图表 |

> 以上目录在 `jar` 文件同级自动创建。