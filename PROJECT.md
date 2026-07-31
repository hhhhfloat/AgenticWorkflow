# Agentic Workflow — 项目文档

> 基于 DeepSeek API 的本地 Agent 代码生成工作流。
> 项目代码及本文档均由 DeepSeek 辅助生成。
> **版本: v4.0**

---

## 目录结构

```
workflow/
├── pom.xml                                    # Maven 项目配置（Java 21, Fat Jar 打包）
├── README.md                                  # 项目说明
├── README.html                                # HTML 版说明
├── PROJECT.md                                 # 本文件 — 项目文档
│
├── src/
│   ├── main/
│   │   ├── java/com/myagent/workflow/
│   │   │   ├── core/                          # 核心调度与上下文
│   │   │   │   ├── Main.java                  # Agent 工作流编排器（多轮对话 + 工具调用循环）
│   │   │   │   ├── AgentConfig.java           # 配置记录（API Key, 编译器路径, 模型选择等）
│   │   │   │   ├── ConfigEditor.java          # 从 HTTP 请求构建运行时配置
│   │   │   │   ├── ContextManager.java        # 三段式上下文管理器（前缀-中间-后缀 + 自动压缩）
│   │   │   │   └── SystemPrompt.java          # Agent 系统提示词（行为规则）
│   │   │   │
│   │   │   ├── http/                          # HTTP 服务与 REST API
│   │   │   │   ├── HttpServerMain.java        # 入口：启动 HTTP 服务，注册路由
│   │   │   │   ├── LogFileWriter.java         # 日志文件写入（自动清理 30 天前的日志）
│   │   │   │   ├── utils/
│   │   │   │   │   └── HandlerUtils.java      # 查询解析、JSON 响应工具
│   │   │   │   └── handlers/                  # HTTP 请求处理器
│   │   │   │       ├── StaticHandler.java     # 静态资源（前端页面）
│   │   │   │       ├── ConfigHandler.java     # GET /config — 获取服务器配置
│   │   │   │       ├── ProjectsHandler.java   # GET /projects — 列出 TestProjects 下项目
│   │   │   │       ├── BrowseHandler.java     # GET /browse — 浏览 sandbox/TestProjects 目录
│   │   │   │       ├── ArchiveHandler.java    # POST /archive — 归档项目至 TestProjects
│   │   │   │       ├── UploadHandler.java     # POST /upload — 文件上传
│   │   │   │       ├── CreateProjectHandler.java # POST /createProject — 新建空项目
│   │   │   │       ├── OpenFolderHandler.java # POST /openFolder — 打开本地文件夹
│   │   │   │       ├── ExternalFileHandler.java # 外部文件目录挂载（sandbox, TestProjects）
│   │   │   │       └── 内部 Handler 类:
│   │   │   │           ├── RunHandler         # POST /run — SSE 流式执行 Agent 任务
│   │   │   │           ├── StopHandler        # POST /stop — 停止执行
│   │   │   │           ├── HeartbeatHandler   # POST /heartbeat — 心跳检测 + 目录变更检测
│   │   │   │           ├── ClearApiKeyHandler # POST /clear-api-key — 清除 API Key
│   │   │   │           ├── RestartHandler     # POST /restart — 重启服务
│   │   │   │           ├── ProjectMetaHandler # GET /project-meta — 查询项目注册表
│   │   │   │           └── RunProjectHandler  # POST /runProject — 运行项目入口文件
│   │   │   │
│   │   │   ├── model/                         # 数据模型（POJO 与 Record）
│   │   │   │   ├── AnchorLocation.java        # 锚点位置（项目路径/文件/行号/ID/预览）
│   │   │   │   ├── AnchorSummary.java         # 锚点摘要
│   │   │   │   ├── ClassDefinition.java       # 类定义（名称/类型/父类/接口/方法/字段）
│   │   │   │   ├── FieldDefinition.java       # 字段定义
│   │   │   │   ├── FileStructure.java         # 文件结构（语言/包/导入/类/函数/锚点）
│   │   │   │   └── MethodDefinition.java      # 方法定义
│   │   │   │
│   │   │   ├── parser/                        # 多语言代码结构解析器
│   │   │   │   ├── StructureParser.java       # 解析器接口
│   │   │   │   ├── StructureParserRegistry.java # 解析器注册中心（按文件扩展名路由）
│   │   │   │   ├── JavaParser.java            # Java 结构解析（使用 javac Tree API）
│   │   │   │   ├── PythonParser.java          # Python 结构解析
│   │   │   │   ├── CppParser.java             # C++ 结构解析
│   │   │   │   ├── JavaScriptParser.java      # JS/TS 结构解析
│   │   │   │   ├── HtmlParser.java            # HTML 结构解析
│   │   │   │   ├── CssParser.java             # CSS 结构解析
│   │   │   │   ├── GenericParser.java         # 通用解析器（锚点提取兜底）
│   │   │   │   └── FileStructureFormatter.java # 结构化输出格式化器
│   │   │   │
│   │   │   ├── tools/                         # Agent 工具实现
│   │   │   │   ├── ToolDefinitions.java       # 工具 Schema 定义（DeepSeek Function Calling）
│   │   │   │   ├── ToolExecutor.java          # 工具执行器（调度所有工具调用）
│   │   │   │   ├── FileOperator.java          # 文件读写/删除/目录列举
│   │   │   │   ├── PathUtils.java             # 安全路径解析（防穿越）
│   │   │   │   ├── Compiler.java              # 编译运行引擎（Java/C++/Python/Node/HTML）
│   │   │   │   ├── AnchorManager.java         # 锚点管理（索引/查找/插入/删除/区间读取）
│   │   │   │   └── CodeSearcher.java          # 代码搜索与调用链分析
│   │   │   │
│   │   │   └── security/                      # 安全扫描模块
│   │   │       ├── SecurityScanner.java       # 安全扫描器（单例，递归目录扫描）
│   │   │       ├── SecurityConfig.java        # 安全配置
│   │   │       ├── ScanResult.java            # 扫描结果
│   │   │       ├── CodeLine.java              # 代码行（带行号）
│   │   │       ├── Severity.java              # 违规严重级别枚举
│   │   │       ├── Violation.java             # 违规记录
│   │   │       ├── filters/
│   │   │       │   ├── WhitelistFilter.java   # 白名单过滤器
│   │   │       │   └── ContextAwareFilter.java # 上下文感知过滤器
│   │   │       ├── parsers/
│   │   │       │   ├── CodeParser.java        # 解析器接口
│   │   │       │   ├── JavaParser.java        # Java 代码解析（去注释/字符串）
│   │   │       │   ├── PythonParser.java      # Python 代码解析
│   │   │       │   └── CppParser.java         # C++ 代码解析
│   │   │       └── rules/
│   │   │           ├── Rule.java              # 规则接口
│   │   │           ├── RuleRegistry.java      # 规则注册表
│   │   │           ├── FilePathRule.java      # 路径穿越检测规则
│   │   │           └── CommandExecutionRule.java # 系统命令执行检测规则
│   │   │
│   │   └── resources/
│   │       ├── static/                        # 前端静态资源
│   │       │   ├── index.html                 # 主页面（Agent 工作台）
│   │       │   ├── style.css                  # 样式（深色主题）
│   │       │   ├── marked.min.js              # Markdown 渲染库
│   │       │   ├── tips.js                    # 提示文案
│   │       │   └── modules/                   # 前端模块化 JS
│   │       │       ├── config.js              # DOM 引用与常量
│   │       │       ├── quote.js               # 文案工具
│   │       │       ├── log.js                 # 日志输出
│   │       │       ├── history.js             # 历史记录
│   │       │       ├── heartbeat.js           # 心跳机制
│   │       │       ├── tree.js                # 文件树
│   │       │       ├── archive.js             # 归档/创建/上传
│   │       │       ├── openFolder.js          # 打开文件夹
│   │       │       ├── sse.js                 # SSE 流式请求
│   │       │       ├── settings.js            # 配置管理
│   │       │       ├── sidebar.js             # 侧边栏切换
│   │       │       ├── runner.js              # 运行模块
│   │       │       └── events.js              # 事件绑定与初始化
│   │       └── logback.xml                    # 日志配置
│   │
│   └── test/java/                             # 测试目录（空）
```

---

## 数据模型

### 核心 Record / POJO

| 模型 | 类型 | 字段 | 用途 |
|------|------|------|------|
| `AgentConfig` | `record` | `apiKey`, `model`, `mavenCommand`, `javaHome`, `pythonInterpreter`, `nodeInterpreter`, `cppCompilerType`, `msvcCompiler`, `mingwCompiler`, `enableSecurityScan`... | 运行时配置（用户可覆盖） |
| `FileStructure` | `record` | `filePath`, `language`, `packageName`, `imports`, `classes`, `functions`, `fields`, `anchors` | 多语言代码结构解析结果 |
| `ClassDefinition` | `record` | `name`, `type`, `superClass`, `interfaces`, `methods`, `fields`, `anchors`, `startLine`, `endLine` | 类/接口/枚举/结构体定义 |
| `MethodDefinition` | `record` | `name`, `returnType`, `parameters`, `modifiers`, `startLine`, `endLine`, `anchorId` | 方法/函数定义 |
| `FieldDefinition` | `record` | `name`, `type`, `modifiers`, `line` | 字段/属性定义 |
| `AnchorLocation` | `class` | `projectPath`, `filePath`, `line`, `id`, `preview` | 锚点查找结果 |

### 三段式上下文管理（ContextManager） 【未启用】

```
prefix (系统提示 + 用户请求 + 压缩摘要)
   ↓ 滚动
toCompress (中间历史轮次)
   ↓ 超过阈值触发压缩
suffix (最近 hotSuffixRounds 轮热历史)
```

- **前缀**：系统提示词 + 用户请求 + 压缩摘要（永不压缩）
- **中间**：滚动出的旧历史轮次（超过阈值时压缩为摘要）
- **后缀**：最近 N 轮热历史（保留原始上下文）

---

## 关键锚点

### Main.java
| 锚点 ID | 用途 |
|---------|------|
| `main_class` | Agent 工作流编排器类定义 |
| `main_fields` | 核心字段（httpClient, objectMapper, toolExecutor 等） |
| `main_constructor` | 构造函数（初始化 HTTP 客户端、上下文管理器、工具执行器） |
| `main_run` | 主运行循环（多轮迭代、工具调度、上下文管理） |
| `main_stop` | 停止请求 |
| `main_checkStop` | 中断检查 |
| `main_logIf` | 日志输出 |
| `main_entry` | 命令行入口 |
| `main_setLogConsumer` | 设置日志消费者 |

### HttpServerMain.java
| 锚点 ID | 用途 |
|---------|------|
| `httpserver_class` | HTTP 服务入口类定义 |
| `httpserver_config` | 端口/超时配置 |
| `httpserver_state` | 运行状态（心跳/Agent实例/锁） |
| `httpserver_exitCodes` | 退出码定义 |
| `httpserver_entry` | main 方法入口 |
| `httpserver_runHandler` | SSE 流式执行处理器 |
| `httpserver_stopHandler` | 停止请求处理器 |
| `httpserver_heartbeatHandler` | 心跳处理器（含目录变更检测） |
| `httpserver_heartbeatMonitor` | 心跳监控线程 |

### SystemPrompt.java
| 锚点 ID | 用途 |
|---------|------|
| `systemPrompt_class` | 系统提示词类定义 |
| `systemPrompt_get` | 完整行为规则文本 |

---

## 功能清单

### 核心 Agent 能力
- [x] **自然语言驱动**：用户输入描述，Agent 自动拆解任务并迭代执行
- [x] **多轮对话**：基于 DeepSeek Function Calling，支持自动工具调度
- [x] **动态模型切换**：运行中可在 deepseek-v4-flash 和 deepseek-v4-pro 间切换
- [x] **三段式上下文管理**：前缀-中间-后缀结构 + Flash 模型压缩
- [x] **成本统计**：实时计算 Token 消耗和费用

### Agent 工具（共 17 个）
| 工具 | 功能 |
|------|------|
| `list_directory` | 列出目录（支持递归） |
| `read_file` | 读取文件（限制 50000 字符） |
| `write_file` | 写入文件（自动创建父目录） |
| `delete_file` | 删除文件 |
| `compile_and_run` | 编译运行（Java/C++/Python/Node/HTML/Maven） |
| `get_file_structure` | 多语言代码结构解析 |
| `search_text` | 文本搜索（支持正则） |
| `read_between_anchors` | 读取锚点区间代码 |
| `build_anchor_index` | 重建锚点索引 |
| `list_anchors` | 列出项目锚点 |
| `insert_at_anchor` | 在锚点插入代码 |
| `delete_between_anchors` | 删除锚点区间代码 |
| `find_references` | 查找符号引用 |
| `find_callers` | 查找函数调用者 |
| `find_callees` | 查找函数被调用者 |
| `switch_model` | 切换 DeepSeek 模型 |
| `query_history` | 查询历史记录 |

### HTTP 服务（REST API）
- [x] `POST /run` — SSE 流式执行 Agent 任务
- [x] `POST /stop` — 停止正在执行的任务
- [x] `POST /heartbeat` — 心跳检测 + 目录变更自动刷新
- [x] `POST /archive` — 归档 sandbox 项目至 TestProjects
- [x] `POST /createProject` — 在 sandbox 创建空项目
- [x] `POST /upload` — 上传文件
- [x] `POST /openFolder` — 打开本地文件夹
- [x] `POST /clear-api-key` — 清除 API Key 并重启
- [x] `POST /restart` — 重启服务
- [x] `GET /browse` — 浏览 sandbox/TestProjects 目录
- [x] `GET /projects` — 列出 TestProjects 下的项目
- [x] `GET /config` — 获取服务配置
- [x] `GET /project-meta` — 查询项目 .agent_entry.json 注册表
- [x] `POST /runProject` — 运行指定项目入口文件

### 安全扫描
- [x] 系统命令执行检测（Runtime.exec, os.system, subprocess, child_process 等）
- [x] 路径穿越检测（`..`, 盘符）
- [x] 多语言代码解析（Java/Python/C++）
- [x] 白名单过滤（安全的 import/include 语句）
- [x] 缓存机制（文件未变更时跳过扫描）
- [x] 递归目录扫描

### 多语言编译运行
- [x] Java 单文件编译（javac + java）
- [x] Maven 项目编译（mvn clean compile）
- [x] JavaFX 项目运行（mvn javafx:run）
- [x] C++ 编译运行（支持 MSVC 和 MinGW 双编译器）
- [x] Python 执行（GUI 和 CLI 双模式）
- [x] Node.js 执行
- [x] HTML 浏览器预览
- [x] 智能进程执行器（超时检测 + 输入阻塞检测 + 安全环境隔离）

### 前端 Web 界面
- [x] 深色主题终端风格
- [x] 可折叠侧边栏文件树（sandbox + TestProjects 双目录）
- [x] SSE 流式实时输出
- [x] Markdown 渲染（代码块/表格/标题等）
- [x] 迭代次数控制
- [x] 配置弹窗（编译器路径/模型/安全扫描开关等）
- [x] 项目归档、创建、上传
- [x] 打开本地文件夹
- [x] 运行项目入口文件
- [x] 心跳机制（前端断开自动停止任务 + 目录变更自动刷新）
- [x] 日志文件夹快速访问

### 其他功能
- [x] 日志持久化至 `./HistoryOutput/`，自动保留 30 天
- [x] 运行历史记录至 `./history.jsonl`
- [x] 项目注册表 `.agent_entry.json`（记录入口文件和模式）
- [x] 安全进程执行（环境变量重定向、工作目录白名单）

---

## 更新日志

### v4.0 — 语义级结构解析 + 安全扫描 + 上下文重构
- 新增 `get_file_structure`：多语言代码结构解析（Java/Python/C++/JS/HTML/CSS）
- 新增 `read_between_anchors`：精准锚点区间读取
- 新增安全扫描模块：编译前自动拦截危险代码
- 上下文管理重构：三段式结构 + 智能尾部截取，缓存命中率大幅提升
- 前端文件树预加载优化，展开更流畅

### v3.x — 多语言编译支持，前端优化，锚点体系完善
- 支持 Java/C++/Python/Node.js/HTML 编译运行
- 新增 `find_references` / `find_callers` / `find_callees` 调用链分析
- 前端侧边栏文件树、配置弹窗、项目归档
- 锚点体系：`insert_at_anchor` / `delete_between_anchors`

### v2.x — 核心 Agent 工作流
- 多轮迭代 + 工具调度循环
- 锚点索引管理
- 基础文件操作

### v1.x — 初始版本
- DeepSeek API 集成
- 命令行入口

---

## 技术栈

| 层面 | 技术 |
|------|------|
| 语言 | Java 21 |
| 构建 | Maven 3.x |
| API 调用 | OkHttp 4.12 |
| JSON 解析 | Jackson 2.17 |
| 日志 | Logback |
| AI 模型 | DeepSeek V4 (Flash / Pro) |
| 前端 | 纯 HTML + JS + marked.js |
| 代码分析 | javac Tree API (jdk.compiler) |
| 进程管理 | ProcessBuilder + 智能执行器 |
