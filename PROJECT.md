# PROJECT.md — agentic-workflow

## 项目定位
基于 DeepSeek API 的 Agent 工作流编排器（Java 21 / Maven，构件 `com.myagent:agentic-workflow`）。把大模型「思考 → 调用工具 → 回填结果」的多轮循环封装为服务，对外提供 HTTP Web 工作台（默认 `127.0.0.1:8080`）与 CLI 入口，供开发者用自然语言驱动本地代码生成、编译运行与文件管理。

## 整体架构
- **core**：Agent 内核。`Main` 驱动多轮 API 请求与工具分发；`ContextManager` 维护「不可变基础区 + 易失工作区」并做轮次摘要归并；`AgentConfig`/`ConfigEditor`/`EnvDetector` 管理参数与环境探测；`HistoryRecorder`/`UsageTracker` 负责历史落盘与计价。
- **tools + parser + model**：能力层。`ToolExecutor` 按名分发工具并做沙箱校验，`FileOperator`/`PathUtils` 安全读写，`CodeSearcher` 检索，`Compiler` 编译运行，`AnchorManager`/`AnchorIndex` 维护锚点索引；`parser` 解析各语言文件结构，`model` 承载结构与锚点数据模型。
- **security**：编译前安全扫描。按语言解析出有效代码行后，以命令执行/文件路径规则判定，并做白名单与上下文过滤。
- **session**：会话容器。`SessionManager`（内存映射 + 信号量限流）管理 `Session` 生命周期，`SessionStorage` 落盘 `./sessions`。
- **http + static**：Web 层。`HttpServerMain` 注册路由，handlers 处理会话/任务/项目/系统请求；前端为纯静态页面（SSE 流式渲染）。

关键数据流：浏览器 → handler → `Session`/`SessionManager` → `Main` → DeepSeek API ⇄ `ToolExecutor` → 沙箱文件系统；每轮结束刷新锚点/项目索引。
依赖方向：http → session → core → tools →（parser / model / security），下层不反向依赖上层。

## 关键决策
- **工具即能力边界**：Agent 对环境的全部交互收敛为 `ToolDefinitions` 声明、`ToolExecutor` 按名分发的函数工具，模型只面对稳定契约而非直接文件/进程操作；所有路径参数统一经 `checkPath` → `PathUtils.safeResolve` 做沙箱校验，穿越直接拒绝。工具分四组：
  - **文件目录**：`list_directory` 列目录树（可递归）、`read_file` 读文本（5000 字上限）、`write_file` 整文件写入/覆盖并标脏、`delete_file` 删文件。
  - **锚点编辑**：`list_anchors` 列锚点、`describe_anchors` 列锚点+职责描述、`build_anchor_index` 重建索引、`read_between_anchors` 按区间精读、`insert_at_anchor` 在锚点前/后插入、`delete_between_anchors` 删区间（配合 insert 即区间替换）。
  - **检索理解**：`get_file_structure` 取类/方法/字段/锚点结构、`search_text` 正则全文检索（≤30 条）、`find_references` 查符号引用、`find_callers`/`find_callees` 查调用链（可递归）。
  - **执行**：`compile_and_run` 按 html/java/maven/cpp/python/node 编译运行，可仅编译不运行。
- **锚点即坐标**：以单行注释 `// @anchor: 模块_功能` 作为 Agent 精准读写的坐标（`_start`/`_end` 括出区间），替代脆弱的行号定位；`insert`/`delete`/`read_between` 均基于锚点 ID 完成。
- **描述索引**：锚点下一行紧贴的注释即其 description（隔空行视为无描述），由 `AnchorIndex.scanAnchorsFromFile → extractDesc` 提取。写作与索引分离——`AnchorManager` 在 `write_file` 后**标脏**、每轮结束统一 flush，再由 `AnchorIndex` 扫描生成两份索引：`.anchors.json`（id + line + preview，供坐标定位）与 `.project_index.json`（id + line + desc + symbol，供**按职责检索**，`symbol` 由 `parser` 的 AST 解析补出，取锚点所在/最近方法）。工具 `describe_anchors` 即消费后者。重名锚点自动加 `_2` 后缀。
- **全局串行**（Semaphore=1）：任务一次只跑一个，换取实现简单与资源可控，放弃并发调度。
- **轮次摘要归并**：每轮结束把 Agent 最终回复作为【任务摘要】并入不可变基础区并清空工作区，不做模型侧压缩/检查点。

## 规范约定
- Maven 单模块树，仅 `src/main`（无测试作用域）。
- 包名 `com.myagent.workflow.<模块>`，handler 一文件一类。
- 源码/文档统一用单行 `@anchor: 模块_功能` 标记，锚点下一行须为职责描述；`/** */` 等块注释内的 `@anchor` 不被索引（仅识别单行 `//`、`/* */`、`<!-- -->`、`#`）。
- 锚点命名 `模块_功能`，全项目唯一；`_start`/`_end` 仅作区间标记。

## 已知限制与坑
- **描述必须紧邻**：锚点与描述之间不能有空行，否则该锚点 description 为空。
- **安全扫描误伤**：源码含 `ProcessBuilder`/绝对工具链路径，整树 `compile_and_run` 可能被自身规则拦下。
- **退出码**：10=无/无效 API Key，42=清除 API Key，43=配置变更重启，12=端口占用/重复启动。
- **任务串行**：并发请求会排队（N=1）。

## 启动方式
- 环境：本机需具备目标语言工具链（JDK/Maven/Python/Node/MinGW/MSVC）；配置缺失时 `AgentConfig` 自动调用 `EnvDetector` 探测生成 `agent-config.properties`。
- 必需变量：`DEEPSEEK_API_KEY`（缺失则退出码 10）。
- 启动：`mvn exec:java` 启动 HTTP 服务（127.0.0.1:8080）；或 `mvn package` 后运行 `target/agentic-workflow-1.0-SNAPSHOT-jar-with-dependencies.jar`（或者通过./start.bat一键运行）。
