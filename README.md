# Agentic Workflow

基于 DeepSeek API 的本地 Agentic 代码生成工作流。

> 此文本与工作流的程序代码均由 deepseek 分步生成，可在
> https://chat.deepseek.com/share/ud2i4emlkpslkmo3h2
> 查看

## 核心功能

- 自然语言驱动代码生成与修改
- 工具链：代码搜索、目录浏览、文件读写、编译运行
- 锚点系统：Agent 自动标注关键位置，支持索引查询、精准插入与替换
- HTML/CSS/JS 强制拆分，符合工程规范
- Web 界面 + 侧边栏文件树 + 心跳保活 + 历史记录持久化
- 运行日志自动持久化到 `HistoryOutput/` 目录
- 可自定义最大迭代次数（默认 20）

## 快速开始

### 1. 设置 API Key
运行配置中，环境变量添加 `DEEPSEEK_API_KEY`，输入您的 API 值。

### 2. 启动 HTTP 服务
运行 `HttpServerMain.java`，访问 http://localhost:8080

### 3. 使用
在 Web 界面输入需求，点击运行即可。侧边栏可浏览 `sandbox/` 和 `TestProjects/` 目录。

**必须先运行 `HttpServerMain.java` 再打开前端，才能连接到 API。**

**网络不稳定时，请刷新页面后重新点击运行。**

## 工具集

| 工具 | 用途 |
|------|------|
| `search_text` | 正则搜索代码引用 |
| `list_directory` | 查看目录结构 |
| `read_file` / `write_java_file` | 文件读写 |
| `compile_and_run` | 编译运行 / 浏览器预览 |
| `build_anchor_index` / `list_anchors` | 锚点索引构建与查询 |
| `insert_at_anchor` / `replace_at_anchor` | 锚点精准插入与替换 |

## 成本表现

- 平均 1 元 / 140 万 Token（DeepSeek V4-Pro）
- 缓存命中率 > 90%，较无缓存成本降低约 60%

## 版本

**v1.0** — 首个稳定版本，支持锚点记录与索引查询。  
**v2.0** — 新增侧边栏文件树、日志持久化、迭代次数控制、迭代彩蛋、`insert_at_anchor` / `replace_at_anchor` 精准编辑、项目入口一键预览。

## 技术栈

- Java 17+ / Maven
- OkHttp + Jackson
- DeepSeek API（OpenAI 兼容）
- 纯前端 HTML + marked.js