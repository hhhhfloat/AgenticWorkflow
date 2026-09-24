# PROJECT.md — agentic-workflow（Agent 工作流编排器）

仓库 `workflow` 为一个标准的单一 Maven 树（Java 21），由两个相互独立的部分组成：

| 部分 | 位置 | 说明 |
| --- | --- | --- |
| **主程序** | `src/main` | 基于 DeepSeek API 的 Agent 工作流编排器，含多会话管理、HTTP Web 服务（8080）与可编程 / CLI 入口 |
| **测试程序** | `src/test/.../testtool` | JavaFX 桌面 A/B 对照工具，用于验证「压缩模式」开关对长任务的影响（仅在测试作用域构建） |

---

<!-- @anchor: main_program_start -->

## 一、主程序（agentic-workflow）

### 1.1 概览

| 项 | 说明 |
| --- | --- |
| 组织 / 构件 | `com.myagent` / `agentic-workflow`（1.0-SNAPSHOT） |
| 语言 / 构建 | Java 21 / Maven（assembly 插件打 fat-jar） |
| 关键依赖 | OkHttp 4.12（DeepSeek API）、Jackson 2.17（JSON）、Logback 1.5.6 |
| HTTP 入口 | `com.myagent.workflow.http.HttpServerMain`（绑定 `127.0.0.1:8080`） |
| 可编程 / CLI 入口 | `core.Main`（首选 `new Main(Session)`；兼容 `new Main(AgentConfig)` 内部自建临时会话） |
| 会话容器 | `session.SessionManager`（HTTP 侧全局单例，信号量限流，当前 N=1 全局串行） |
| 压缩总开关 | `AgentConfig.enableCompression`（前端字段 `compressionEnabled`，后端兼容旧键 `enableCompression`） |
| 运行时目录 | `./sandbox`、`./TestProjects`、`./HistoryOutput`、`./temp`、`./sessions`（启动时自动创建） |
| 配置来源 | `agent-config.properties`（缺失时由 `core.EnvDetector` 自动探测生成）+ 环境变量 `DEEPSEEK_API_KEY`（缺失或无效时退出码 10） |
| 编译现状 | 主树 `src/main` 共 **82 个 Java 源文件** |

### 1.2 目录结构（src/main）

```
java/com/myagent/workflow/
├── core/      (9)  AgentConfig / ConfigEditor / EnvDetector / SystemPrompt / ContextManager
│                   / Compressor / HistoryRecorder / UsageTracker / Main
├── http/      (24) HttpServerMain + LogFileWriter + utils/HandlerUtils + handlers/(21)
├── model/     (6)  AnchorLocation / AnchorSummary / ClassDefinition / MethodDefinition
│                   / FieldDefinition / FileStructure
├── parser/    (10) StructureParserRegistry + StructureParser
│                   + Java/Python/JS/C++/HTML/CSS/Generic 解析器 + FileStructureFormatter
├── security/  (16) SecurityScanner + SecurityConfig + ScanResult / Violation / CodeLine / Severity
│                   + filters/(WhitelistFilter, ContextAwareFilter)
│                   + parsers/(CodeParser, Java/Python/C++ 语言解析器)
│                   + rules/(Rule, RuleRegistry, CommandExecutionRule, FilePathRule)
├── session/   (5)  Session / SessionManager / SessionStorage / SessionMeta / SessionState
└── tools/     (12) ToolDefinitions / ToolExecutor / Compiler / FileOperator / PathUtils
                    + CodeSearcher / TextSearcher / ReferenceFinder / CallGraphAnalyzer
                    + SearchFileFilter / AnchorManager / AnchorIndex

resources/
├── logback.xml
└── static/                # Web 前端
    ├── index.html / style.css / tips.js / marked.min.js
    └── modules/           # 17 个模块 JS：archive config events heartbeat history log openFolder
                            #   quote runner sessionList sessionState settings sidebar sse status tree usageStore
```

`http/handlers/`（21 个独立 handler，v5.0 由内部类拆出）：
`ArchiveHandler` `BrowseHandler` `ClearApiKeyHandler` `ConfigHandler` `CreateProjectHandler`
`ExternalFileHandler` `HeartbeatHandler` `OpenFolderHandler` `ProjectMetaHandler` `ProjectsHandler`
`RestartHandler` `RunHandler` `RunProjectHandler` `SessionCloseHandler` `SessionCreateHandler`
`SessionHistoryHandler` `SessionListHandler` `StaticHandler` `StatusHandler` `StopHandler` `UploadHandler`。

### 1.3 数据模型

| 模型 | 说明 |
| --- | --- |
| `AgentConfig`（core，class） | 全部运行参数：apiKey / model / 各语言编译器路径 / `enableSecurityScan` / `enableCompression` / `checkpointMinInterval` / `checkpointMaxInterval`；`buildDefaultConfig()` + 从 `agent-config.properties` 加载（缺配置时 `EnvDetector` 生成） |
| `model/` 6 个 record | 锚点（`AnchorLocation`/`AnchorSummary`）与结构解析产物（`ClassDefinition`/`MethodDefinition`/`FieldDefinition`/`FileStructure`） |
| session 模型 | `SessionMeta`（record：sessionId/title/createdAt/lastActiveAt/messageCount/state）、`SessionState`（IDLE/RUNNING/PAUSED/ARCHIVED 常量）、`Session`（运行态聚合）、`SessionManager` / `SessionStorage` |
| core 支撑 | `HistoryRecorder`（会话历史增量落盘 + 原始 request/response 日志缓冲与 gzip 压缩）、`UsageTracker`（Token 计价与统计） |
| security 模型 | `ScanResult`（扫描结果）、`Violation`、`CodeLine`、`Severity` |

### 1.4 核心功能

- **Agent 编排**（core/Main）：绑定到 `Session`（`new Main(Session)`；兼容 `new Main(AgentConfig)`）。`run(userRequest, maxIterations)` 请求模型 → 解析 `tool_calls` → 分发执行 → 回填结果的多轮循环：首轮 `init`、后续 `appendUserMessage`，任务结束 `mergeSummaryToBase` 归并摘要并清空工作区。提供 `IterationListener`/`setIterationListener`、`TaskUsage` 记录 + `getLastTaskUsage()`、`stop()`/`checkStop()`、`getSession()`；日志经 `session::log` 转发。
- **会话管理**（session/5 类）：`SessionManager` 内存映射（`ConcurrentHashMap`）+ `SessionStorage` 落盘协调；`create`/`get`/`switchTo`/`save`/`close`/`listAll`/`listActive`/`exists`/`shutdown`，用 `Semaphore`（`MAX_CONCURRENT_TASKS=1`）保证全局串行。`Session` 持有 meta/state/`ContextManager`/runningAgent/runningThread/心跳，提供 `prepareUserMessage`（首轮/多轮分支）、`markRunning`/`markIdle`/`stopTask`、`heartbeat`。`SessionStorage` 落盘 `./sessions`：`index.json` + `{sessionId}/meta.json` + `immutable_base.jsonl` + `volatile_working.jsonl`。
- **上下文、历史与计价**（core/ContextManager + HistoryRecorder + UsageTracker）：`ContextManager` 维护 `immutableBase`/`volatileWorking`/`pendingDocChanges`，提供 `requestCheckpoint`（缓冲）、`mergeSummaryToBase`/`extractSnapshotFromRound`、`restore*` 快照、`flushPendingChanges`，并把历史持久化委托 `HistoryRecorder`（`appendRawLog`/`flushRawLog`/`compressRawLog`）、把计费委托 `UsageTracker`（`recordUsage`/`printStats`）。
- **压缩开关（端到端）**：前端设置 → `config.js buildRunConfig()` 携带 `config.compressionEnabled` → `/run`、`/runProject` → `ConfigEditor.resolveCompressionEnabled`（兼容旧键）→ `AgentConfig.enableCompression`。开启时 `SystemPrompt.get(true)` 注入压缩模式双段提示词、`ToolDefinitions.build(true)` 追加注册 `request_checkpoint`、`ContextManager(..., compressionEnabled)` 启用自适应检查点（`roundsSinceLastCheckpoint` + MIN/MAX 间隔）。检查点采用「Agent 自压缩」：`requestCheckpoint(phaseSummary, nextPlan)` 仅缓冲请求，下一轮由 Agent 生成 `PROJECT_STATE_SNAPSHOT` 摘要；`Compressor.compressProjectMd`（>3000 字符）负责文档压缩。
- **环境探测**（core/EnvDetector）：可独立运行（`main`），探测 JDK / Maven / Python / Node / MinGW / MSVC 路径并写出 `agent-config.properties`；`AgentConfig` 在缺少配置文件时自动调用。
- **使用量计价**（core/UsageTracker）：按 FLASH / PRO × 命中 / 未命中 × 峰值 / 非峰值 单价计算成本，`record`/`formatStats`/`calculateCost`/`isPeakHour`。
- **工具层**（tools/12 类）：`ToolDefinitions.build(boolean)` 提供 **16 个基础工具**（list_directory / write_file / compile_and_run / get_file_structure / read_file / read_between_anchors / delete_file / search_text / build_anchor_index / list_anchors / insert_at_anchor / delete_between_anchors / find_references / find_callers / find_callees / switch_model），压缩开启时追加 `request_checkpoint`（共 17）；`ToolExecutor` 分发（`compile_and_run` 先经安全扫描再交 `Compiler`）；`Compiler` 自动识别 HTML/Maven/C++/Python/Node/Java（超时 + stdin 阻塞检测）；`AnchorManager` 为门面，`AnchorIndex` 维护 `.anchors.json`（含旧索引 `migrateOldIndex` 迁移、`rebuild`/`list`/`find`/`findGlobally`）；`CodeSearcher` 门面转发至 `SearchFileFilter`/`TextSearcher`/`ReferenceFinder`/`CallGraphAnalyzer`；`FileOperator`/`PathUtils` 保证沙箱内安全读写。
- **安全扫描**（security/16 类）：单例 `SecurityScanner` 递归扫描 + 修改缓存；`WhitelistFilter`/`ContextAwareFilter` 上下文感知过滤；按语言解析有效代码行后以 `CommandExecutionRule`/`FilePathRule` 判定，输出 `ScanResult`/`Violation`。
- **HTTP 服务**：路由注册于 `HttpServerMain.registerRoutes`，拆分至 `handlers/`（21）。分五组——会话 `/session/create` `/session/list` `/session/close` `/session/history`；任务 `/run`（SSE 流式）`/stop` `/heartbeat` `/status`；项目 `/runProject` `/project-meta`；系统 `/restart` `/clear-api-key` `/config`；项目/文件管理 `/projects` `/browse` `/archive` `/upload` `/createProject` `/openFolder`；静态 `/` 由 `StaticHandler` 提供，外部目录 `/TestProjects`、`/sandbox` 由 `ExternalFileHandler` 挂载。心跳监控：120 秒无心跳自动停止对应会话任务（每 5 秒检测一次）。退出码约定：10 无/无效 API Key，42 清除 API Key，43 配置变更重启，12 端口被占用/重复启动。
- **Web 前端**（static/）：文件树、项目运行/归档/上传/新建、SSE 流式输出（marked 渲染）、设置弹窗（含「启用压缩模式」）、心跳断线重连与目录变更轮询、历史记录；新增**会话列表**（`sessionList.js`）、**当前会话状态**（`sessionState.js`，localStorage `currentSessionId`）、**用量面板**（`usageStore.js`，按会话累计 Token/成本并渲染 `#usagePanel`）。

### 1.5 构建与运行

```bash
# 主程序编译（82 个源文件）
mvn compile

# 生成/刷新本机环境配置（可选，独立运行）
java -cp target/classes com.myagent.workflow.core.EnvDetector agent-config.properties

# 启动 HTTP 服务（需先设置 API Key）
export DEEPSEEK_API_KEY=sk-xxx
mvn exec:java                                   # → com.myagent.workflow.http.HttpServerMain
# 或打包后运行：
mvn package
java -jar target/agentic-workflow-1.0-SNAPSHOT-jar-with-dependencies.jar
```

> 注意：`compile_and_run` 在 `enableSecurityScan` 开启时会先做安全扫描；`EnvDetector`/`Compiler` 等源码含 `ProcessBuilder` 与绝对工具链路径，会触发内置规则拦截，故对整树编译验证可能被安全扫描挡下（属工具层行为，非编译错误）。

<!-- @anchor: main_program_end -->

---

## 二、测试程序（testtool）— A/B 压缩效果对比测试

<!-- @anchor: test_program_start -->

### 2.1 定位与用途

<!-- @anchor: test_program_purpose -->

位于 `src/test/java/com/myagent/workflow/testtool/`，是独立于主构建的 **JavaFX 桌面程序**（主类 `TestRunnerFX`）。pom.xml 以 `test` 作用域声明 JavaFX（controls/fxml，21），并提供 exec 目标 `test-runner`；因此它不进入主程序 jar，仅在测试作用域参与编译与运行。

用途：对**同一 prompt** 分别以「启用压缩（A）/ 禁用压缩（B）」运行主程序 `core.Main`，按相同最大迭代数（默认 30）顺序执行 A→B（B 运行前自动清空沙箱项目目录，保证空白起点），实时对比并输出报告。默认任务与项目名见 `core/PromptContents`（游戏合集页面，项目 `test-programming`）。

### 2.2 目录结构与组成

<!-- @anchor: test_program_structure -->

```
testtool/ (27 Java)
├── TestRunnerFX.java       # JavaFX 入口：组装 UI、调度 A/B、清理/归档、触发档案导出
├── MainView.java           # JavaFX 视图：prompt / 项目名 / 最大迭代输入、A/B 状态面板、双折线图、结果表
├── controller/  (3)        # TestController（进程内驱动 Main，回调贯穿 UI）/ ChartController / LogController
├── core/        (1)        # PromptContents（默认任务与项目名）
├── model/      (13)        # 见 2.3
├── view/chart/  (6)        # Chart 抽象 + ChartFactory + Bar/Pie/Line/ComparisonChartEntity（渲染为 JavaFX Node）
└── export/      (2)        # PortfolioWriter（档案落盘）/ ReportRenderer（Markdown 报告渲染）
```

- **TestController**：`TestCallback` 接口回传日志 / 启停 / 逐轮数据 / 结果；`executeSingleTest` 基于 `ConfigEditor.buildDefault()` 以 `compressionEnabled` 覆写 `AgentConfig`，构造 `new Session(testConfig)` 并 `session.setLogConsumer(...)` 重定向输出，再 `new Main(session)` 后挂迭代监听 → `IterationData` 写入 `DataStore` 并实时回调 UI；结束后 `ResultCollector.extractFromLog` 正则提取汇总为 `TestResult`。
- **图表**：逐轮以两条折线实时绘制「累计 / 本轮缓存命中率」；两轮完成后用堆叠柱状图对比 Token 构成（缓存命中/未命中/输出）并叠加成本与命中率信息表。
- **日志**：`LogController` 缓冲全部输出并写入 `./testPortfolio/logs/test_<项目>_<时间戳>.log`。

### 2.3 数据模型（model/，13 个 record/类）

<!-- @anchor: test_program_model -->

| 模型 | 说明 |
| --- | --- |
| `TestConfig` / `TestResult` / `IterationData` | 单组测试参数（prompt、maxIterations、compressionEnabled、label、min/max 间隔）/ 单组汇总（轮数、Token、成本、命中率、压缩次数）/ 单轮过程数据 |
| `DataStore` | 历史与逐轮数据内存存储 + 累计命中率计算 + CSV 导出 / 目录归档 |
| `ResultCollector` | 从捕获日志中正则提取统计字段与项目名 |
| `ChartData` | 图表通用数据（轴标签、系列名、取值表） |
| `ThresholdConfig` / `RunMeta` | 压缩阈值快照（enabled/min/max）/ 运行元数据（runId、时间、prompt、model、maxIterations） |
| `Manifest` / `ManifestGroupSummary` | 档案清单 JSON 及其分组摘要 |
| `ReportData` / `ReportGroupSummary` / `Verdict` | Markdown 报告数据模型、分组摘要与判定结论（betterGroup + reason） |

### 2.4 测试档案（export/）

<!-- @anchor: test_program_export -->

每次完整 A/B 结束后 `PortfolioWriter.write(...)` 在 `./testPortfolio/run_<yyyyMMdd_HHmmss>/` 下生成：

```
0_manifest.json                      # 清单：元数据 + 两组摘要 + 判定结论（含压缩触发轮次）
raw_data/group_a_iterations.csv      # 启用压缩逐轮 Token/命中率/成本
raw_data/group_b_iterations.csv      # 禁用压缩逐轮明细
configs/thresholds.json              # 阈值与共享配置快照
1_report.md                          # Markdown 报告（ReportRenderer 渲染）
```

`determineVerdict` 依据迭代轮数与成本自动给出倾向性结论（A 优 / B 优 / 混合，供人工判断）。

### 2.5 构建与运行

<!-- @anchor: test_program_build -->

```bash
# 编译测试程序（随测试作用域，需要 JavaFX 依赖可解析）
mvn test-compile

# 启动 JavaFX 桌面工具
mvn exec:java@test-runner            # → com.myagent.workflow.testtool.TestRunnerFX
```

> 提示：testtool 为 GUI 程序，运行需本地窗口环境与 JavaFX 模块（`javafx.controls,javafx.fxml`）；本工具仅负责其代码组织与文档，不替代主程序编译验证。

<!-- @anchor: test_program_end -->

---

## 三、锚点体系与文档约定

- 代码与 Markdown 文档均可使用单行 `@anchor` 注释标记关键位置（Java/JS/C++：`// @anchor: 名称`；CSS：`/* @anchor: 名称 */`；HTML/Markdown：`<!-- @anchor: 名称 -->`）；索引文件 `.anchors.json` 由 `build_anchor_index` 重建，供 `read_between_anchors` / `insert_at_anchor` / `delete_between_anchors` 精准读写。
- 锚点命名遵循「模块_功能」；主程序工具类、`Main`、`HttpServerMain`、`session`、前端 static 及文档侧均含锚点。
- 本文件主程序部分设锚点：`main_program_start` / `main_program_end`；测试程序部分设锚点：`test_program_start` / `test_program_purpose` / `test_program_structure` / `test_program_model` / `test_program_export` / `test_program_build` / `test_program_end`，便于定点更新。
- 变更与迭代细节见 `UPDATE.md`（含 `update_log_start` / `update_log_end` 锚点）。

---
*本文档为项目简要介绍；目录/功能若随迭代变化，请同步更新并记录于 UPDATE.md。*
