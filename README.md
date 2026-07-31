# Agentic Workflow

基于 DeepSeek API 的本地 Agent 代码生成工作流。

> 项目代码及本说明均由 DeepSeek 辅助生成。

## 核心功能

- 自然语言驱动：描述需求，Agent 自动拆解并执行
- 锚点系统：精准定位、插入、删除代码块，支持结构化重构
- 语义级工具：`get_file_structure` 多语言结构解析、`find_references` / `find_callers` / `find_callees` 调用链分析
- 安全扫描：编译前自动拦截危险代码（系统命令、路径穿越）
- 支持编译运行：Maven / Java / C++ / Python / Node.js / HTML，图形库支持 JavaFX 和 Pygame
- 侧边栏文件树：浏览 `sandbox/` 与 `TestProjects/`，支持项目归档、文件上传、新建项目
- 运行日志持久化至 `HistoryOutput/`，自动保留 30 天
- 缓存命中率常驻 90%+

## 快速开始

1. 安装 Java 21 JDK
2. 双击 `start.bat`

## 工具集

| Agent工具 | 用途                                                |
|-----------|---------------------------------------------------|
| `list_directory` / `read_file` / `write_file` | 文件操作                                              |
| `get_file_structure` | 获取文件结构（类/方法/字段/锚点），支持 Java/Python/C++/JS/HTML/CSS |
| `read_between_anchors` | 精准读取两个锚点之间的代码                                     |
| `compile_and_run` | 编译运行 / 浏览器预览                                      |
| `search_text` / `find_references` / `find_callers` / `find_callees` | 代码搜索与调用分析                                         |
| `build_anchor_index` / `list_anchors` / `insert_at_anchor` / `delete_between_anchors` | 锚点索引与精准编辑                                         |
| `query_history` | 读取迭代历史记录                                          |
| `switch_model` | 动态切换 DeepSeek 模型（Pro/Flash）                       |


## 成本表现
- 测试使用了五次运行，共使用约 160 万token，共花费约 0.3 元，缓存命中率 96.25%，平均 540 万token / 元

### 【默认】不使用上下文压缩
- 

## 版本

**v4.0** — 语义级结构解析 + 安全扫描 + 上下文重构
- 新增 `get_file_structure`：多语言代码结构解析（Java/Python/C++/JS/HTML/CSS）
- 新增 `read_between_anchors`：精准锚点区间读取
- 新增安全扫描模块：编译前自动拦截危险代码
- 上下文管理重构：三段式结构，前缀+中间+后缀，中间段可以传递给compressWithFlash函数，让DeepSeek-v4-flash对上下位进行压缩。目前因为降低命中率，没有开启功能。
- 前端文件树预加载优化，展开更流畅

## 技术栈

- Java 17+ / Maven
- OkHttp + Jackson
- DeepSeek API（OpenAI 兼容）
- 纯前端 HTML + marked.js