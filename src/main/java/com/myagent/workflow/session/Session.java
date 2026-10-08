// @anchor: session_intro
// 会话实体：多轮对话的一等公民，持有上下文、元数据、运行任务与日志转发
package com.myagent.workflow.session;

import com.myagent.workflow.core.config.AgentConfig;
import com.myagent.workflow.core.ContextManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

// @anchor: session_class
// 会话类：聚合上下文管理器、元数据/状态、当前任务与 SSE 日志消费者
/**
 * 会话 —— 多轮对话的一等公民。
 * <p>
 * 职责：
 * 1. 持有会话的上下文管理器（ContextManager）
 * 2. 记录会话的元数据和状态
 * 3. 管理当前运行的任务（Main 实例 + 线程）
 * 4. 将日志多播给所有订阅者（/run 与 /stream）
 * <p>
 * 注意：
 * - ContextManager 采用"延迟注入"模式：Session 构造时不创建它，
 *   由 SessionManager 在创建完 Session 后注入。这是为了避开
 *   构造时的循环依赖（ContextManager 需要 logConsumer，而 logConsumer
 *   又依赖于 Session 的存在）。
 * - 所有跨线程访问的字段都标记为 volatile。
 */
public class Session {

    private static final Logger logger = LoggerFactory.getLogger(Session.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // @anchor: session_fields
    // 会话字段：不可变标识/配置、可变元数据、运行时状态与当前任务
    // ===== 不可变字段 =====
    private final String sessionId;
    private volatile AgentConfig config;

    // ===== 可变元数据（通过 SessionMeta 的 with* 方法更新） =====
    private volatile SessionMeta meta;

    // ===== 运行时状态 =====
    private volatile SessionState state;
    private volatile ContextManager contextManager;   // 延迟注入

    // 支持多个消费者（运行端 + 拉取端同时订阅）
    private final List<LogSink> logSinks = new CopyOnWriteArrayList<>();

    // ===== 当前运行的任务（临时，任务结束后清空） =====
    private volatile Object runningAgent;             // 用 Object 避免循环依赖 core.Main
    private volatile Thread runningThread;
    private volatile long lastHeartbeatTime;
    private volatile long runningStartedAt;           // 任务开始时间（硬超时用）

    // ==================== 构造 ====================

    // @anchor: session_constructorFull
    // 构造会话并指定 sessionId（供从磁盘恢复时使用），初始化元数据与空闲状态
    /** 由 SessionStorage.load() 使用，传入磁盘上已有的 sessionId */
    public Session(String sessionId, AgentConfig config) {
        this.sessionId = sessionId;
        this.config = config;
        this.state = SessionState.IDLE;

        String now = LocalDateTime.now().format(TIME_FMT);
        this.meta = new SessionMeta(sessionId, "新会话", now, now, 0, SessionState.IDLE, null);
    }

    // @anchor: session_constructorNew
    // 创建新会话时使用：自动生成 sessionId
    /** 创建新会话时使用 */
    public Session(AgentConfig config) {
        this(generateSessionId(), config);
    }

    // @anchor: session_constructorWithProject
    // 创建新会话并指定工作项目（相对沙箱的项目名）
    public Session(AgentConfig config, String workProject) {
        this.sessionId = generateSessionId();
        this.config = config;
        this.state = SessionState.IDLE;
        String now = LocalDateTime.now().format(TIME_FMT);
        String title = (workProject != null && !workProject.isBlank()) ? workProject : "新会话";
        this.meta = new SessionMeta(sessionId, title, now, now, 0, SessionState.IDLE, workProject);
    }

    // @anchor: session_generateSessionId
    // 用时间戳前缀 + UUID 片段生成有序且唯一的会话 ID
    private static String generateSessionId() {
        // 用时间戳 + UUID 前缀，保证有序且唯一
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String uuid = UUID.randomUUID().toString().substring(0, 6);
        return timestamp + "_" + uuid;
    }

    // ==================== 生命周期 ====================

    // @anchor: session_attachContextManager
    // 由 SessionManager 注入上下文管理器，注入后会话才具备执行任务的能力
    /**
     * 由 SessionManager 在创建完 Session 后调用，注入 ContextManager。
     * 注入后，Session 才具备执行任务的能力。
     */
    public void attachContextManager(ContextManager contextManager) {
        this.contextManager = contextManager;
    }

    // @anchor: session_updateConfig
    // 更新会话配置：本次 /run 的请求配置生效
    public void updateConfig(AgentConfig newConfig) {
        if (newConfig != null) {
            this.config = newConfig;
        }
    }

    // @anchor: session_touch
    // 更新最后活跃时间，用于前端会话列表排序
    /**
     * 更新最后活跃时间。
     * 在每次用户输入或 Agent 输出后调用，用于前端列表排序。
     */
    public void touch() {
        String now = LocalDateTime.now().format(TIME_FMT);
        this.meta = meta.withLastActiveAt(now);
    }

    // @anchor: session_rename
    // 更新会话标题，不改变活跃时间戳
    /**
     * 更新会话标题。
     * 只修改 meta.title，不触碰 lastActiveAt（重命名不算“活跃”操作）。
     */
    public void rename(String newTitle) {
        if (newTitle == null || newTitle.isBlank()) return;
        this.meta = meta.withTitle(newTitle.trim());
    }

    // @anchor: session_prepareUserMessage
    // 处理用户消息的首轮 init / 后续 append 分支，并同步更新元数据计数
    /**
     * 准备一条用户消息。
     * <p>
     * 内部处理"首轮 init / 后续 append"分支，并同步更新 meta：
     * - messageCount +1
     * - lastActiveAt 更新为当前时间
     * <p>
     * 注意：title 暂时保持"新会话"，等未来引入"项目目录限定"后，
     * 由项目名自动填充。
     *
     * @param userMessage  用户输入
     * @param systemPrompt 系统提示词（仅首轮使用）
     */
    public void prepareUserMessage(String userMessage, String systemPrompt) {
        if (contextManager == null) {
            throw new IllegalStateException("ContextManager 尚未注入");
        }
        if (userMessage == null || userMessage.isBlank()) {
            return;
        }

        // 首轮：init（写 systemPrompt + userMessage）
        // 后续：appendUserMessage（只追加 userMessage）
        if (contextManager.getBaseMessageCount() == 0) {
            contextManager.init(systemPrompt, userMessage);
        } else {
            contextManager.appendUserMessage(userMessage);
        }

        // 更新 meta
        this.meta = meta
                .withMessageCount(meta.messageCount() + 1)
                .withLastActiveAt(LocalDateTime.now().format(TIME_FMT));
    }

    // @anchor: session_markRunning
    // 标记任务开始运行：记录 Agent 与线程，置状态为 RUNNING
    /**
     * 标记任务开始运行。
     */
    public synchronized void markRunning(Object agent) {
        this.runningAgent = agent;
        this.runningThread = Thread.currentThread();
        this.runningStartedAt = System.currentTimeMillis();
        this.lastHeartbeatTime = System.currentTimeMillis();
        this.state = SessionState.RUNNING;
        this.meta = meta.withState(SessionState.RUNNING);
    }

    // @anchor: session_markIdle
    // 标记任务结束（成功或失败）：清空任务引用并置状态为 IDLE
    /**
     * 标记任务结束（成功或失败）。
     */
    public synchronized void markIdle() {
        this.runningAgent = null;
        this.runningThread = null;
        this.runningStartedAt = 0;
        this.state = SessionState.IDLE;
        this.meta = meta.withState(SessionState.IDLE);
        touch();
    }
    // @anchor: session_getRunningStartedAt
    // 返回当前任务的开始时间戳（毫秒）；未运行时为 0
    public long getRunningStartedAt() {
        return runningStartedAt;
    }

    // @anchor: session_forceIdle
    // 强制将会话置为 IDLE，不依赖 runningAgent 是否存在；用于清理卡死会话
    public synchronized void forceIdle() {
        if (runningAgent != null) {
            try {
                runningAgent.getClass().getMethod("stop").invoke(runningAgent);
            } catch (Exception ignored) {}
        }
        if (runningThread != null && runningThread != Thread.currentThread()) {
            runningThread.interrupt();
        }
        this.runningAgent = null;
        this.runningThread = null;
        this.state = SessionState.IDLE;
        this.meta = meta.withState(SessionState.IDLE);
        touch();
    }

    // @anchor: session_stopTask
    // 停止当前任务：反射调用 Agent.stop 并中断运行线程（供 /stop 接口使用）
    /**
     * 停止当前任务。
     * 由 HTTP 层的 /stop 接口调用。
     */
    public synchronized void stopTask() {
        if (runningAgent == null) {
            // 僵尸状态：state 是 RUNNING 但 agent 已消失，直接归一化
            if (state == SessionState.RUNNING) {
                forceIdle();
            }
            return;
        }
        // 用反射/接口方式调用 stop，避免直接依赖 core.Main
        try {
            runningAgent.getClass().getMethod("stop").invoke(runningAgent);
        } catch (Exception e) {
            logger.warn("停止任务失败", e);
        }
        if (runningThread != null && runningThread != Thread.currentThread()) {
            runningThread.interrupt();
        }
    }

    // ==================== 心跳 ====================

    // @anchor: session_heartbeat
    // 记录本次心跳时间戳，用于判定会话是否存活
    public void heartbeat() {
        this.lastHeartbeatTime = System.currentTimeMillis();
    }

    // @anchor: session_getLastHeartbeatTime
    // 返回最近一次心跳时间戳
    public long getLastHeartbeatTime() {
        return lastHeartbeatTime;
    }

    // ==================== 日志转发 ====================

    // @anchor: session_addLogSink
    // 追加一个日志消费者，供 /stream 与 /run 同时订阅
    public void addLogSink(LogSink sink) {
        if (sink != null && !logSinks.contains(sink)) {
            logSinks.add(sink);
        }
    }

    // @anchor: session_removeLogSink
    // 移除指定日志消费者，SSE 断开时调用
    public void removeLogSink(LogSink sink) {
        logSinks.remove(sink);
    }

    // @anchor: session_log
    // 内部日志入口：有消费者则转发给 SSE，否则回退到本地日志
    /**
     * 内部日志入口，供 ContextManager 和 Main 调用。
     */
    public void log(String message) {
        // 服务端日志始终记录，便于排障
        logger.info(message);
        // 非阻塞投递给所有消费者；失效的消费者自摘
        logSinks.removeIf(sink -> !sink.offer(message));
    }

    // @anchor: session_restoreMeta
    // 从磁盘恢复时用：覆盖当前元数据并按元数据同步状态
    /**
     * 从磁盘恢复时用，覆盖当前 meta 和 state。
     * 仅在 SessionStorage.load() 中调用。
     */
    public void restoreMeta(SessionMeta meta) {
        // 磁盘上的 RUNNING 不代表运行时真相：进程已退出，任务不可能还在跑
        // 归一化为 IDLE，避免恢复后 isRunning() 恒真导致永久 409
        if (meta.state() == SessionState.RUNNING) {
            meta = meta.withState(SessionState.IDLE);
        }
        this.meta = meta;
        this.state = meta.state();
    }

    // ==================== Getter ====================

    // @anchor: session_getters
    // 会话只读访问器：ID、配置、元数据、状态、上下文与运行判定
    public String getSessionId() {
        return sessionId;
    }

    public AgentConfig getConfig() {
        return config;
    }

    public SessionMeta getMeta() {
        return meta;
    }

    public SessionState getState() {
        return state;
    }

    public ContextManager getContextManager() {
        return contextManager;
    }

    public boolean isRunning() {
        return state == SessionState.RUNNING;
    }

    public boolean hasActiveTask() {
        return runningAgent != null;
    }

    public String getWorkProject(){
        return meta.workProject();
    }
}

