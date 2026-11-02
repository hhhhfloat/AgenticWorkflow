<!-- @anchor: project_intro -->
<!-- 项目总览：本地单机 AI 编码工作台的定位、设计理念与整体架构 -->

# agentic-workflow · 本地 AI 编码工作台

## 一句话定位

`agentic-workflow` 是一个**本地单机运行**的 AI 编码工作台：把大模型（默认 DeepSeek）包装成一名"全栈开发工程师" Agent，让它在受限沙箱目录内自主读写代码、编译运行、维护锚点索引，并通过一套自托管的 HTTP + SSE 服务，用浏览器（桌面端 / 手机端）驱动与观察整个过程。

它不是通用聊天界面，而是**围绕"锚点（anchor）"与"会话（session）"两个原创概念构建的工程化 Agent 外壳**：大模型只负责决策，文件操作、上下文管理、并发控制、安全扫描、能力暴露全部由后端代码统一裁决。运行时面向 **Windows 与 Linux 双平台**：平台差异被收敛到少数几处（`Platform` 判定、进程执行层、PATH 探测、外部打开动作），业务逻辑不做平台分支。

---

<!-- @anchor: project_design -->
<!-- 设计理念：贯穿全项目的跨文件约定（锚点、双区上下文、会话、沙箱、控制权、跨平台） -->
## 设计理念（贯穿全项目的约定）

1. **锚点优先（Anchor-first）**
   源码里用 `@anchor: id` 注释标记功能块，每个源文件首个锚点固定为 `<file_name>_intro`。Agent 读代码用"按锚点读区间"，改代码用"在锚点区间内操作"，避免全量读写大文件。**修改工具按改动量分工**——纯新增用 `insert_at_anchor`；局部小改（改几行、换一个值）用 `edit_between_anchors`，它在锚点区间内用 `old_string → new_string` 做原子替换，要求 `old_string` 在区间内**唯一命中**、且**锚点行本身不可被编辑**（区间按开区间处理），零命中/多命中时返回带上下文与相似候选的诊断，引导模型加长 `old_string` 而不是盲目重写；整段重写才用 `delete_between_anchors` + `insert_at_anchor`。
   锚点定位统一走 `AnchorResolver`：`anchorId` + 可选 `fileHint`（支持完整路径 / 不带项目名 / 纯文件名三种形态），多处命中时列候选并要求更精确路径，零命中时先全局兜底、再按编辑距离给出相似 ID 建议；模型误传的 `@anchor:` 前缀、注释符、位置大小写由归一化逻辑吸收。
   锚点被索引成两份产物：`.anchors.json`（id + 行号 + 预览）与 `.project_index.json`（id + 行号 + 职责描述 + 所属符号）。**索引新鲜度**由"两份索引 mtime 的较小值 ≥ 源侧最大 mtime"判定：运行开始强制重建当前工作项目（未绑定时按新鲜度逐项目跳过），收尾只重建过期项目；索引读取按 mtime 走缓存。

2. **双区上下文（Base + Working）**
   会话历史拆成**不可变基础区**（SystemPrompt、全部用户消息、历次任务摘要、文档变更日志）与**易失工作区**（当前任务的多轮消息）。任务结束只把一段"任务摘要"并入基础区、工作区清空——用恒定大小的摘要换跨轮次记忆，使 token 成本随会话推进收敛。
   收敛与容错各有补充：**按需压缩**——基础区过大时由 `/session/compact` 让模型把较早历史重写成结构化摘要（保留 SystemPrompt 与最近 N 对消息，切点按 user 消息对齐），压缩前先归档到会话目录的 `history-base/`，可回看；**崩溃可续**——每轮工具结果并入工作区后，把完整的 `buildMessages()` 快照连同基础区指纹覆盖写入 recovery 文件，自然完成或用户主动停止即清除、只有真实异常才保留，`/session/resume` 校验指纹后按切点续跑。

3. **会话为一等公民**
   `Main` 的生命周期从属于一个 `Session`。会话持有上下文、元数据、运行状态与日志订阅者，可创建 / 切换 / 重命名 / 落盘 / 从磁盘恢复；HTTP 服务重启后会话仍可从 `./sessions` 载入。

4. **沙箱 + 最小权限**
   所有文件读写经 `PathUtils` 限制在 `./sandbox` 内（拒绝 `..`、盘符、`.` 开头路径）；外部进程的工作目录必须在沙箱内，并重定向其临时目录与用户主目录。写操作由 `PathPolicy` 约束在当前会话绑定的"工作项目"内，公共临时区 `tmp` 除外；锚点写工具的落点另由 `AnchorManager` 复核，两个裁决点对"公共 tmp / 未绑定工作项目 / 已绑定工作项目"给出同一结论。**不经会话的手动入口**（`/runProject`、前端 `/tool`）以"手动模式"运行工具，不绑定工作项目，只保留沙箱边界校验。请求可覆盖的工具链路径只接受"PATH 命令名或沙箱外绝对路径"，断掉"上传 → 指定为解释器 → 执行"的链路。

5. **零框架、单 jar、纯 JDK**
   后端只依赖 JDK 内置 `com.sun.net.httpserver` + OkHttp + Jackson + Logback，打包为 fat jar；`launcher` 是同一 jar 内的另一个 `main`，负责按需拉起 / 停止主服务。

6. **多端 + 单一控制权**
   桌面端与手机端共用同一后端。设备通过显式头 `X-Client-Device`（UA 兜底）声明身份；写路由受"当前控制端"锁保护，避免两端同时操作，控制权可切换。该锁解决的是"两端互斥"，**不是身份鉴权**——它信任请求方的自述身份。

7. **跨平台收敛（Windows / Linux）**
   `os.name` 判断只在 `Platform.isWindows/isLinux` 一处收口，业务代码不直接嗅探系统名。真正有平台差异的只有三层：**进程执行层**（Linux 用 `setsid` 启动并在清理时按进程组发送 `SIGKILL` 以回收孤儿进程；Windows 走进程树 `destroyForcibly`）、**PATH / 工具链探测**（按 `File.pathSeparator` 切分 PATH、Linux 额外校验可执行位并跳过 WSL 的 `/mnt/` 挂载、探测集按平台选择、C++ 编译器类型 Linux 默认 `gcc`、可执行产物无 `.exe` 后缀）、**外部打开动作**（`cmd /c start` / `open` / `xdg-open`）。

---

<!-- @anchor: project_runtime -->
<!-- 运行模型：两个 main 进程（launcher 守护 + HTTP 主服务）、绑定地址与退出码约定 -->
## 进程与运行模型

- **主服务**：`HttpServerMain.main`。启动时校验 `DEEPSEEK_API_KEY`、装载全局配置、创建运行时目录（`./sandbox`、`./TestProjects`、`./HistoryOutput`、`./temp`、`./sessions`）、初始化 `SessionManager` 并注册 ShutdownHook，随后注册全部路由并启动。默认监听 `8080`。
  - 绑定地址由 `BindAddressResolver` 决定：`AGENT_BIND` 优先；未设置时自动探测 Tailscale（`100.64.0.0/10`），探测不到回退 `127.0.0.1`。launcher 与主服务共用该解析器，launcher 解析一次后通过环境变量把地址下发给子进程，保证两端一致。
  - 心跳监视线程（daemon）：客户端心跳失联只记录日志并留待前端提示，不停任务；任务超过硬超时（30 分钟）才归一化为 IDLE。另有父进程存活监视，`--daemon` 模式下跟随父进程退出，避免 launcher 被杀后留下孤儿进程。
- **守护启动器**：`LauncherMain.main`。常驻 `8081`，暴露 `/start`、`/stop`、`/status` 与按钮首页，用 `ProcessBuilder` 以同 jar 的 `--daemon` 方式拉起主服务（继承自身的 JVM 参数、强制 UTF-8，工作目录跟随 launcher 的 CWD），并以 UTF-8 接管子进程 stdout 写入滚动日志，再轮询 `/status` 判定就绪。
- **退出码约定**：`10` 无/无效 API Key，`42` 用户主动清除 API Key，`43` 请求重启，`12` 启动失败（端口占用 / 线程冲突）。前端保存配置或清 Key 后调对应接口，进程自行退出，由 launcher / 外部脚本决定是否重启。

---

<!-- @anchor: project_backend -->
<!-- 后端分层：core / session / tools / parser / security / model / http / launcher 的职责划分 -->
## 后端分层

后端包名统一为 `com.myagent.workflow`，按职责分为八层：

- **core（编排与上下文）**：`Main` 是 Agent 主循环——组装请求、调模型、分发工具调用（含单轮相同调用去重）、累计用量，并在 `finally` 完成摘要合并、索引刷新、日志落盘与 recovery 去留；`run` 与 `runResume` 共用同一内部循环，差异仅在是否重新准备用户消息。`ContextManager` 实现双区上下文、摘要合并、按需压缩、快照恢复与基础区指纹；`HistoryRecorder` 负责原始 API 日志的缓存 / 刷盘 / 压缩并维护 recovery 快照（同步方法，日志回调在锁外）；`SystemPrompt` 集中存放行为规范（含工作项目提示与"按改动量选修改工具"的约定）；`UsageTracker` 计费，`ToolLogFormatter` 处理人类可读工具日志，`ProcessRegistry` 登记子进程以便统一回收。
- **core.config（配置与环境探测）**：`AgentConfig` 是运行配置 record 与默认值兜底；`ConfigEditor` 把请求覆盖项叠加到默认配置，校验可执行路径安全、拒绝由请求关闭安全扫描；`BindAddressResolver` 解析绑定地址；`env` 子包是探测器体系——`Platform` 收口平台判定，`EnvDetector` 按平台选择 `Probe` 集合（Windows 含 MinGW/MSVC，Linux 用 GCC）并配合 `ShellPathResolver`、`CommandRunner`、`ConfigRenderer` 等生成本机工具链配置。
- **session（会话）**：`Session` 聚合上下文、元数据、状态与日志多播；日志多播面向 `LogSink` 抽象（`offer` 非阻塞），`SseLogSink` 以有界队列 + 独立写线程落 socket，普通日志满则丢弃计数、控制事件满则抢占丢弃最旧一条，慢连接不拖累 Agent 主线程。`SessionManager` 是内存容器 + 磁盘协调者，用信号量把 `/run`、`/session/resume`、`/session/compact`、`/runProject` 统一限制为全局串行，`close()` 超时时注册回调交给任务线程收尾；`SessionStorage` 只做"存 / 取"，管 `./sessions` 下的 meta / jsonl / 索引 / 用量文件与 `history-base/` 归档。
- **tools（Agent 能力）**：`ToolDefinitions` 声明工具 schema，`ToolExecutor` 以"工具名 → 处理器"表分发，并承担索引刷新策略（运行前重建当前项目、收尾只重建过期项目、单轮结束刷脏）；`PathPolicy` 负责路径参数与写类工具的工作项目限定（含手动模式），锚点写工具落点由 `AnchorManager` 复合同一口径；`ProjectLayout` 是"哪些文件 / 目录该被跳过"与"项目根推导"的单一真源；`Args` 做类型宽容的参数提取。底层能力拆成：`FileOperator` / `PathUtils`（沙箱读写与路径安全）、`anchor`（`AnchorManager` 门面 + `AnchorResolver` 定位、`AnchorScanner` / `AnchorIndex` / `AnchorIndexCache` / `AnchorQuery` / `AnchorFormatter` / `AnchorEditDiagnostics`）、`search`（`CodeSearcher` 门面 + 文本检索 / 引用查找 / 调用图）、`runner`（`Compiler` / `CompileRunner` 编排 + 各语言运行器 + `ProcessRunner`）。
- **parser（结构解析）**：`StructureParserRegistry` 按扩展名分发语言解析器（Java 走 javac AST、失败降级正则；Python/C++/JS/HTML/CSS 为缩进或正则），`GenericParser` 兜底，`FileStructureFormatter` 渲染紧凑文本。
- **security（安全扫描）**：`SecurityScanner` 在编译前按规则匹配，`RuleRegistry` 内置"命令执行"与"路径穿越"两条 ERROR 规则（先剥离注释，再放行无害 import），并带"未变更文件跳过"缓存；规则开关只由服务端配置决定。
- **model**：解析产物与锚点位置的数据载体。
- **http（服务层）**：`HttpServerMain` 是入口与路由表（含心跳监控与父进程监视）；`GuardedHandler` 是设备保护包装器；`handlers` 按域分子包——`session`（CRUD / 历史 / 日志 / 用量 / 上下文 / 压缩 / 恢复）、`run`（运行、恢复、停止、日志流、手动跑项目、前端工具调用，以及被 `/run` 与 `/session/resume` 共用的 `SseTaskSession`）、`project`（列表 / 元信息 / 浏览 / 归档 / 上传 / 建项目 / 扫描 / 开目录）、`staticres`（静态资源与外部目录映射）、`system`（状态、心跳、控制权、配置、重启、退出、清 Key）。
- **launcher**：守护启动器（见上）。

---

<!-- @anchor: project_task_lifecycle -->
<!-- 一次任务的完整生命周期：从 /run 到 SSE 收尾、用量落库、recovery 快照与索引刷新 -->
## 一次任务的生命周期

1. 前端 `POST /run`（携带 `prompt`、可选 `sessionId`、`maxIterations` 与运行配置）。`RunHandler` 完成预检：清 Key 检查、CORS 预检、参数校验与配置覆盖。
2. 获取或新建会话；先查"该会话是否已在跑"（409 快速失败），再抢全局运行许可（409 覆盖跨会话并发）。许可一旦获取，所有失败路径都必须释放，成功则把释放责任移交给后台执行线程。`/session/resume`、`/session/compact`、`/runProject` 走同一信号量，因此与 `/run` 全局互斥。
3. 建立 SSE 响应并启动后台执行；SSE 的头、消费者、保活、收尾清理与许可释放统一由 `SseTaskSession` 承担，`/run` 与 `/session/resume` 只参数化差异。
4. 主循环逐轮：`buildMessages()` → 调模型 → 记录 usage → 有 `tool_calls` 则逐条分发并把结果作为 `tool` 消息回灌，一轮结束批量刷新锚点脏文件；无 `tool_calls` 即完成。单轮内相同调用只执行一次。**每轮工具结果并入工作区后**覆盖写 recovery（完整快照 + 基础区指纹），语义是"最后完成一轮的状态"；同时推一条 `context` 控制事件（本轮 tokens 与模型窗口）。进入循环前先重建索引，保证 Agent 读到的行号不滞后。
5. `finally`（best-effort）：生成用量增量、把摘要并入基础区（异常中断则只清空工作区）、刷新过期索引、落盘并压缩原始日志、按"自然完成或用户主动停止"清除 recovery（只有真实异常保留）、打印统计，最后 `markIdle` 并触发 `close()` 注册的收尾回调。
6. SSE 侧：无论成败都追加会话级用量并推 `usage`，随后发 `done` / `error`（JSON 控制事件，分别带 `summary` / `message` 与 `stack`）与 `end`，落盘会话、清理连接、释放许可。

三条与任务流平行的动作（都要先拿到全局运行许可，因此与 `/run` 互斥）：

- **上下文压缩** `POST /session/compact`：归档基础区 → 把待压缩历史 + 指令发给模型生成结构化摘要 → 用摘要覆盖基础区（保留 SystemPrompt 与最近 N 对消息，切点按 user 消息对齐）→ 同步清除 recovery，避免续跑到与旧上下文不匹配的工作区。
- **异常续跑** `POST /session/resume`：`GET /session/hasRecovery` 判断是否有可续进度（含基础区指纹校验）；resume 时指纹不符直接 409，相符则以快照切点注入工作区、跳过"准备用户消息"，按与 `/run` 相同的 SSE 协议继续执行。
- **手动跑项目** `POST /runProject`：前端"▶ 运行"触发，直接执行一次 `compile_and_run` 并同步返回输出——不进 Agent 循环、不建 SSE、不绑会话，产物不写任何会话历史。

模型可用工具由 `ToolDefinitions` 声明、`ToolExecutor` 按名分发：文件类（list_directory / read_file / write_file / delete_file / get_file_structure）、锚点类（read_between_anchors / insert_at_anchor / edit_between_anchors / delete_between_anchors / build_anchor_index / describe_anchors）、检索类（search_text / find_references / find_callers / find_callees）与执行类（compile_and_run）。

---

<!-- @anchor: project_frontend -->
<!-- 前端架构：无构建的模块化静态页；桌面端 modules/ 与移动端 mobile/ 双入口 -->
## 前端架构

前端是**无打包器**的原生静态资源（`resources/static`），由 `StaticHandler` 直出；脚本以普通 `<script>` 顺序加载、通过全局变量协作，加载顺序即依赖顺序（第三方库 → 基础模块 → 功能模块 → `events.js` 绑定事件与初始化）。

- **桌面端**（`index.html` + `modules/`）：`config.js` 管 DOM 引用 / Markdown 渲染器 / 请求配置拼装，`sessionState.js` / `history.js` / `quote.js`+`tips.js` / `quickPrompts.js` 管会话号、提示词历史与文案库。运行链路是 `sse.js`（`POST /run` 主链路，含停止与 `GET /stream` 只读订阅）+ `status.js`（状态轮询）+ `heartbeat.js`（心跳、断联提示、目录变化刷新）+ `control.js`（控制权）+ `runner.js`（"▶ 运行" → `/runProject`）。上下文与容错由 `contextSize.js`（圆饼 + 压缩）、`usageStore.js`、`recovery.js`（错误持久化 + 继续 / 忽略）承担。会话与文件侧是 `sessionList.js` / `sessionCreate.js` / `tree-*.js` / `archive.js` / `openFolder.js` / `toolApi.js`；配置侧是 `settings.js`（`agentSettings` + `/restart`）、`sidebar.js`、`events.js`。
- **移动端**（`mobile/`）：`mobile-core/session/run/tree/status/control.js` 分工，`mobile.js` 作为入口；UI 为顶栏 + 抽屉面板。移动端同样打设备标识，但只覆盖会话、运行、文件树与控制权，不提供压缩与续跑入口。
- **辅助页**：`code.html`（代码预览 + 锚点侧栏）、`qr.html`（扫码访问 + 控制权切回桌面）、`md.html`（Markdown 预览）、`scan.html`（大文件扫描）。
- **与后端的契约**：运行走 `POST /run` 的 SSE 流——普通日志是原生多行 `data:` 帧，控制事件是单行 JSON（`session` / `usage` / `context` / `done` / `error` / `end`），前端单独识别；`context` 每轮携带 tokens 与窗口，圆饼无需额外请求。收到 `error` 时前端持久化错误与堆栈，顶栏感叹号跨刷新提示，"继续上次任务"先查 `hasRecovery` 再 `resume`。观看他端运行走只读 `GET /stream`（结束推 `stream-end`、超时推 `stream-timeout`，按退避重连），本端自发运行时用守卫与幂等 `openStream` 避免重复订阅。`/status` 轮询任务状态，`/config` 读取服务端默认值回填表单。
- **本地持久化**：`localStorage` 保存提示词历史、配置面板设置、当前会话号、自定义话术与待续跑错误；配置项以 `ConfigEditor` 同名字段随请求下发，改配置后调 `/restart` 生效。

---

<!-- @anchor: project_data_dirs -->
<!-- 运行时目录：sessions 持久化与续跑快照、HistoryOutput 日志、sandbox 工作区、TestProjects 归档 -->
## 关键数据目录（运行时约定）

- `./sandbox`：Agent 的受约束工作区；每个子目录是一个"项目"，也是会话可选的工作项目，`sandbox/tmp` 为跨项目可写的公共临时区。
- `./sessions`：会话持久化。顶层 `index.json` 汇总元数据；每个会话一个子目录，含 `meta.json`、`immutable_base.jsonl`、`volatile_working.jsonl`、`usage.json`、`recovery_raw.jsonl`（最后完成一轮的完整快照 + 基础区指纹，覆盖写）与 `history-base/`（压缩前归档）。
- `./HistoryOutput`：`history.jsonl` 记全局请求，`{sessionId}/<时间戳>.log` 记每轮日志，原始 API 请求/响应压缩为 `.gz` 落会话子目录；`./temp` 是压缩前暂存目录。
- `./TestProjects`：项目归档区（按版本目录组织）。以上目录由主服务启动时自动创建。
- 各项目内：`.anchors.json` 与 `.project_index.json` 是索引产物；`.agent_entry.json` 记录最近一次成功运行的入口。**项目根**统一由 `ProjectLayout.resolveProjectRoot`（回溯到沙箱下一级目录）推导，`compile_and_run` 把入口注册表写在该目录；读取侧 `/project-meta` 以调用方传入的项目目录为准，因此前端文件树的"项目节点"即注册表所在目录。

---

<!-- @anchor: project_security -->
<!-- 安全模型：沙箱边界、静态资源与沙箱内容直出加固、路径与工作项目校验、编译前扫描、控制权边界 -->
## 安全模型

- **路径边界**：所有文件路径经 `PathUtils.safeResolve` 归一化并强制落在沙箱内；`..`、盘符、"`.` 开头的隐藏路径"被拒绝；`UPDATE.md` 不允许整体读取或删除。
- **静态资源**：`StaticHandler` 统一分隔符后逐段拒绝 `..`，只直出白名单扩展名并补 `X-Content-Type-Options: nosniff`，非白名单路径直接 404。
- **沙箱内容直出**：`/sandbox`、`/TestProjects` 下文件按扩展名直出，其中 `text/html` 与 `image/svg+xml` 额外带 `Content-Security-Policy: sandbox ...; connect-src 'none'`——被打开的上传文件 / 模型产出运行在唯一源里，读不到工作台同源存储，也发不出 fetch/XHR/WebSocket。
- **工作项目限定**：写操作有两个裁决点并给出相同结论——`PathPolicy.checkWorkProject` 管工具路径参数与 `write_file` / `delete_file` / `compile_and_run` / `build_anchor_index` 的项目归属；`AnchorManager` 管锚点写工具命中文件的落点。公共 `tmp` 恒可写；未绑定工作项目时全沙箱可写；已绑定时只能改该项目或 `tmp`。
- **手动模式**：`/runProject` 与 `/tool` 不经会话，走手动模式工具执行器——不绑定工作项目，因此只保留沙箱边界与 `UPDATE.md` 规则，`/tool` 另有工具名白名单兜底。这是"用户手动操作不受会话约束"的有意设计，代价是这两条入口的写入范围比 Agent 工具更宽。
- **工具链路径**：请求可覆盖的工具链字段（`mavenCommand` / `javaHome` / 各语言解释器 / `msvcCompiler` / `mingwCompiler`）经 `ConfigEditor.isSafeExecutable` 校验——拒绝 shell 元字符，只接受"PATH 命令名"或"沙箱外绝对路径"，否则回退服务端默认值。
- **编译前扫描**：`compile_and_run` 在编译前调用 `SecurityScanner`，命中"系统命令调用""路径穿越"即拦截；开关只由服务端配置决定，配置面板虽提交该字段但请求无法关闭扫描。
- **进程安全**：外部进程的工作目录必须在沙箱内，强制重定向临时目录与用户主目录，stderr 合并进 stdout；统一走 `ProcessRunner`（流式 UTF-8 解码、输出上限、超时与 stdin 阻塞检测、进程树 / 进程组清理、`ProcessRegistry` 兜底回收），编译与运行命令显式注入 UTF-8 编码参数（javac 走 `-J-`，Maven 走 `MAVEN_OPTS`，Python 走 `PYTHONUTF8`/`PYTHONIOENCODING`）。例外是 Maven 的 JavaFX 启动分支与 `javap` 主类探测仍先 `waitFor` 再读输出。
- **前端工具白名单**：`/tool` 只放行只读或幂等的少量工具，写 / 删 / 执行类只对模型开放。
- **控制权**：写路由（`/run`、`/session/resume`、`/session/compact`、`/runProject`、`/tool` 与会话增删改等）由 `GuardedHandler` 按"当前控制端"加锁，非控制端 403；读路由与 `/control` 始终放行。控制端身份来自请求自述，因此保证的是"两端互斥"而非"谁能访问"。

---

<!-- @anchor: project_build_run -->
<!-- 构建与运行：Maven 打包 fat jar、编译参数、环境变量与启动方式 -->
## 构建与运行

- **技术栈**：Java 21，Maven 构建，`maven-assembly-plugin` 打成 `*-jar-with-dependencies.jar`（主类为 HTTP 服务入口）。编译期需 `jdk.compiler` 模块与若干 `--add-exports`（供 Java 结构解析器使用 javac AST）。
- **平台要求**：Windows 与 Linux 均可运行。Linux 侧依赖 `setsid`（进程组管理）、可选 `xdg-open`（打开目录 / 浏览器），C++ 需 `g++` 或 `clang++`（默认 `cppCompilerType=gcc`）；Windows 侧 C++ 走 MinGW 或 MSVC。
- **必需环境变量**：`DEEPSEEK_API_KEY`（否则主服务以退出码 10 退出）。
- **可选环境变量**：`AGENT_BIND`（绑定地址，如 Tailscale IP）、`AGENT_CONFIG`（配置文件路径）。
- **配置文件**：`agent-config.properties`。查找顺序为环境变量指定 → 工作目录 → classpath；均缺失时由 `EnvDetector` 扫描本机生成，再兜底内置默认值。前端配置面板写入浏览器本地存储并在运行时随请求下发，覆盖后端默认值。
- **启动方式**：直接 `java -jar` 主服务，或经 `LauncherMain` 守护按需启停。当前工作目录应设为主服务根目录，以保证 `./sessions`、`./sandbox` 等落点正确。
