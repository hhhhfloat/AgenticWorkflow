# Agentic Workflow — 项目文档

## 一、项目概述

**Agentic Workflow** 是一个本地 AI Agent 工作台。它通过 DeepSeek API 驱动多轮 Function Calling 对话，在沙箱环境中自主完成代码编写、编译、运行等全流程操作。项目内嵌 HTTP 服务器，提供 Web 界面用于交互式操控 Agent。

- **GroupId**: `com.myagent`
- **ArtifactId**: `agentic-workflow`
- **版本**: `1.0-SNAPSHOT`
- **Java 版本**: 21
- **入口类**: `com.myagent.workflow.http.HttpServerMain`

---

## 二、目录结构

```
workflow/
├── pom.xml                                          # Maven 构建文件
├── PROJECT.md                                       # 本文件
└── src/
    ├── main/
    │   ├── java/com/myagent/workflow/
    │   │   ├── core/                                # 核心层
    │   │   │   ├── Main.java                        # Agent 工作流编排器
    │   │   │   ├── AgentConfig.java                 # 配置记录（含编译器路径等常量）
    │   │   │   ├── ConfigEditor.java                # 运行时配置合并
    │   │   │   └── SystemPrompt.java                # 系统提示词
    │   │   ├── http/                                # HTTP 服务层
    │   │   │   ├── HttpServerMain.java              # HTTP 服务器入口 + 核心 Handler
    │   │   │   └── Handlers.java                    # 业务处理器集合
    │   │   ├── model/                               # 数据模型
    │   │   │   └── AnchorLocation.java              # 锚点位置 POJO
    │   │   └── tools/                               # 工具实现层
    │   │       ├── ToolDefinitions.java             # DeepSeek Function Calling 工具定义
    │   │       ├── ToolExecutor.java                # 工具调度执行器
    │   │       ├── Compiler.java                    # 多语言编译运行引擎
    │   │       ├── FileOperator.java                # 文件 CRUD 操作
    │   │       ├── AnchorManager.java               # 锚点索引管理
    │   │       ├── CodeSearcher.java                # 代码搜索 & 调用链分析
    │   │       └── PathUtils.java                   # 沙箱路径安全解析
    │   └── resources/
    │       ├── logback.xml                          # 日志配置
    │       └── static/                              # Web 前端
    │           ├── index.html                       # 主页面（纯结构，无内联 CSS/JS）
    │           ├── style.css                        # 全部样式
    │           ├── marked.min.js                    # Markdown 渲染库
    │           ├── tips.js                          # 提示词模板
    │           └── modules/                         # JS 模块
    │               ├── config.js                    # DOM 引用 & 状态
    │               ├── quote.js                     # 文案工具
    │               ├── log.js                       # 日志输出渲染
    │               ├── history.js                   # 历史记录管理
    │               ├── heartbeat.js                 # 心跳 & 目录变化检测
    │               ├── tree.js                      # 文件树组件
    │               ├── archive.js                   # 归档/创建/上传
    │               ├── openFolder.js                # 打开系统文件夹
    │               ├── sse.js                       # SSE 流式通信
    │               ├── settings.js                  # 配置弹窗管理
    │               ├── sidebar.js                   # 侧边栏折叠
    │               ├── runner.js                    # 运行控制
    │               └── events.js                    # 事件绑定 & 初始化
    └── test/java/                                   # 测试（预留）

运行时目录（自动创建）:
    ./sandbox/              # Agent 工作沙箱
    ./sandbox/.anchor_index.json  # 锚点索引
    ./TestProjects/         # 归档项目库
    ./HistoryOutput/        # 历史日志
```

---

## 三、架构分层

```
┌─────────────────────────────────────────┐
│              Web 前端 (static/)          │  ← 用户交互界面
│   SSE 流式输出 / 文件树 / 配置弹窗      │
└──────────────┬──────────────────────────┘
               │ HTTP (SSE + REST)
┌──────────────▼──────────────────────────┐
│           HTTP 服务层 (http/)            │  ← com.sun.net.httpserver
│  HttpServerMain + Handlers              │
│  /run /stop /heartbeat /browse /archive │
└──────────────┬──────────────────────────┘
               │
┌──────────────▼──────────────────────────┐
│           核心编排层 (core/)             │
│  Main: 多轮对话 → DeepSeek API          │
│  AgentConfig / ConfigEditor /           │
│  SystemPrompt                           │
└──────────────┬──────────────────────────┘
               │ dispatch
┌──────────────▼──────────────────────────┐
│           工具执行层 (tools/)            │
│  ToolExecutor → Compiler / FileOperator │
│  AnchorManager / CodeSearcher /         │
│  PathUtils                             │
└─────────────────────────────────────────┘
```

---

## 四、数据模型

### 4.1 AgentConfig（record）
| 字段 | 类型 | 说明 |
|------|------|------|
| apiKey | String | DeepSeek API Key |
| model | String | 模型名称（deepseek-v4-pro） |
| autoOpenBrowser | boolean | 编译后是否自动打开浏览器 |
| mavenCommand | String | Maven 命令路径 |
| javaHome | String | JDK 路径 |
| pythonInterpreter | String | Python 解释器路径 |
| nodeInterpreter | String | Node.js 路径 |
| cppCompilerType | String | "msvc" 或 "mingw" |
| msvcCompiler | String | MSVC cl.exe 路径 |
| msvcInclude | String | MSVC INCLUDE 路径 |
| msvcLib | String | MSVC LIB 路径 |
| mingwCompiler | String | MinGW g++.exe 路径 |

### 4.2 AnchorLocation
| 字段 | 类型 | 说明 |
|------|------|------|
| projectPath | String | 项目相对路径 |
| filePath | String | 文件相对路径 |
| line | int | 行号（1-based） |
| id | String | 锚点 ID |
| preview | String | 锚点行预览 |

### 4.3 锚点索引（.anchor_index.json）
```json
{
  "project-path": {
    "relative-file-path": [
      { "id": "anchor_name", "line": 12, "preview": "// @anchor: anchor_name" }
    ]
  }
}
```

### 4.4 项目注册表（.agent_entry.json）
```json
{
  "filename": "index.html",
  "mode": "html"
}
```

---

## 五、HTTP API 清单

| 端点 | 方法 | 说明 |
|------|------|------|
| `/` | GET | 静态首页（index.html） |
| `/run` | POST | SSE 流式执行 Agent |
| `/stop` | POST | 停止正在运行的 Agent |
| `/heartbeat` | POST | 心跳检测 + 目录变化通知 |
| `/projects` | GET | 获取归档项目列表（按版本分组） |
| `/browse?path=` | GET | 浏览目录内容 |
| `/archive` | POST | 归档项目到 TestProjects |
| `/upload` | POST | 上传文件到 sandbox |
| `/createProject` | POST | 创建新项目目录 |
| `/openFolder?path=` | GET | 在系统文件管理器中打开目录 |
| `/config` | GET | 获取当前配置 |
| `/clear-api-key` | POST | 清除 API Key 并重启 |
| `/restart` | POST | 保存配置并重启 |
| `/project-meta?path=` | GET | 查询项目注册表 |
| `/runProject` | POST | 编译运行指定项目 |
| `/sandbox/**` | GET | 沙箱目录静态文件服务 |
| `/TestProjects/**` | GET | 归档目录静态文件服务 |

---

## 六、Agent 可用工具清单

共 14 个工具，全部注册为 DeepSeek Function Calling：

| 工具名 | 功能 | 实现类 |
|--------|------|--------|
| `list_directory` | 列出目录内容 | FileOperator |
| `write_file` | 写入源代码文件 | FileOperator |
| `read_file` | 读取文件内容（上限 5000 字符） | FileOperator |
| `delete_file` | 删除文件 | FileOperator |
| `compile_and_run` | 编译并运行（支持 html/java/maven/cpp/python/node） | Compiler |
| `search_text` | 正则搜索文本 | CodeSearcher |
| `build_anchor_index` | 重建锚点索引 | AnchorManager |
| `list_anchors` | 列出项目锚点 | AnchorManager |
| `insert_at_anchor` | 在锚点处插入代码 | AnchorManager |
| `delete_between_anchors` | 删除两个锚点间代码 | AnchorManager |
| `find_references` | 查找符号引用 | CodeSearcher |
| `find_callers` | 查找函数调用者 | CodeSearcher |
| `find_callees` | 分析函数调用依赖 | CodeSearcher |

---

## 七、编译运行引擎（Compiler）能力矩阵

| 语言/模式 | 编译 | 运行 | 输入阻塞检测 | GUI 检测 |
|-----------|------|------|:--:|:--:|
| HTML | — | ✅ 返回预览 URL | — | — |
| Java（单文件） | javac → classes/ | java -cp | ✅ | — |
| Maven | mvn compile | java -cp 或 javafx:run | ✅ | ✅ (JavaFX) |
| C++ (MSVC) | cl.exe | .exe | ✅ | — |
| C++ (MinGW) | g++.exe | .exe | ✅ | — |
| Python | — | python | ✅ | ✅ (pygame/tkinter/PyQt) |
| Node.js | — | node | ✅ | — |

**智能进程执行器** (`executeProcess`)：
- 总超时检测（30s / 60s）
- 输入阻塞检测（10 秒无输出 → 强制终止并提示）
- 中断信号响应（用户停止）
- 每 2 秒状态日志

---

## 八、前端模块职责

| 模块文件 | 职责 |
|----------|------|
| `config.js` | DOM 元素引用缓存 & 全局状态变量 |
| `quote.js` | 随机励志文案 |
| `log.js` | `appendLog()` / `clearLog()` — Markdown 渲染输出 |
| `history.js` | localStorage 历史记录读写 |
| `heartbeat.js` | 定时心跳 → 检测目录变化 → 自动刷新文件树 |
| `tree.js` | 文件树渲染、展开/折叠、右键操作 |
| `archive.js` | 归档、上传、创建项目 API 调用 |
| `openFolder.js` | 打开系统文件夹 |
| `sse.js` | `startSSE()` / `stopSSE()` — 流式读取 `/run` |
| `settings.js` | 配置弹窗的读写与保存 |
| `sidebar.js` | 侧边栏展开/折叠 |
| `runner.js` | 运行/停止按钮逻辑 |
| `events.js` | 所有事件绑定 & 页面初始化入口 |

---

## 九、关键锚点

| 锚点 ID | 文件 | 说明 |
|---------|------|------|
| `main_class` | Main.java | 主类注释 |
| `main_fields` | Main.java | 成员字段区域 |
| `main_constructor` | Main.java | 构造函数 |
| `main_setLogConsumer` | Main.java | 日志消费者设置 |
| `main_run` | Main.java | 核心 run() 方法 |
| `main_stop` | Main.java | stop() 方法 |
| `main_checkStop` | Main.java | 停止检查 |
| `main_logIf` | Main.java | 条件日志 |
| `main_entry` | Main.java | 命令行入口 |
| `agentConfig_cpp` | AgentConfig.java | C++ 编译器配置区 |
| `agentConfig_python` | AgentConfig.java | Python 解释器配置 |
| `agentConfig_node` | AgentConfig.java | Node.js 配置 |
| `systemPrompt_class` | SystemPrompt.java | 提示词类 |
| `systemPrompt_get` | SystemPrompt.java | get() 方法 |
| `httpserver_class` | HttpServerMain.java | HTTP 服务器类 |
| `httpserver_config` | HttpServerMain.java | 配置常量 |
| `httpserver_state` | HttpServerMain.java | 运行状态 |
| `httpserver_exitCodes` | HttpServerMain.java | 退出码 |
| `httpserver_entry` | HttpServerMain.java | main 入口 |
| `httpserver_runHandler` | HttpServerMain.java | RunHandler |
| `httpserver_stopHandler` | HttpServerMain.java | StopHandler |
| `httpserver_heartbeatHandler` | HttpServerMain.java | HeartbeatHandler |
| `httpserver_heartbeatMonitor` | HttpServerMain.java | 心跳监控线程 |
| `handlers_class` | Handlers.java | 处理器集合类 |
| `handlers_projects` | Handlers.java | ProjectsHandler |
| `handlers_static` | Handlers.java | StaticHandler |
| `handlers_external` | Handlers.java | ExternalFileHandler |
| `anchorLocation_class` | AnchorLocation.java | 模型类 |
| `toolDefinitions_class` | ToolDefinitions.java | 工具定义类 |
| `toolDefinitions_build` | ToolDefinitions.java | build() 方法 |
| `toolExecutor_class` | ToolExecutor.java | 执行器类 |
| `toolExecutor_constructor` | ToolExecutor.java | 构造函数 |
| `toolExecutor_dispatch` | ToolExecutor.java | 调度入口 |
| `compiler_class` | Compiler.java | 编译引擎类 |

---

## 十、退出码约定

| 退出码 | 含义 |
|:------:|------|
| 10 | API Key 未设置或无效 |
| 12 | 检测到已有进程占用端口 |
| 42 | 用户清除 API Key 后重启 |
| 43 | 用户修改配置后重启 |

---

## 十一、更新日志

### v1.0-SNAPSHOT（初始版本）
- 完整的 Agent 工作流编排（多轮 Function Calling）
- HTTP 服务器 + Web 前端（SSE 流式输出）
- 支持 6 种语言/模式的编译运行
- 14 个 Agent 工具（文件操作、编译运行、代码搜索、锚点管理、调用链分析）
- 沙箱路径安全隔离
- 智能进程执行器（超时检测 + 输入阻塞检测）
- 文件树浏览器 + 项目归档 + 文件上传
- 心跳监控 + 前端断连自动停止
- 可配置的编译器路径（弹窗编辑，持久化重启）
- 历史记录（localStorage）
- 日志持久化（HistoryOutput/）

<!-- @anchor: update_log_end -->
