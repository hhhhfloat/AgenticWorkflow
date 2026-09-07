# PROJECT.md — agentic-workflow（Agent 工作流编排器）

仓库 `workflow` 为一个标准的单一 Maven 树（Java 21），由两个相互独立的部分组成：

| 部分 | 位置 | 说明 |
| --- | --- | --- |
| **主程序** | `src/main` | 基于 DeepSeek API 的 Agent 工作流编排器，含 HTTP Web 服务（8080）与可编程/CLI 入口 |
| **测试程序** | `src/test/.../testtool` | JavaFX 桌面 A/B 对照工具，用于验证「压缩模式」开关对长任务的影响（仅在测试作用域构建） |

---

## 一、主程序（agentic-workflow）

### 1.1 概览

| 项 | 说明 |
| --- | --- |
| 组织 / 构件 | `com.myagent` / `agentic-workflow`（1.0-SNAPSHOT） |
| 语言 / 构建 | Java 21 / Maven（assembly 插件打 fat-jar） |
| 关键依赖 | OkHttp 4.12（DeepSeek API）、Jackson 2.17（JSON）、Logback 1.5.6 |
| HTTP 入口 | `com.myagent.workflow.http.HttpServerMain`（绑定 `127.0.0.1:8080`） |
| 可编程 / CLI 入口 | `com.myagent.workflow.core.Main`（`new Main(AgentConfig)` + `run(prompt, maxIterations)`） |
| 压缩总开关 | `AgentConfig.enableCompression`（默认开启；前端字段 `compressionEnabled`，后端兼容旧键 `enableCompression`） |
| 运行时目录 | `./sandbox`、`./TestProjects`、`./HistoryOutput`、`./temp`（启动时自动创建） |
| 环境变量 | `DEEPSEEK_API_KEY`（缺失或无效时退出码 10） |
| 编译现状 | 61 个 Java 源文件，`mvn compile` 通过 ✅ |

### 1.2 目录结构（src/main）

```
java/com/myagent/workflow/
├── core/      (6)  AgentConfig / ConfigEditor / SystemPrompt / ContextManager / Compressor / Main
├── http/      (12) HttpServerMain(含内部 handler) + LogFileWriter + utils/HandlerUtils + handlers/(9)
├── model/     (6)  AnchorLocation / AnchorSummary / ClassDefinition / MethodDefinition / FieldDefinition / FileStructure
├── parser/    (10) StructureParserRegistry + StructureParser + Java/Python/JS/C++/HTML/CSS/Generic 解析器 + FileStructureFormatter
├── security/  (16) SecurityScanner + SecurityConfig + filters/(WhitelistFilter, ContextAwareFilter)
│                    + parsers/(CodeParser, Java/Python/C++ 语言解析器) + rules/(RuleRegistry 与两条规则)
│                    + ScanResult / Violation / CodeLine / Severity
└── tools/     (11) ToolDefinitions / ToolExecutor / Compiler / FileOperator / PathUtils
                     + CodeSearcher / TextSearcher / ReferenceFinder / CallGraphAnalyzer / SearchFileFilter / AnchorManager

resources/
├── logback.xml
└── static/                # Web 前端
    ├── index.html / style.css / tips.js / marked.min.js
    └── modules/           # 14 个模块 JS：archive config events heartbeat history log openFolder quote runner settings sidebar sse status tree
```

### 1.3 数据模型

| 模型 | 说明 |
| --- | --- |
| `AgentConfig`（record，core） | 全部运行参数：apiKey / model / 编译器路径 / `enableCompression` / `checkpointMinInterval` / `checkpointMaxInterval` 等常量与 `buildDefaultConfig()` |
| `model/` 6 个 record | 锚点（`AnchorLocation`/`AnchorSummary`）与结构解析产物（`ClassDefinition`/`MethodDefinition`/`FieldDefinition`/`FileStructure`） |
| security 模型 | `ScanResult`（扫描结果）、`Violation`、`CodeLine`、`Severity` |

### 1.4 核心功能

- **Agent 编排**（core/Main）：请求模型 → 解析 `tool_calls` → 分发执行 → 回填结果的多轮循环，受 `maxIterations`/`stop()` 控制；提供 `setLogConsumer`、`setIterationListener` 程序化回调与成本统计。
- **上下文管理与压缩开关（端到端）**：前端设置 → `config.js buildRunConfig()` 携带 `config.compressionEnabled` → `/run`、`/runProject` → `ConfigEditor.resolveCompressionEnabled`（兼容旧键）→ `AgentConfig.enableCompression`。开启时在 `SystemPrompt.get(true)` 注入压缩模式双段提示词、`ToolDefinitions.build(true)` 追加注册 `request_checkpoint`、`ContextManager(..., compressionEnabled)` 启用自适应检查点（`roundsSinceLastCheckpoint` + MIN/MAX 间隔）。检查点采用「Agent 自压缩」：`requestCheckpoint(phaseSummary, nextPlan)` 仅缓冲请求，下一轮由 Agent 依压缩模式提示词自行产出 `PROJECT_STATE_SNAPSHOT` 摘要；`Compressor` 仅负责文档压缩 `compressProjectMd`（>3000 字符）。会话历史增量落盘，文档变更经 `flushPendingChanges` 汇集。
- **工具层**（tools/11 类）：`ToolDefinitions.build(boolean)` 提供 17 个基础工具（list/write/compile-run/get-structure/read/read-between/delete/search/build-anchor/list-anchors/insert/delete-between/find-references/find-callers/find-callees/switch-model/query-history），压缩开启时追加 `request_checkpoint`；`ToolExecutor` 分发；`Compiler` 自动识别 HTML/Maven/C++/Python/Node/Java 并执行（超时 + stdin 阻塞检测）；`AnchorManager` 维护 `.anchors.json`；`FileOperator`/`PathUtils` 保证沙箱内安全读写。
- **安全扫描**（security/16 类）：单例 `SecurityScanner` 递归扫描 + 修改缓存；`WhitelistFilter`/`ContextAwareFilter` 上下文感知过滤；按语言解析有效代码行后以 `CommandExecutionRule`/`FilePathRule` 判定，输出 `ScanResult`/`Violation`。
- **HTTP 服务**：REST 接口 `/run`（SSE 流式）、`/stop`、`/heartbeat`、`/projects`、`/browse`、`/archive`、`/upload`、`/createProject`、`/openFolder`、`/config`、`/clear-api-key`、`/restart`、`/project-meta`、`/runProject`、`/status`；静态 `/` 由 StaticHandler 提供；外部目录 `/TestProjects`、沙箱 `/sandbox` 由 ExternalFileHandler 挂载。心跳监控：120 秒无心跳自动停止 Agent（每 5 秒检测一次）。退出码约定：10 无/无效 API Key，42 清除 API Key，43 配置变更重启，12 端口被占用/重复启动。
- **Web 前端**（static/）：文件树、项目运行/归档/上传/新建、SSE 流式输出（marked 渲染）、设置弹窗（含「启用压缩模式」）、心跳断线重连与目录变更轮询、历史记录。

### 1.5 构建与运行

```bash
# 主程序编译（61 个源文件）
mvn compile

# 启动 HTTP 服务（需先设置 API Key）
export DEEPSEEK_API_KEY=sk-xxx
mvn exec:java                                   # → com.myagent.workflow.http.HttpServerMain
# 或打包后运行：
mvn package
java -jar target/agentic-workflow-1.0-SNAPSHOT-jar-with-dependencies.jar
```

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

- **TestController**：`TestCallback` 接口回传日志 / 启停 / 逐轮数据 / 结果；`executeSingleTest` 基于 `ConfigEditor.buildDefault()` 以 `compressionEnabled` 覆写 `AgentConfig`，`new Main(testConfig)` 后重定向 stdout、挂迭代监听 → `IterationData` 写入 `DataStore` 并实时回调 UI；结束后 `ResultCollector.extractFromLog` 正则提取汇总为 `TestResult`。
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
- 锚点命名遵循「模块_功能」；主程序工具类、`Main`、`HttpServerMain`、前端 static 及文档侧均含锚点。
- 本文件测试程序部分已设锚点：`test_program_start` / `test_program_purpose` / `test_program_structure` / `test_program_model` / `test_program_export` / `test_program_build` / `test_program_end`，便于对该节做定点更新。
- 变更与迭代细节见 `UPDATE.md`（含 `update_log_start` / `update_log_end` 锚点）。

---
*本文档为项目简要介绍；目录/功能若随迭代变化，请同步更新并记录于 UPDATE.md。*
