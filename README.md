# Agentic Workflow

基于 DeepSeek API 的本地 Agent 代码生成工作流。

> 项目代码及本说明均由 DeepSeek 辅助生成。

## 核心功能

- 自然语言驱动：描述需求，Agent 自动拆解并执行
- 锚点系统：精准定位、插入、删除代码块，支持结构化重构
- 语义级工具：`find_references`、`find_callers`、`find_callees` 辅助调用链分析
- HTML/CSS/JS 强制分离，符合工程规范
- 侧边栏文件树：浏览 `sandbox/` 与 `TestProjects/`，支持项目归档、文件上传、新建项目
- 运行日志持久化至 `HistoryOutput/`，自动保留 30 天
- 可调最大迭代次数（默认 30），缓存命中率常驻 93%+

## 快速开始

1. 设置环境变量 `DEEPSEEK_API_KEY`
2. 运行 `HttpServerMain.java`，访问 `http://localhost:8080`
3. 输入需求，点击运行

## 工具集

| 工具 | 用途 |
|------|------|
| `list_directory` / `read_file` / `write_java_file` | 文件操作 |
| `compile_and_run` | 编译运行 / 浏览器预览 |
| `search_text` / `find_references` / `find_callers` / `find_callees` | 代码搜索与调用分析 |
| `build_anchor_index` / `list_anchors` / `insert_at_anchor` / `delete_between_anchors` | 锚点索引与精准编辑 |
| `archiveProject` / `uploadFiles` / `createProject` / `openFolder` | 项目管理（侧边栏操作） |

## 成本表现

- 实测 1000 万 Token 约 3 元（DeepSeek V4-Pro）
- 缓存命中率 > 93%，输出占比 < 3%

## 版本

**v2.2** — 原子工具链完善（`find_references`、`delete_between_anchors`），计划表项目完整落地，缓存与输出效率达最优。

## 技术栈

- Java 17+ / Maven
- OkHttp + Jackson
- DeepSeek API（OpenAI 兼容）
- 纯前端 HTML + marked.js