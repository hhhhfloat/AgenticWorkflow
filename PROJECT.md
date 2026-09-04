# PROJECT.md — agentic-workflow（Agent 工作流编排器）

一个基于 **DeepSeek API** 的 Agent 工作流编排系统：接收自然语言需求，驱动大模型多轮对话，调度内置工具（文件操作、编译运行、代码搜索、锚点管理、安全扫描等）完成任务；同时通过 **HTTP 服务（8080 端口）** 提供 Web 操作界面。仓库为标准单一 Maven 树（`src/main` + `src/test`），`src/test` 下另含一套用于 A/B 对比「压缩开关」效果的 JavaFX 测试工具 **testtool**（独立于主构建）。

---

## 一、项目概览

| 项 | 说明 |
| --- | --- |
| 项目名 | agentic-workflow（目录名 workflow） |
| 组织 | `com.myagent` |
| 语言 / 构建 | Java 21 / Maven（fat-jar 打包，assembly 插件） |
| HTTP 服务入口 | `com.myagent.workflow.http.HttpServerMain`（默认 8080 端口） |
| 程序化 / CLI 入口 | `com.myagent.workflow.core.Main`（`main` / `new Main(AgentConfig)` 多轮驱动） |
| 关键依赖 | OkHttp（DeepSeek API）、Jackson（JSON）、Logback（日志） |
| 运行时目录 | `./sandbox`、`./TestProjects`、`./HistoryOutput`、`./temp`（自动创建） |
| 环境变量 | `DEEPSEEK_API_KEY`（必需，HTTP 服务缺失时退出码 10） |
| 压缩总开关 | `AgentConfig.enableCompression`（默认 true，HTTP 端与前端一致可配） |
| 编译现状 | 主树 61 个源文件 `mvn compile` ✅；testtool 需自行提供 JavaFX classpath（见 §6） |

---

## 二、目录结构

```
workflow/
├── pom.xml                              # Maven 构建（默认编译 src/main；无 test/JavaFX 依赖）
├── .agent_entry.json / .anchors.json    # 运行入口记录 / 锚点索引
├── PROJECT.md / UPDATE.md               # 项目文档 / 变更记录
└── src/
    ├── main/
    │   ├── java/com/myagent/workflow/
    │   │   ├── core/      (6)   AgentConfig / ConfigEditor / SystemPrompt / ContextManager / Compressor / Main
    │   │   ├── http/      (12)  HttpServerMain + LogFileWriter + utils/HandlerUtils + handlers/(9)
    │   │   ├── model/     (6)   锚点 / 类 / 方法 / 字段 / 文件结构等 record 模型
    │   │   ├── parser/    (10)  结构解析器（Registry + Java/Python/JS/C++/HTML/CSS/Generic 等）
    │   │   ├── security/  (16)  安全扫描器 + 规则 + 语言级过滤/解析器
    │   │   └── tools/     (11)  工具定义与执行、编译、搜索、锚点、文件操作
    │   └── resources/
    │       ├── logback.xml
    │       └── static/                # Web 前端（index.html + style.css + 14 个模块 JS）
    └── test/
        ├── java/com/myagent/workflow/testtool/   (18) JavaFX A/B 对照工具（独立构建）
        └── resources/                 # logback.xml + test-report.css
```

## 三、数据模型

### 3.1 主业务模型（record，model/ 包）

| 类 | 用途 |
| --- | --- |
| `AnchorLocation` / `AnchorSummary` | 锚点全局定位 / 文件内摘要（id、行号、预览） |
| `ClassDefinition` / `MethodDefinition` / `FieldDefinition` | 类、方法、字段的结构化描述 |
| `FileStructure` | 单个文件的语言、包、导入、类、函数、字段、锚点聚合 |
| `ScanResult` / `Violation` / `CodeLine` / `Severity` | 安全扫描结果模型（security/） |

### 3.2 编排配置 / 上下文模型（core/）

| 类 | 说明 |
| --- | --- |
| `AgentConfig` | record：运行参数 + `enableCompression`（默认 true）+ 模型/目录/编译器常量 + 锚点索引文件名；`buildDefaultConfig()` 取系统常量与环境变量 |
| `ContextManager` | 「不可变基础区 + 易变工作区」消息管理；检查点自适应节奏（`MIN/MAX_INTERVAL`）、`requestCheckpoint` 缓冲、会话历史（`historyFile/historyOffsets`）、文档变更收集（`addPendingDocChange/flushPendingChanges`）、峰值/非峰值成本计价（`recordUsage/calculateCost/isPeakHour`） |
| `SystemPrompt` | `get(boolean)`：压缩开启 → 「默认 + 压缩模式」双段提示词（含 `request_checkpoint` 用法与 `PROJECT_STATE_SNAPSHOT` 模板）；关闭 → 精简单段 |
| `Compressor` | 职责收敛：仅文档压缩 `compressProjectMd`（>3000 字符才压缩，保留目录树与 `@anchor`，输出约 800–1500 字） |
| `Main` | 多轮编排：`run(prompt, maxIterations)` / `stop()` / `setLogConsumer` / `setIterationListener` / `iterationListenerIsNull` / `printStats` / `checkApiKey` |

### 3.3 testtool 模型（src/test/.../testtool/model）

| 类 | 说明 |
| --- | --- |
| `TestConfig` | 单组测试配置（prompt、迭代上限、`compressionEnabled`、label） |
| `TestResult` / `IterationData` | 单组汇总（token、成本、缓存命中率、压缩次数）/ 单轮过程数据 |
| `DataStore` / `ChartData` | 历史持久化 + CSV 导出 + 通用图表数据 |

## 四、核心功能

### 4.1 Agent 编排（core/Main）
- 多轮工具调用循环：请求模型 → 解析 `tool_calls` → 分发执行 → 回填结果，直至模型不再请求工具（受 `maxIterations` / `stop()` 控制）。
- 程序化回调：`setLogConsumer`、`setIterationListener`（每轮回传 token / 成本）、`Main(AgentConfig)` + `run(prompt, maxIterations)`。
- 成本统计与 API Key 校验（401 判无效，网络异常时放行）。

### 4.2 上下文管理与压缩开关（端到端）
- **配置链路**：前端设置弹窗（`settings.js`，localStorage 默认开）→ `config.js buildRunConfig()` 携带 `config.compressionEnabled` → `/run`、`/runProject` → 后端 `ConfigEditor.resolveCompressionEnabled`（**优先读 `compressionEnabled`，兼容旧字段 `enableCompression`**，另兼容 `minInterval/maxInterval` 别名）→ `AgentConfig.enableCompression`。
- **三处注入**：`SystemPrompt.get(boolean)`（双段/单段提示词）；`ToolDefinitions.build(boolean)`（开启时追加注册 `request_checkpoint`）；`ContextManager(..., compressionEnabled)`（false 时跳过自动检查点触发）。
- **Agent 自压缩模型**：`requestCheckpoint(phaseSummary, nextPlan)` 仅缓冲压缩请求并追加本轮消息，下一轮由 Agent 依据压缩模式提示词自行产出 `PROJECT_STATE_SNAPSHOT` 摘要（非服务端代为压缩）。
- **自适应检查点**：`roundsSinceLastCheckpoint` + `MIN/MAX_INTERVAL`；`appendToWorking` 达到阈值且开启压缩时自动触发检查点。
- **历史与文档变更**：会话历史增量落盘；TODO / UPDATE / PROJECT 文档变更收集后经 `Compressor.compressProjectMd` 压缩产出归档文本。

### 4.3 工具层（tools，11 类）
- `ToolDefinitions.build(boolean)`：17 个基础工具（list/write/compile-run/get-structure/read/read-between/delete/search/build-anchor/list-anchors/insert/delete-between/find-references/find-callers/find-callees/switch-model/query-history）；开启压缩时追加 `request_checkpoint`。
- `ToolExecutor`：按工具名分发执行。
- `Compiler`：自动检测类型（HTML / Maven / C++ / Python / Node / Java），进程执行支持总超时、stdin 阻塞检测、沙箱目录校验。
- 代码搜索：`CodeSearcher`（门面）+ `TextSearcher` / `ReferenceFinder` / `CallGraphAnalyzer` / `SearchFileFilter`。
- `AnchorManager`：`.anchors.json` 索引构建、按 ID 增删查、`findAnchor(projectPath, anchorId)`。
- `FileOperator` / `PathUtils`：沙箱内安全文件读写与路径解析。

### 4.4 安全扫描（security，16 类）
单例 `SecurityScanner`（目录递归 + 修改缓存）；`CommandExecutionRule` / `FilePathRule` 规则；`WhitelistFilter` / `ContextAwareFilter` 上下文感知过滤；按 Java / Python / C++ 语言解析有效代码行后扫描，输出 `ScanResult` / `Violation`。

### 4.5 HTTP 服务（http）
- 默认端口 8080；接口：`/run`（SSE 流式）、`/stop`、`/heartbeat`、`/projects`、`/browse`、`/archive`、`/upload`、`/createProject`、`/openFolder`、`/config`、`/clear-api-key`、`/restart`、`/project-meta`、`/runProject`、`/status`、静态 `/`、外部目录 `/sandbox`、`/TestProjects`。
- 心跳监控线程：120 秒无心跳自动停止 Agent；5 秒检测一次。
- `/run`、`/runProject`：`ConfigEditor.buildFromRequest` 读取 `root.config.*` → `new Main(runConfig)`。

### 4.6 Web 前端（static）
`index.html` + `style.css` + 14 个模块 JS（archive / config / events / heartbeat / history / log / openFolder / quote / runner / settings / sidebar / sse / status / tree）。SSE 流式输出（marked 渲染）、文件树、项目运行/归档/上传/新建、设置弹窗（含「启用压缩模式」）、心跳断连重连、历史记录。

### 4.7 测试工具 testtool（src/test/.../testtool）
> JavaFX 桌面程序，独立于主构建（root pom 未声明 JavaFX，需自行提供 classpath 编译）。

- **目的**：A/B 对照压缩开关对长任务的影响——同一 prompt 分别以 `enableCompression=false / true` 运行，对比迭代轮数、token、缓存命中率、成本、压缩次数。
- **构成**：入口 `TestRunnerFX`（JavaFX，A/B 双状态面板 + 双折线图 + 历史结果表）；编排 `TestExecutionManager`；UI `MainView` + controller/（`TestController` / `ChartController` / `LogController`）+ model/(6) + view/chart/(`ChartFactory` 与 4 种图实体)。
- **机制**：`TestController.executeSingleTest` 用 `AgentConfig(..., compressionEnabled)` 构造 `Main`，重定向 stdout 捕获输出、迭代监听回传 → `IterationData` 入 `DataStore`；`ResultCollector.extractFromLog` 正则提取统计 → `TestResult`。

## 五、锚点体系

- 代码中以单行 `@anchor` 注释标记关键位置，供工具链精确定位/读写；索引文件 `.anchors.json` 由 `build_anchor_index` 重建。
- 当前规模：**153 锚点 / 30 文件**，覆盖 tools（11 类全部）、`Main`、`HttpServerMain`、前端 static（index.html + 14 模块 + style.css）等；`archive.js` / `runner.js` / `status.js` 各含一处同名模块锚点，索引中以 `_2` 后缀区分。
- 文档侧 `UPDATE.md` 含 `update_log_start` / `update_log_end` 锚点。

## 六、构建与运行

```bash
# 主树编译（61 个源文件）
mvn compile

# 启动 HTTP 服务（需先设置 API Key）
export DEEPSEEK_API_KEY=sk-xxx
mvn exec:java        # 或打包后 java -jar agentic-workflow-1.0-SNAPSHOT-jar-with-dependencies.jar
```

testtool 编译示例（Windows，按实际 JavaFX 路径调整）：

```bash
mvn dependency:copy-dependencies   # 或自行准备 JavaFX SDK
javac --module-path <javafx-lib> --add-modules javafx.controls,javafx.swing \
      -cp target/classes -d target/test-classes \
      $(find src/test/java -name '*.java')
```

## 七、注意事项

1. **运行必需**：未设置 `DEEPSEEK_API_KEY` 时 HTTP 服务以退出码 10 退出；`Main` CLI 以退出码 1 退出。
2. **testtool**：需外部 JavaFX classpath，不随 `mvn compile / package` 构建；`HttpServerMain` 另有 `clear-api-key`（42）与 `restart`（43）等退出码约定。
3. 压缩总开关默认开启；HTTP 端通过前端字段 `compressionEnabled`（兼容 `enableCompression`）即可关闭。

---
*变更与迭代细节见 UPDATE.md。*
