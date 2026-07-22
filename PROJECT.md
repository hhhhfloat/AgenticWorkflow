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
    │   │   ├── security/                            # 🔒 安全扫描模块（v1.1 新增）
    │   │   │   ├── CodeLine.java                    # 代码行记录（内容+行号）
    │   │   │   ├── ScanResult.java                  # 扫描结果记录（passed + violations + summary）
    │   │   │   ├── SecurityConfig.java              # 安全配置（规则启用/禁用）
    │   │   │   ├── SecurityScanner.java             # 安全扫描器（单例，核心扫描引擎）
    │   │   │   ├── Severity.java                    # 严重级别枚举（ERROR / WARNING / INFO）
    │   │   │   ├── Violation.java                   # 违规记录（文件路径+行号+规则ID+建议）
    │   │   │   ├── filters/                         # 过滤器
    │   │   │   │   ├── ContextAwareFilter.java      # 上下文感知过滤器（预留）
    │   │   │   │   └── WhitelistFilter.java         # 白名单过滤器（放行 import/include 语句）
    │   │   │   ├── parsers/                         # 语言解析器
    │   │   │   │   ├── CodeParser.java              # 解析器接口
    │   │   │   │   ├── CppParser.java               # C++ 解析器（继承 JavaParser）
    │   │   │   │   ├── JavaParser.java              # Java/JS 解析器（去除注释和空行）
    │   │   │   │   └── PythonParser.java            # Python 解析器（# 注释处理）
    │   │   │   └── rules/                           # 安全规则
    │   │   │       ├── Rule.java                    # 规则接口（id + pattern + suggestion + severity）
    │   │   │       ├── RuleRegistry.java            # 规则注册中心（单例，统管所有规则）
    │   │   │       ├── CommandExecutionRule.java    # 系统命令执行检测规则
    │   │   │       └── FilePathRule.java            # 路径穿越检测规则
    │   │   └── tools/                               # 工具实现层
    │   │       ├── ToolDefinitions.java             # DeepSeek Function Calling 工具定义
    │   │       ├── ToolExecutor.java                # 工具调度执行器（集成安全扫描）
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
│  PathUtils                              │
└──────────────┬──────────────────────────┘
               │ 🛡️ 编译前拦截
┌──────────────▼──────────────────────────┐
│         安全扫描层 (security/)           │  ← v1.1 新增
│  SecurityScanner → RuleRegistry         │
│  → 规则匹配 → WhitelistFilter → 拦截   │
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
| `compile_and_run` | 编译并运行（支持 html/java/maven/cpp/python/node），**编译前自动触发安全扫描** | Compiler + SecurityScanner |
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

## 八、安全扫描模块（security/）🛡️

<!-- @anchor: security_overview -->

### 8.1 概述

安全扫描模块是 v1.1 新增的独立安全层，在 `compile_and_run` 工具执行编译前自动触发。它通过正则规则匹配 + 白名单过滤机制，拦截 Agent 生成的潜在危险代码，确保沙箱安全。

**设计原则**：
- **单例模式**：`SecurityScanner` 和 `RuleRegistry` 均采用单例，全局统一规则配置
- **可扩展**：新增规则只需实现 `Rule` 接口并注册到 `RuleRegistry`
- **语言感知**：解析器根据文件语言去除注释后再匹配，避免注释中的安全关键词误报
- **白名单放行**：`import os`、`#include <cstdlib>` 等合法导入语句被白名单过滤
- **缓存加速**：目录递归扫描时，未变更文件直接跳过（基于文件修改时间缓存）

### 8.2 扫描流程

```
compile_and_run 调用
       │
       ▼
SecurityScanner.scan(filePath) / scanDirectory(dirPath)
       │
       ├── 1. 文件存在性检查
       ├── 2. 读取源文件内容
       ├── 3. 根据扩展名选择解析器（JavaParser / PythonParser / CppParser）
       ├── 4. 解析器去除注释和空行 → 提取有效代码行 List<CodeLine>
       ├── 5. 逐行遍历：
       │      ├── WhitelistFilter.isWhitelisted() → 放行 import/include
       │      └── RuleRegistry.getRules() → 正则匹配 → 命中则记录 Violation
       └── 6. 返回 ScanResult（passed=true/false + violations + summary）
```

### 8.3 安全规则清单

<!-- @anchor: security_rules_table -->

| 规则 ID | 严重级别 | 检测内容 | 正则模式 |
|---------|:------:|------|------|
| `COMMAND_EXECUTION` | ERROR | 系统命令调用 | `Runtime.exec`、`ProcessBuilder`、`os.system`、`subprocess.Popen/call/check_call`、`child_process.exec/spawn/execSync`、`system(`、`popen(`、`powershell -`、`cmd /c` |
| `FILE_PATH_TRAVERSAL` | ERROR | 路径穿越 | `../`、`..\`、盘符如 `C:\` |

### 8.4 解析器矩阵

<!-- @anchor: security_parsers_table -->

| 解析器 | 适用语言 | 注释处理 |
|--------|------|------|
| `JavaParser` | Java、JavaScript、Node.js | 去除 `//` 单行注释 + `/* */` 多行注释 |
| `PythonParser` | Python | 去除 `#` 单行注释 |
| `CppParser` | C++ | 继承 JavaParser（C++ 注释规则一致） |

### 8.5 过滤器

<!-- @anchor: security_filters -->

| 过滤器 | 职责 | 状态 |
|--------|------|:--:|
| `WhitelistFilter` | 放行以 `import`、`from`、`#include` 开头的合法导入语句 | ✅ 活跃 |
| `ContextAwareFilter` | 上下文感知过滤（预留扩展） | 🔜 预留 |

### 8.6 数据模型

<!-- @anchor: security_data_models -->

| 类/记录 | 类型 | 说明 |
|---------|------|------|
| `CodeLine` | record | `(String content, int lineNumber)` — 有效代码行 |
| `ScanResult` | record | `(boolean passed, List<Violation> violations, String summary)` — 扫描结果，含 `getFormattedReport()` |
| `Violation` | record | `(String filePath, int lineNumber, String ruleId, String matchedText, Severity severity, String suggestion)` |
| `Severity` | enum | `ERROR`（必须拦截）、`WARNING`（记录不拦截，预留）、`INFO`（仅信息） |
| `Rule` | interface | `getId()` / `getPattern()` / `getSuggestion()` / `isEnabled()` / `getSeverity()` |
| `SecurityConfig` | class | 规则启用/禁用配置（当前全部启用，预留外部配置加载） |

### 8.7 ToolExecutor 集成点

<!-- @anchor: security_integration -->

`ToolExecutor.compileAndRun()` 方法中，在执行实际编译前插入了安全检查：

```java
// 获取 SecurityScanner 单例
SecurityScanner scanner = SecurityScanner.getInstance();

// 目录模式：递归扫描所有源文件
if (Files.isDirectory(filePath)) {
    scanResult = scanner.scanDirectory(filePath);
} else if (Files.isRegularFile(filePath)) {
    scanResult = scanner.scan(filePath);
}

// 未通过则返回格式化报告并拦截编译
if (!scanResult.passed()) {
    return "❌ 安全扫描拦截:\n" + scanResult.getFormattedReport();
}
```

**拦截效果**：当 Agent 生成的代码包含 `Runtime.getRuntime().exec(...)`、`os.system(...)`、路径 `../` 等危险模式时，`compile_and_run` 将直接返回安全拦截报告，不会执行编译。

---

## 九、前端模块职责

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

## 十、关键锚点

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
| `security_overview` | PROJECT.md | 安全模块概述 |
| `security_rules_table` | PROJECT.md | 安全规则清单 |
| `security_parsers_table` | PROJECT.md | 解析器矩阵 |
| `security_filters` | PROJECT.md | 过滤器说明 |
| `security_data_models` | PROJECT.md | 安全模块数据模型 |
| `security_integration` | PROJECT.md | ToolExecutor 集成说明 |

---

## 十一、退出码约定

| 退出码 | 含义 |
|:------:|------|
| 10 | API Key 未设置或无效 |
| 12 | 检测到已有进程占用端口 |
| 42 | 用户清除 API Key 后重启 |
| 43 | 用户修改配置后重启 |

---

## 十二、更新日志

### v1.1 — 安全扫描模块

<!-- @anchor: update_v1_1 -->

**新增 `security/` 模块**（共 16 个文件）：
- `SecurityScanner`：核心扫描引擎（单例），支持单文件扫描和目录递归扫描，含文件修改时间缓存以加速重复扫描
- `RuleRegistry`：规则注册中心（单例），统一管理所有安全规则
- `CommandExecutionRule`：检测系统命令调用（`Runtime.exec`、`ProcessBuilder`、`os.system`、`subprocess`、`child_process` 等）
- `FilePathRule`：检测路径穿越（`../`、`..\`、盘符如 `C:\`）
- `CodeParser` 接口 + `JavaParser` / `PythonParser` / `CppParser`：按语言去除注释后提取有效代码行
- `WhitelistFilter`：白名单放行 `import` / `from` / `#include` 等合法导入语句
- `ContextAwareFilter`：预留的上下文感知过滤器
- 数据模型：`CodeLine`、`ScanResult`、`Violation`、`Severity`、`SecurityConfig`、`Rule`

**修改 `ToolExecutor.java`**：
- `compileAndRun()` 方法中，在编译前插入 `SecurityScanner` 安全扫描
- 支持单文件模式（`scanner.scan(filePath)`）和目录模式（`scanner.scanDirectory(filePath)`）
- 扫描未通过时返回格式化拦截报告，阻止编译执行

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
