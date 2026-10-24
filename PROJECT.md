<!-- @anchor: project_intro -->
<!-- 项目总览：本地单机 AI 编码工作台的定位、设计理念与整体架构 -->

# agentic-workflow · 本地 AI 编码工作台

## 一句话定位

`agentic-workflow` 是一个**本地单机运行**的 AI 编码工作台：把大模型（默认 DeepSeek）包装成一名"全栈开发工程师" Agent，让它在受限沙箱目录内自主读写代码、编译运行、维护锚点索引，并通过一套自托管的 HTTP + SSE 服务，用浏览器（桌面端 / 手机端）驱动与观察整个过程。

它不是通用聊天界面，而是**围绕"锚点（anchor）"与"会话（session）"两个原创概念构建的工程化 Agent 外壳**：大模型只负责决策，文件操作、上下文管理、并发控制、安全扫描、能力暴露全部由后端代码统一裁决。

---

<!-- @anchor: project_design -->
<!-- 设计理念：贯穿全项目的跨文件约定（锚点、双区上下文、会话、沙箱、控制权） -->
## 设计理念（贯穿全项目的约定）

1. **锚点优先（Anchor-first）**
   源码里用 `@anchor: id` 注释标记功能块，每个源文件首个锚点固定为 `<file_name>_intro`（文件初始介绍）。Agent 读代码用"按锚点读区间"，改代码用"在锚点处插入 / 删除区间"，避免全量读写大文件。锚点被索引成两份产物：`.anchors.json`（id + 行号 + 预览，用于定位）与 `.project_index.json`（id + 行号 + 职责描述 + 所属符号，用于理解）。改文件后索引按"脏文件"批量刷新，保证行号始终最新。**索引新鲜度**由"两份索引文件 mtime 的较小值 ≥ 源侧最大 mtime"判定：一次运行开始时强制重建当前工作项目的索引（未绑定工作项目时遍历各项目、按新鲜度跳过），任务收尾只重建被判为过期的项目，从而避免每轮都全量扫描；读取索引文件时按 mtime 走缓存，避免重复解析。

2. **双区上下文（Base + Working）**
   每个会话的对话历史被拆成两区：**不可变基础区**（SystemPrompt、所有用户消息、历次任务摘要、文档变更日志）与**易失工作区**（当前任务内的多轮消息）。任务结束只把一段"任务摘要"并入基础区，工作区清空——用一个恒定大小的摘要换取跨轮次的记忆，从而让 token 成本随会话推进保持收敛。
   收敛与容错各有补充机制：**按需压缩**——基础区过大时由 `/session/compact` 让模型把较早的历史重写成一段结构化摘要（保留 SystemPrompt 与最近 N 对消息，切点按 user 消息对齐），压缩前的完整基础区先归档到会话目录的 `history-base/`，使压缩成为可回看的单向操作；**崩溃可续**——每轮工具结果并入工作区后，把那一刻完整的 `buildMessages()` 快照连同基础区指纹覆盖写入会话级 recovery 文件，自然完成或用户主动停止即清除、只有真实异常才保留，`/session/resume` 校验指纹后按快照切点续跑，无需重跑已完成的工作。

3. **会话为一等公民**
   Agent 不再是一次性执行器：`Main` 的生命周期从属于一个 `Session`。会话持有自己的上下文、元数据、运行状态与日志订阅者，可创建 / 切换 / 重命名 / 落盘 / 从磁盘恢复；HTTP 服务重启后会话仍可从 `./sessions` 载入。

4. **沙箱 + 最小权限**
   所有文件读写都被 `PathUtils` 限制在 `./sandbox` 内，拒绝 `..` 与盘符；编译运行的外部进程工作目录必须在沙箱内，并重定向其 `TMPDIR/HOME` 等环境变量。写操作由 `PathPolicy` 约束在当前会话绑定的"工作项目"内，公共临时区 `tmp` 除外；锚点写工具（insert_at_anchor / delete_between_anchors）的落点另由 `AnchorManager.checkWorkProjectPermission` 复核，两个裁决点对"公共 tmp / 未绑定工作项目 / 已绑定工作项目"三种场景给出同一结论。**不经会话的手动入口**（`/runProject`、前端 `/tool`）以"手动模式"运行工具（`ToolExecutor.forManualRun`）：不绑定工作项目，因此只保留沙箱边界校验、不施加工作项目限定。请求可覆盖的工具链路径（`mavenCommand`、`javaHome`、各语言解释器）只接受"PATH 命令名或沙箱外的绝对路径"，以断掉"上传 → 指定为解释器 → 执行"的链路。

5. **零框架、单 jar、纯 JDK**
   后端只依赖 JDK 内置的 `com.sun.net.httpserver` + OkHttp（调大模型）+ Jackson（JSON）+ Logback（日志），不含 Spring 等重框架。打包为 fat jar，`mainClass` 为 HTTP 服务入口；`launcher` 是同一 jar 内的另一个 `main`，负责按需拉起/停止主服务。

6. **多端 + 单一控制权**
   桌面端与手机端共用同一后端。设备通过显式头 `X-Client-Device`（UA 兜底）声明身份；写路由受"当前控制端"锁保护（首个访问受保护路由的一端自动成为控制端），避免两端同时操作产生冲突，控制权可随时切换。该锁解决的是"两端互斥"，**不是身份鉴权**——它信任请求方的自述身份。

---

<!-- @anchor: project_runtime -->
<!-- 运行模型：两个 main 进程（launcher 守护 + HTTP 主服务）、绑定地址与退出码约定 -->
## 进程与运行模型

- **主服务**：`HttpServerMain.main`。启动时校验 `DEEPSEEK_API_KEY`、装载全局配置（`ConfigEditor.buildDefault`）、创建运行时目录（`./sandbox`、`./TestProjects`、`./HistoryOutput`、`./temp`、`./sessions`）、初始化 `SessionManager` 并注册 ShutdownHook，随后注册全部路由并启动。默认监听 `8080`。
  - 绑定地址由 `BindAddressResolver` 决定：环境变量 `AGENT_BIND` 优先；未设置时自动探测 Tailscale（`100.64.0.0/10`）地址，探测不到回退 `127.0.0.1`。据此打开浏览器首页或扫码页。
  - 心跳监视线程（`startHeartbeatMonitor`，daemon）：客户端心跳失联只记录日志并留待前端提示，不再停任务；任务运行超过硬超时（30 分钟）才 `stopTask()` + `forceIdle()` 一次性归一化为 IDLE。`main` 退出前调用 `stopHeartbeatMonitor` 置标志并中断该线程，另有 `startParentWatcher` 监视父进程存活。
  - 带 `--daemon` 参数时进入"跟随父进程"模式：读取 stdin 直到 EOF 即退出，避免 launcher 被杀后留下孤儿进程。
- **守护启动器**：`LauncherMain.main`。常驻 `8081`，暴露 `/start`、`/stop`、`/status` 三个接口与一个按钮首页，用 `ProcessBuilder` 以同 jar 的 `--daemon` 方式拉起主服务，并轮询其 `/status` 判定就绪。
- **退出码约定**：`10` 无/无效 API Key，`42` 用户主动清除 API Key，`43` 请求重启，`12` 启动失败（如端口占用 / 线程冲突）。前端保存配置或清 Key 后调用对应接口，进程自行退出，由 launcher / 外部脚本决定是否重启。

---

<!-- @anchor: project_backend -->
<!-- 后端分层：core / session / tools / parser / security / model / http / launcher 的职责划分 -->
## 后端分层

后端包名统一为 `com.myagent.workflow`，按职责分为八层：

- **core（编排与上下文）**：`Main` 是 Agent 主循环——组装请求、调用大模型、分发工具调用（含单轮相同调用去重）、累计用量，并在 `finally` 完成摘要合并、索引刷新、日志落盘与 recovery 去留；新任务 `run` 与恢复 `runResume` 共用同一内部循环，唯一差异是恢复时不再重新准备用户消息（工作区已由外部注入）。`ContextManager` 实现双区上下文、摘要合并、按需压缩与快照恢复，并在基础区变化时提供指纹供续跑校验；`HistoryRecorder` 负责原始 API 日志的缓存 / 刷盘 / 压缩（同步方法，压缩体在同步块内、日志回调移到锁外），并维护会话级 recovery 快照；`SystemPrompt` 集中存放交给模型的全部行为规范与锚点约定；`UsageTracker` 计费，`ToolLogFormatter` 只负责人类可读的工具日志，`ProcessRegistry` 登记所有子进程以便 JVM 退出时统一回收。
- **core.config（配置与环境探测）**：`AgentConfig` 是运行配置 record（工具链字段 + 系统级常量，含默认迭代上限与模型上下文窗口）与默认值兜底；`ConfigEditor` 把前端请求里的覆盖项叠加到默认配置，并对可执行路径做安全校验、拒绝由请求关闭安全扫描；`BindAddressResolver` 解析绑定地址；`config.env` 子包是一个探测器体系——`EnvDetector` 门面遍历若干 `Probe`（Java/Maven/MinGW/MSVC/Node/Python）并配合 `ShellPathResolver`、`WindowsRegistryReader`、`GlobUtil`、`EnvUtil`、`CommandRunner`、`ConfigRenderer`，在本机自动探测工具链路径并生成配置文件。
- **session（会话）**：`Session` 聚合上下文、元数据（`SessionMeta`）、状态（`SessionState`）与日志多播，并持有运行中的 Agent 引用与心跳；日志多播面向 `LogSink` 抽象（`offer` 非阻塞、`close` 幂等），`SseLogSink` 以有界队列 + 独立写线程落 socket，普通日志队列满时丢弃并计数、控制事件队列满时抢占式丢弃最旧一条，使多个观察者互不阻塞、慢连接不拖累 Agent 主线程；`SessionManager` 是内存容器 + 磁盘协调者，用信号量把 `/run`、`/session/resume`、`/session/compact`、`/runProject` 统一限制为全局串行（N=1），`close()` 在停止超时时改为"注册回调、由任务线程 finally 落盘并移除"；`SessionStorage` 只做"存 / 取"，负责 `./sessions` 下的 meta / jsonl / 索引 / 用量文件，并把压缩前的基础区归档到 `history-base/`；`SessionUsage` 记录会话级累计用量。
- **tools（Agent 能力）**：`ToolDefinitions` 声明暴露给模型的工具 schema，`ToolExecutor` 以"工具名 → 处理器"表注册并按工具名校验后分发，同时承担索引刷新策略（运行前强制重建当前项目索引、收尾用 `isProjectFresh` 只重建过期项目、单轮结束 `flushDirty`）；`PathPolicy` 负责工具路径参数与写类工具（write_file / delete_file / compile_and_run / build_anchor_index）的工作项目限定，并以 `manualMode` 让手动入口只做沙箱边界校验；锚点写工具（insert_at_anchor / delete_between_anchors）的落点另由 `AnchorManager.checkWorkProjectPermission` 复核（与 `PathPolicy` 同口径）；`Args` 做类型宽容的参数提取，`ProjectLayout` 是"哪些文件 / 目录该被跳过"的单一真源（编译产物、依赖缓存、隐藏名、自动生成的元数据），并由 `resolveProjectRoot` 统一"项目根"的推导（入口注册表的写读两侧共用）。底层能力拆成：`FileOperator`（沙箱读写/列目录）、`PathUtils`（路径安全解析）、`anchor`（`AnchorManager` 门面 + `AnchorIndex`/`AnchorQuery`/`AnchorScanner`/`AnchorFormatter`，索引文件的读取统一走按 mtime 失效的 `AnchorIndexCache`）、`search`（`CodeSearcher` 门面 + `TextSearcher`/`ReferenceFinder`/`CallGraphAnalyzer`/`SearchFileFilter`）、`runner`（`Compiler` 门面 + `CompileRunner` 编排，及 Java/Python/Node/C++/Maven/Html 各语言运行器与 `ProcessRunner`/`FileStructureRunner`）。
- **parser（结构解析）**：`StructureParserRegistry` 单例按扩展名把文件分发给语言解析器（`lang` 子包：Java 走 javac AST、失败降级正则；Python/C++/JS/HTML/CSS 为缩进或正则解析），`GenericParser` 兜底；`FileStructureFormatter` 把解析结构渲染成紧凑文本。产物模型在 `model` 包。
- **security（安全扫描）**：`SecurityScanner` 单例在编译前对单文件/目录按规则匹配，`RuleRegistry` 内置"命令执行"与"路径穿越"两条 ERROR 级规则（`parsers` 先剥离注释、`filters` 放行无害 import），并带"未变更文件跳过"缓存；`SecurityConfig` 预留规则开关，启用与否只由服务端配置决定。
- **model**：解析产物与锚点位置的数据载体（record / POJO）。
- **http（服务层）**：`HttpServerMain` 是入口与路由表（含心跳监控与父进程存活监视）；`GuardedHandler` 是设备保护包装器；`handlers` 按域分五个子包——`session`（会话 CRUD / 历史 / 日志 / 用量 / 上下文查询 / 压缩 / 恢复）、`run`（运行、恢复、停止、日志流、前端工具调用、手动跑项目，以及被 `/run` 与 `/session/resume` 共用的 `SseTaskSession`）、`project`（项目列表/元信息/浏览/归档/上传/建项目/扫描/开目录）、`staticres`（静态资源与外部目录映射）、`system`（状态、心跳、控制权、配置、重启、退出、清 Key）；`utils.HandlerUtils` 提供查询串/JSON 公共工具；`LogFileWriter` 把每轮运行日志写入 `./HistoryOutput/{sessionId}/`。
- **launcher**：守护启动器（见上）。

---

<!-- @anchor: project_task_lifecycle -->
<!-- 一次任务的完整生命周期：从 /run 到 SSE 收尾、用量落库、recovery 快照与索引刷新 -->
## 一次任务的生命周期

1. 前端 `POST /run`（携带 `prompt`、可选 `sessionId`、`maxIterations` 与运行配置）。`RunHandler` 完成预检：清 Key 检查、CORS 预检、参数校验与配置覆盖。
2. 获取或新建会话；先查"该会话是否已在跑"（是则 409，快速失败），再抢全局运行许可（失败则 409，覆盖跨会话并发）。许可一旦获取，所有失败路径都必须释放，成功则把释放责任移交给后台执行线程。`/session/resume`、`/session/compact`、`/runProject` 走同一信号量，因此与 `/run` 全局互斥。
3. 建立 SSE 响应，先发一条 `{"type":"session",...}` 告知前端会话号，再启动保活 ping 线程并在后台线程执行 Agent。SSE 的头、消费者、ping、收尾清理与许可释放统一由 `SseTaskSession` 承担，`/run` 与 `/session/resume` 只参数化差异（`run` / `runResume`、是否补发 `error`、tag 前缀）。
4. 主循环逐轮：`ContextManager.buildMessages()` → `sendAndReceive` 调大模型 → 记录 usage → 若有 `tool_calls` 则逐条 `ToolExecutor.dispatch` 执行并把结果作为 `tool` 消息回灌，一轮结束批量刷新锚点脏文件；无 `tool_calls` 即任务完成。单轮内的相同调用（name + arguments 相同）只执行一次，其余 tool_call 复用首次结果并照常回填 `tool` 消息，避免模型异常重复输出时反复触发同一工具。**每轮工具结果并入工作区之后**覆盖写一次 recovery（完整 `buildMessages()` 快照 + 基础区指纹），语义是"最后完成一轮的状态"，崩溃后可续而不会重跑已完成轮次；同一轮还通过迭代回调向前端推一条 `context` 控制事件（本轮 `tokens` 与模型窗口）。进入循环前先重建索引（绑定工作项目时强制重建该项目，否则遍历各项目并按新鲜度跳过），保证 Agent 读到的锚点行号不滞后。
5. `finally` 阶段（best-effort）：生成本轮用量增量、把任务摘要并入基础区（异常中断则只清空工作区）、刷新锚点索引（只重建 `isProjectFresh` 判为过期的项目）、落盘并压缩原始日志、按"自然完成或用户主动停止"清除 recovery（只有真实异常才保留供续跑）、打印统计，最后 `markIdle` 并触发由 `SessionManager.close()` 注册的"任务结束后落盘并移除"回调（若有）。
6. SSE 侧：无论成败都追加会话级用量并推 `{"type":"usage",...}`，随后发 `done` / `error`（JSON 控制事件，分别携带 `summary` / `message` 与 `stack`）与 `{"type":"end"}`，落盘会话、清理消费者与连接、释放运行许可。

三条与任务流平行的会话/项目动作（都要先拿到全局运行许可，因此与 `/run` 互斥）：

- **上下文压缩** `POST /session/compact`：先把基础区快照归档到 `history-base/`，再把待压缩范围的历史 + 压缩指令发给模型生成结构化摘要，用摘要覆盖基础区（保留 SystemPrompt 与最近 N 对消息；切点从尾部按 user 消息对齐，N 下限为 1，不再允许"完全清空"）；压缩改变了基础区，因此同步清除 recovery，避免续跑到与旧上下文不匹配的工作区。
- **异常续跑** `POST /session/resume`：`GET /session/hasRecovery` 判断是否存在可续进度（同时校验 recovery 内的基础区指纹，并回传上次的 prompt）——不匹配时直接返回 `hasRecovery=false`；resume 读取快照，指纹不符直接 409，相符则以快照里的基础区条数作切点把其余消息注入 `volatileWorking`，跳过"准备用户消息"，按与 `/run` 相同的 SSE 协议继续执行。
- **手动跑项目** `POST /runProject`：前端"▶ 运行"按钮触发，用 `ToolExecutor.forManualRun` 直接执行一次 `compile_and_run` 并同步返回输出——不进入 Agent 循环、不建 SSE、不绑定会话，因此其产物不写入任何会话历史。

模型可调用的工具由 `ToolDefinitions` 与 `ToolExecutor.dispatch` 一一对应，包括：文件类（list_directory / read_file / write_file / delete_file / get_file_structure）、锚点类（read_between_anchors / insert_at_anchor / delete_between_anchors / build_anchor_index / describe_anchors）、检索类（search_text / find_references / find_callers / find_callees）与执行类（compile_and_run）。

---

<!-- @anchor: project_frontend -->
<!-- 前端架构：无构建的模块化静态页；桌面端 modules/ 与移动端 mobile/ 双入口 -->
## 前端架构

前端是**无打包器**的原生静态资源（`resources/static`），由后端 `StaticHandler` 直接吐出；各脚本以普通 `<script>` 顺序加载、通过全局变量与函数协作。`index.html` 里脚本的加载顺序即依赖顺序：第三方库 → 基础模块（DOM 引用 / 状态 / 文案）→ 功能模块（文件树、SSE、恢复、配置…）→ `events.js` 最后绑定界面事件与初始化。

- **桌面端**：`index.html` + `style.css`，逻辑按职能拆进 `modules/`：
  - 基础：`config.js`（DOM 引用、marked 渲染器、把本地设置拼成请求用的 `config`）、`sessionState.js`（当前会话号读写）、`log.js`（日志渲染与转义）、`quote.js` + 根目录 `tips.js`（随机迭代提示语与文案库）、`quickPrompts.js`（常用话术快捷入口，可自定义并持久化）、`history.js`（prompt 历史）。
  - 运行链路：`sse.js`（`POST /run` 的 SSE 主链路，含停止与 `GET /stream` 只读订阅）、`status.js`（`/status` 轮询驱动按钮状态）、`heartbeat.js`（心跳 + 断联提示 + 目录变化后刷新）、`control.js`（控制权接管与 banner）、`runner.js`（文件树"▶ 运行"→ `POST /runProject`）。
  - 上下文与容错：`contextSize.js`（上下文圆饼、详情弹层、触发压缩）、`usageStore.js`（用量面板）、`recovery.js`（顶栏红色感叹号 + 弹出错误详情 + 继续 / 忽略）。
  - 会话与文件：`sessionList.js`、`sessionCreate.js`、`tree-api.js` / `tree-render.js` / `tree-state.js` / `tree-actions.js`、`archive.js`（归档 / 上传 / 建项目）、`openFolder.js`、`toolApi.js`（`POST /tool` 调白名单工具）。
  - 配置：`settings.js`（`agentSettings` 本地配置的读写与表单填充；保存 / 恢复默认后 `POST /restart` 并提示关闭页面，清 Key 走 `POST /clear-api-key`）、`sidebar.js`（侧边栏折叠）、`events.js`（侧边栏视图切换、输入框自适应、可见性等界面事件与初始化）。
- **移动端**：`mobile/index.html` + `mobile.css`，`mobile-core.js`（常量 / DOM / 状态）、`mobile-session.js`、`mobile-run.js`、`mobile-tree.js`、`mobile-status.js`、`mobile-control.js` 分工，`mobile.js` 作为入口绑定事件并初始化；UI 为顶栏 + 抽屉式会话/文件面板。移动入口会给所有请求打上设备标识，但只覆盖会话、运行、文件树与控制权，不提供上下文压缩与续跑入口。
- **辅助页**：`code.html` + `code.js`（代码预览：按 `path` 参数打开沙箱文件，带语法高亮与锚点侧栏，桌面与移动端文件树均跳转至此）、`qr.html`（手机扫码访问 + 一键把控制权切回桌面）、`md.html`（Markdown 预览，基于 `marked.min.js`）、`scan.html`/`scan.js`/`scan.css`（大文件扫描与导出）。
- **与后端的契约**：运行走 `POST /run` 的 SSE 流——普通日志以原生多行 `data:` 帧传输（前端拼回换行），控制事件为单行 JSON `data: {"type":...}`（`session` / `usage` / `context` / `done` / `error` / `end`），由前端单独识别，界面上的 `[完成]/[错误]` 只是前端前缀；`context` 每轮携带 `tokens` 与 `modelWindow`，前端据此实时刷新上下文圆饼而不再发请求。收到 `error` 时前端把错误与堆栈持久化到 `localStorage`，顶栏红色感叹号据此跨刷新提示，"继续上次任务"先查 `GET /session/hasRecovery` 再 `POST /session/resume` 续跑。上下文占用由 `GET /session/context` 提供（运行中给精确 token，空闲给基础区字节数），压缩按钮走 `POST /session/compact`。观看他端正在运行会话的实时日志走只读 `GET /stream`：任务结束推 `stream-end`、触达最大生命周期推 `stream-timeout`，前端在任务仍运行时按退避重连；本端自发运行时用 `isLocalRunActive()` 守卫、`openStream` 幂等，避免 `/run` 与 `/stream` 重复订阅。心跳 `POST /heartbeat` 续活并回传"目录是否变化"，`GET /status` 轮询任务状态，`GET /config` 读取服务端默认（如最大迭代次数）回填表单。
- **本地持久化**：`localStorage` 保存提示词历史、配置面板设置（`agentSettings`）、当前会话号、自定义话术与待续跑的错误信息；配置项以后端 `ConfigEditor` 的同名字段随请求下发，改配置后调 `/restart` 生效。

---

<!-- @anchor: project_data_dirs -->
<!-- 运行时目录：sessions 持久化与续跑快照、HistoryOutput 日志、sandbox 工作区、TestProjects 归档 -->
## 关键数据目录（运行时约定）

- `./sandbox`：Agent 的受约束工作区；每个子目录是一个"项目"，也是会话可选的工作项目。`sandbox/tmp` 是跨项目可写的公共临时区。
- `./sessions`：会话持久化。顶层 `index.json` 汇总全部会话元数据；每个会话一个子目录，内含 `meta.json`、`immutable_base.jsonl`、`volatile_working.jsonl`、`usage.json`，以及 `recovery_raw.jsonl`（最后完成一轮的完整 `buildMessages()` 快照 + 基础区指纹，单行 JSON、覆盖写，供续跑）与 `history-base/`（压缩前的基础区归档）。
- `./HistoryOutput`：运行期产物。`history.jsonl` 记录全局用户请求；`{sessionId}/<时间戳>.log` 是每轮运行的日志；原始 API 请求/响应先缓存，任务结束时压缩为 `.gz` 落到会话子目录。
- `./temp`：原始 API 日志在压缩前的暂存目录。
- `./TestProjects`：项目归档区（按版本目录组织）。以上运行时目录由主服务启动时自动创建。
- 各项目内：`.anchors.json` 与 `.project_index.json` 是锚点/结构索引；`.agent_entry.json` 记录最近一次成功运行的入口（供前端"▶ 运行"按钮）。"项目根"由 `ProjectLayout.resolveProjectRoot`（回溯到沙箱下一级目录）统一推导，写入侧与读取侧共用同一定义。

---

<!-- @anchor: project_security -->
<!-- 安全模型：沙箱边界、静态资源与沙箱内容直出加固、路径与工作项目校验、编译前扫描、控制权边界 -->
## 安全模型

- **路径边界**：所有文件路径经 `PathUtils.safeResolve` 归一化并强制落在沙箱内；`..`、绝对盘符、"`.` 开头的隐藏路径"被拒绝；`UPDATE.md` 不允许整体读取或删除。
- **静态资源**：`StaticHandler` 统一分隔符后逐段拒绝 `..`，只直出白名单扩展名（html/css/js/图片/字体等）并补 `X-Content-Type-Options: nosniff`，非白名单路径（如 exploded 形态下的 `agent-config.properties`）直接 404。
- **沙箱内容直出**：`/sandbox`、`/TestProjects` 下任意文件按扩展名直出，其中 `text/html` 与 `image/svg+xml` 额外带 `Content-Security-Policy: sandbox allow-scripts allow-forms allow-popups allow-modals; connect-src 'none'`——被打开的上传文件/模型产出运行在唯一源里，读不到工作台的同源存储，也发不出 fetch/XHR/WebSocket。
- **工作项目限定**：写操作有两个裁决点，且对同一场景给出相同结论——`PathPolicy.checkWorkProject` 管工具路径参数与 `write_file` / `delete_file` / `compile_and_run` / `build_anchor_index` 的项目归属；`AnchorManager.checkWorkProjectPermission` 管 `insert_at_anchor` / `delete_between_anchors` 命中文件的落点。公共 `tmp` 恒可写；未绑定工作项目时全沙箱可写；已绑定工作项目时只能改该项目或 `tmp`。
- **手动模式**：`/runProject` 与前端 `/tool` 不经会话，走 `ToolExecutor.forManualRun`——不绑定工作项目，因此 `PathPolicy` 只剩沙箱边界与 `UPDATE.md` 规则，工作项目限定整体跳过；`/tool` 另有一层工具名白名单兜底。这是"用户手动操作不受会话工作项目约束"的有意设计，代价是这两条入口的写入范围比 Agent 工具更宽。
- **工具链路径**：前端请求可覆盖的工具链字段（`mavenCommand` / `javaHome` / 各语言解释器 / `msvcCompiler` / `mingwCompiler`）经 `ConfigEditor.isSafeExecutable` 校验——拒绝 shell 元字符，只接受"PATH 命令名"或"沙箱之外的绝对路径"，否则回退服务端默认值；`cppCompilerType` 只认 `mingw` / `msvc`。
- **编译前扫描**：`compile_and_run` 在真正编译前调用 `SecurityScanner`，命中"系统命令调用""路径穿越"等高危模式即拦截；开关只由服务端配置（`agent-config.properties` 的 `agent.enableSecurityScan`）决定——配置面板虽会提交该字段，但 `ConfigEditor` 直接沿用服务端值，请求无法关闭扫描。
- **进程安全**：外部进程的工作目录必须在沙箱内，且强制重定向临时目录与用户主目录，stderr 由执行器内部合并进 stdout；统一走 `ProcessRunner`（流式 UTF-8 解码、100 万字符输出上限、超时与 stdin 阻塞检测、进程树清理、`ProcessRegistry` 兜底回收），编译与运行命令显式注入 `stdout/stderr/file.encoding=UTF-8`（javac 走 `-J-` 形式，Maven 走 `MAVEN_OPTS`，Python 走 `PYTHONUTF8`/`PYTHONIOENCODING`）。仍有两处例外直接使用 `waitFor` + 整段读取而非 `ProcessRunner`：Maven 的 JavaFX 启动分支与 `javap` 主类探测（后者先 `waitFor(10s)` 再读输出，反之为避免超时分支不可达而刻意如此）。
- **前端工具白名单**：`/tool` 端点只放行只读或幂等的少量工具（build_anchor_index / describe_anchors / get_file_structure / list_directory / read_between_anchors），写/删/执行类工具只对模型开放。
- **控制权**：写路由（`/run`、`/session/resume`、`/session/compact`、`/runProject`、`/tool` 与会话增删改等）由 `GuardedHandler` 按"当前控制端"加锁，非控制端返回 403；读路由（如 `/session/context`、`/session/hasRecovery`、`/stream`）与 `/control/*` 始终放行。控制端身份来自请求自述（`X-Client-Device`，UA 兜底），因此它保证的是"两端互斥"而非"谁能访问"。

---

<!-- @anchor: project_build_run -->
<!-- 构建与运行：Maven 打包 fat jar、编译参数、环境变量与启动方式 -->
## 构建与运行

- **技术栈**：Java 21，Maven 构建，`maven-assembly-plugin` 打成 `*-jar-with-dependencies.jar`（主类为 HTTP 服务入口）。编译期需 `jdk.compiler` 模块与其若干 `--add-exports`（供 Java 结构解析器使用 javac AST）。
- **必需环境变量**：`DEEPSEEK_API_KEY`（否则主服务以退出码 10 退出）。
- **可选环境变量**：`AGENT_BIND`（绑定地址，如 Tailscale IP）、`AGENT_CONFIG`（配置文件路径）。
- **配置文件**：`agent-config.properties`。查找顺序为环境变量指定 → 工作目录 → classpath；均缺失时由 `EnvDetector` 扫描本机生成，再兜底内置默认值。前端配置面板写入浏览器本地存储并在运行时随请求下发，覆盖后端默认值。
- **启动方式**：直接 `java -jar` 主服务，或经 `LauncherMain` 守护（HTTP + 按钮页）按需启停。当前工作目录应设为主服务根目录，以保证 `./sessions`、`./sandbox` 等落点正确。
