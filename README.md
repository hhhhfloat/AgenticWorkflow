# Agentic Workflow

基于 DeepSeek API 的本地 Agentic 代码生成工作流。

>此文本与工作流的程序代码均由deepseek分步生成，可在
> https://chat.deepseek.com/share/ud2i4emlkpslkmo3h2
> 查看

## 核心功能

- 自然语言驱动代码生成与修改
- 工具链：代码搜索、目录浏览、文件读写、编译运行
- 锚点系统：Agent 自动标注关键位置，支持索引查询
- HTML/CSS/JS 强制拆分，符合工程规范
- Web 界面 + 心跳保活 + 历史记录持久化

## 快速开始

### 1. 设置 API Key
Main.java 与 HttpServer.java 运行配置中：环境变量 添加 DEEPSEEK_API_KEY ，输入您的API值

### 2. 启动 HTTP 服务
运行 HttpServerMain.java

### 3. 打开前端
双击 display/index.html

**必须先运行 `HttpServerMain.java` 再打开前端，才能连接到api**

**有时候会因为网络不稳定，连接失败。此时请重新在`index.html`页面上点击运行**


## 工具集

| 工具 | 用途 |
|------|------|
| `search_text` | 正则搜索代码引用 |
| `list_directory` | 查看目录结构 |
| `read_file` / `write_java_file` | 文件读写 |
| `compile_and_run` | 编译运行 / 浏览器预览 |
| `build_anchor_index` / `list_anchors` | 锚点索引构建与查询 |

## 成本表现

- 平均 1 元 / 140 万 Token（DeepSeek V4-Pro）
- 缓存命中率 > 90%，较无缓存成本降低约 60%

## 版本

**v1.0** — 首个稳定版本，支持锚点记录与索引查询，下一版本将实现 `insert_at_anchor` 精准插入。

## 技术栈

- Java 17+ / Maven
- OkHttp + Jackson
- DeepSeek API（OpenAI 兼容）
- 纯前端 HTML + marked.js