<!-- @anchor: project_intro -->
<!-- 项目总览：本地单机 AI 编码工作台的定位、设计理念与整体架构 -->

# agentic-workflow · 本地 AI 编码工作台

## 一句话定位

`agentic-workflow` 是一个**本地单机运行**的 AI 编码工作台：把 DeepSeek 大模型包装成一名"全栈开发工程师" Agent，让它在受限沙箱目录内自主读写代码、编译运行、维护锚点索引，并通过一套自托管的 HTTP + SSE 服务，用浏览器（桌面端 / 手机端）驱动与观察整个过程。

它不是通用聊天界面，而是**围绕"锚点（anchor）"与"会话（session）"两个原创概念构建的工程化 Agent 外壳**：大模型只负责决策，文件操作、上下文管理、并发放、安全扫描、能力暴露全部由后端代码统一裁决。

---

<!-- @anchor: project_design -->
<!-- 设计理念：贯穿全项目的跨文件约定（锚点、双区上下文、会话、沙箱、控制权） -->
## 设计理念（贯穿全项目的约定）

1. **锚点优先（Anchor-first）**
   源码里用 `@anchor: id` 注释标记功能块。Agent 读代码用"按锚点读区间"，改代码用"在锚点处插入 / 删除区间"，避免全量读写大文件。锚点同时被索引成两份产物：`.anchors.json`（id + 行号 + 预览，用于定位）与 `.project_index.json`（id + 行号 + 职责描述 + 所属符号，用于理解）。改文件后索引按"脏文件"批量刷新，保证行号始终最新。

2. **双区上下文（Base + Working）**
   每个会话的对话历史被拆成两区：**不可变基础区**（SystemPrompt、所有用户消息、历次任务摘要、文档变更日志）与**易失工作区**（当前任务内的多轮消息）。任务结束只把一段"任务摘要"并入基础区，工作区清空——用一个恒定大小的摘要换取跨轮次的记忆，从而让 token 成本随会话推进保持收敛。

3. **会话为一等公民**
   Agent 不再是一次性执行器：`Main` 的生命周期从属于一个 `Session`。会话持有自己的上下文、元数据、运行状态与日志订阅者，可创建 / 切换 / 重命名 / 落盘 / 从磁盘恢复；HTTP 服务重启后会话仍可从 `./sessions` 载入。

4. **沙箱 + 最小权限**
   所有文件读写都被 `PathUtils` 限制在 `./sandbox` 内，拒绝 `..` 与盘符；编译运行的外部进程工作目录必须在沙箱内，并重定向其 `TMPDIR/HOME` 等环境变量。写操作（write/delete/compile/build_anchor）被额外约束在当前会话绑定的"工作项目"内（公共临时区 `tmp` 除外）。

5. **零框架、单 jar、纯 JDK**
   后端只依赖 JDK 内置的 `com.sun.net.httpserver` + OkHttp（调大模型）+ Jackson（JSON）+ Logback（日志），不含 Spring 等重框架。打包为 fat jar，`mainClass` 为 HTTP 服务入口；`launcher` 是同一 jar 内的另一个 `main`，负责按需拉起/停止主服务。

6. **多端 + 单一控制权**
   桌面端与手机端共用同一后端。设备通过显式头 `X-Client-Device`（UA 兜底）声明身份；写路由受"当前控制端"锁保护，避免两端同时操作产生冲突，控制权可随时切换。

---

<!-- @anchor: project_runtime -->
<!-- 运行模型：两个 main 进程（launcher 守护 + HTTP 主服务）、绑定地址与退出码约定 -->
## 进程与运行模型

- **主服务**：`HttpServerMain.main`。启动时校验 `DEEPSEEK_API_KEY`、装载全局配置、创建运行时目录、初始化 `SessionManager`，随后注册全部路由并启动。默认监听 `8080`。
  - 绑定地址由环境变量 `AGENT_BIND` 决定；未设置时自动探测 Tailscale（`100.64.0.0/10`）地址，探测不到回退 `127.0.0.1`。据此打开浏览器首页或扫码页。
  - 带 `--daemon` 参数时进入"跟随父进程"模式：读取 stdin 直到 EOF 即退出，避免 launcher 被杀后留下孤儿进程。
- **守护启动器**：`LauncherMain.main`。常驻 `8081`，暴露 `/start`、`/stop`、`/status` 三个接口与一个按钮首页，用 `ProcessBuilder` 以同 jar 的 `--daemon` 方式拉起主服务，并轮询其 `/status` 判定就绪。
- **退出码约定**：`10` 无/无效 API Key，`42` 用户主动清除 API Key，`43` 请求重启，`12` 端口占用启动失败。前端保存配置或清 Key 后调用对应接口，进程自行退出，由 launcher / 外部脚本决定是否重启。

---

<!-- @anchor: project_backend -->
<!-- 后端分层：core / session / tools / parser / security / model / http / launcher 的职责划分 -->
## 后端分层

后端包名统一为 `com.myagent.workflow`，按职责分为八层：

- **core（编排与上下文）**：`Main` 是 Agent 主循环——组装请求、调用 DeepSeek、分发工具调用、累计用量；`ContextManager` 实现双区上下文与快照；`SystemPrompt` 集中存放交给模型的全部行为规范；`AgentConfig` + `ConfigEditor` + `EnvDetector` 负责配置模型、请求覆盖与本机工具链自动探测；`UsageTracker` 计费，`HistoryRecorder` 落盘原始 API 日志。
- **session（会话）**：`Session` 聚合上下文、元数据（`SessionMeta`）、状态（`SessionState`）与日志多播；`SessionManager` 是内存容器 + 磁盘协调者，用信号量把并发限制为全局串行（N=1）；`SessionStorage` 只做"存 / 取"，负责 `./sessions` 下的 meta / jsonl / 索引 / 用量文件；`SessionUsage` 记录会话级累计用量。
- **tools（Agent 能力）**：`ToolDefinitions` 声明暴露给模型的工具 schema，`ToolExecutor` 按工具名校验路径与工作项目后分发。底层能力拆成 `FileOperator`（沙箱读写/列目录）、`Compiler`（各语言编译运行）、`CodeSearcher`（全文/引用/调用链检索）、锚点族（`AnchorManager`/`AnchorIndex`/`AnchorQuery`/`AnchorScanner`/`AnchorFormatter`）与 `CallGraphAnalyzer`。
- **parser（结构解析）**：`StructureParserRegistry` 按扩展名把文件分发给语言解析器（Java 走 javac AST、失败降级正则；Python/C++/JS/HTML/CSS 为缩进或正则解析），`GenericParser` 兜底；`FileStructureFormatter` 把解析结构渲染成紧凑文本。产物模型在 `model` 包。
- **security（安全扫描）**：`SecurityScanner` 在编译前对单文件/目录按规则匹配，`RuleRegistry` 内置"命令执行"与"路径穿越"两条 ERROR 级规则，`WhitelistFilter` 放行无害 import，并带"未变更文件跳过"缓存。
- **model**：解析产物与锚点位置的数据载体（record / POJO）。
- **http（服务层）**：`HttpServerMain` 是入口与路由表；`GuardedHandler` 是设备保护包装器；`handlers` 包内每个 handler 对应一个端点；`HandlerUtils` 提供查询串/JSON 公共工具；`LogFileWriter` 把每轮运行日志写入 `./HistoryOutput/{sessionId}/`。
- **launcher**：守护启动器（见上）。

---

<!-- @anchor: project_task_lifecycle -->
<!-- 一次任务的完整生命周期：从 /run 到 SSE 收尾、用量落库与索引刷新 -->
## 一次任务的生命周期

1. 前端 `POST /run`（携带 `prompt`、可选 `sessionId`、`maxIterations`）。`RunHandler` 完成预检：清 Key 检查、CORS 预检、参数校验。
2. 获取或新建会话；检查"该会话是否已在跑"（是则 409）与"全局是否已有任务"（信号量获取失败则 409）。
3. 建立 SSE 响应，先发一条 `{"type":"session",...}` 告知前端会话号，并启动保活 ping 线程；在后台线程里 `new Main(session).run(...)`。
4. 主循环逐轮：`ContextManager.buildMessages()` → `sendAndReceive` 调 DeepSeek → 记录 usage → 若有 `tool_calls` 则逐条 `ToolExecutor.dispatch` 执行并把结果作为 `tool` 消息回灌，一轮结束批量刷新锚点脏文件；无 `tool_calls` 即任务完成。
5. `finally` 阶段（best-effort）：生成本轮用量增量、把任务摘要并入基础区、刷新沙箱内各项目索引、落盘并压缩原始日志、打印统计，最后 `markIdle` 并释放信号量。
6. SSE 侧：无论成败都追加会话级用量并推 `{"type":"usage",...}`，随后发 `[完成]`/`[错误]` 与 `[结束]`，落盘会话、清理消费者与连接。

模型可调用的工具由 `ToolDefinitions` 与 `ToolExecutor.dispatch` 一一对应，包括：文件类（list_directory / read_file / write_file / delete_file / get_file_structure）、锚点类（read_between_anchors / insert_at_anchor / delete_between_anchors / build_anchor_index / describe_anchors）、检索类（search_text / find_references / find_callers / find_callees）与执行类（compile_and_run）。

---

<!-- @anchor: project_frontend -->
<!-- 前端架构：无构建的模块化静态页；桌面端 modules/ 与移动端 mobile/ 双入口 -->
## 前端架构

前端是**无打包器**的原生静态资源（`resources/static`），由后端 `StaticHandler` 直接吐出；各脚本以普通 `<script>` 顺序加载、通过全局变量与函数协作。

- **桌面端**：`index.html` + `style.css`，逻辑按职能拆进 `modules/`（DOM/状态常量、SSE 运行、状态轮询、会话列表与历史、文件树、配置、归档/上传/建项目、心跳、控制权、日志渲染、话术、用量面板等）。初始化与事件绑定集中在 `events.js`。
- **移动端**：`mobile/index.html` + `mobile.css`，`mobile-*.js` 各自对应核心、会话、运行、文件树、状态、控制权等模块，UI 为顶栏 + 抽屉式会话/文件面板。移动入口会自动给所有请求打上设备标识。
- **辅助页**：`qr.html`（手机扫码访问 + 一键把控制权切回桌面）、`md.html`（Markdown 预览）、`scan.html`/`scan.js`（大文件扫描与导出）。
- **与后端的契约**：运行走 `POST /run` 的 SSE 流，只有 `data:` 行有意义，换行以 `\n` 转义；特殊事件 `{"type":"session"}`、`{"type":"usage"}` 与标记行 `[完成]/[错误]/[结束]` 由前端单独识别。观看其他会话的实时日志走只读 `GET /stream`。心跳 `POST /heartbeat` 续活，`GET /status` 轮询任务状态。
- **本地持久化**：`localStorage` 保存提示词历史、配置面板设置、当前会话号与自定义话术，键名与后端默认配置保持同步（改配置后调用 `/restart` 生效）。

---

<!-- @anchor: project_data_dirs -->
<!-- 运行时目录：sessions 持久化、HistoryOutput 日志、sandbox 工作区、TestProjects 归档 -->
## 关键数据目录（运行时约定）

- `./sandbox`：Agent 的受约束工作区；每个子目录是一个"项目"，也是会话可选的工作项目。`sandbox/tmp` 是跨项目可写的公共临时区。
- `./sessions`：会话持久化。顶层 `index.json` 汇总全部会话元数据；每个会话一个子目录，内含 `meta.json`、`immutable_base.jsonl`、`volatile_working.jsonl`、`usage.json`。
- `./HistoryOutput`：运行期产物。`history.jsonl` 记录全局用户请求；`{sessionId}/<时间戳>.log` 是每轮运行的日志；原始 API 请求/响应日志缓存后压缩为 `.gz`。
- `./TestProjects`：项目归档区（按版本目录组织），与 `./temp` 一同由主服务启动时自动创建。
- 各项目内：`.anchors.json` 与 `.project_index.json` 是锚点/结构索引；`.agent_entry.json` 记录最近一次成功运行的入口（供前端"▶ 运行"按钮）。

---

<!-- @anchor: project_security -->
<!-- 安全模型：沙箱边界、路径与工作项目校验、编译前扫描、前端工具白名单 -->
## 安全模型

- **路径边界**：所有文件路径经 `PathUtils.safeResolve` 归一化并强制落在沙箱内；`..`、绝对盘符、"`.` 开头的隐藏路径"被拒绝；`UPDATE.md` 不允许整体读取或删除。
- **工作项目限定**：写类工具的目标必须落在会话绑定的工作项目（或公共 `tmp`）内，越界即返回错误。
- **编译前扫描**：`compile_and_run` 在真正编译前调用 `SecurityScanner`，命中"系统命令调用""路径穿越"等高危模式即拦截；可在配置面板关闭。
- **进程安全**：外部进程的工作目录必须在沙箱内，且强制重定向临时目录与用户主目录；带编译/运行超时、stdin 阻塞检测与进程树清理。
- **前端工具白名单**：`/tool` 端点只放行只读或幂等的少量工具（build_anchor_index / describe_anchors / get_file_structure / list_directory / read_between_anchors），写/删/执行类工具只对模型开放。
- **控制权**：写路由由 `GuardedHandler` 按"当前控制端"加锁，非控制端返回 403；读路由与 `/control/*` 始终放行。

---

<!-- @anchor: project_build_run -->
<!-- 构建与运行：Maven 打包 fat jar、编译参数、环境变量与启动方式 -->
## 构建与运行

- **技术栈**：Java 21，Maven 构建，`maven-assembly-plugin` 打成 `*-jar-with-dependencies.jar`（主类为 HTTP 服务入口）。编译期需 `jdk.compiler` 模块与其若干 `--add-exports`（供 Java 结构解析器使用 javac AST）。
- **必需环境变量**：`DEEPSEEK_API_KEY`（否则主服务以退出码 10 退出）。
- **可选环境变量**：`AGENT_BIND`（绑定地址，如 Tailscale IP）、`AGENT_CONFIG`（配置文件路径）。
- **配置文件**：`agent-config.properties`。查找顺序为环境变量指定 → 工作目录 → classpath；均缺失时由 `EnvDetector` 扫描本机生成，再兜底内置默认值。前端配置面板写入浏览器本地存储并在运行时随请求下发，覆盖后端默认值。
- **启动方式**：直接 `java -jar` 主服务，或经 `LauncherMain` 守护（HTTP + 按钮页）按需启停。当前工作目录应设为主服务根目录，以保证 `./sessions`、`./sandbox` 等落点正确。
