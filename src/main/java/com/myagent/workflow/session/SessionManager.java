// @anchor: sessionManager_tot_desc
// 会话管理器：内存活跃会话容器 + 磁盘存储协调者，并以信号量控制全局串行
package com.myagent.workflow.session;

import com.myagent.workflow.core.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

// @anchor: sessionManager_class
// 会话管理器：提供创建/获取/切换/关闭会话接口，协调内存映射与落盘
/**
 * 会话管理器 —— 内存会话的容器 + 磁盘存储的协调者。
 * <p>
 * 职责：
 * 1. 管理活跃会话的内存映射
 * 2. 提供创建/获取/切换/关闭会话的接口
 * 3. 通过信号量控制全局并发（当前 N=1，全局串行）
 * 4. 在合适的时机触发会话落盘
 * <p>
 * 并发模型：
 * - activeSessions 是 ConcurrentHashMap，所有读写线程安全
 * - 任务的并发由 runningPermits 控制：同一时刻只有一个任务能运行
 * - 会话切换不涉及线程调度，只涉及内存/磁盘的搬运
 */
public class SessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);

    // @anchor: sessionManager_maxConcurrent
    // 最大同时运行的会话数（当前为 1，全局串行）
    /** 最大同时运行的会话数（当前为 1，全局串行） */
    private static final int MAX_CONCURRENT_TASKS = 1;

    // @anchor: sessionManager_fields
    // 活跃会话内存映射、磁盘存储与运行许可信号量
    private final Map<String, Session> activeSessions = new ConcurrentHashMap<>();
    private final SessionStorage storage;
    private final Semaphore runningPermits = new Semaphore(MAX_CONCURRENT_TASKS);

    // @anchor: sessionManager_constructor
    // 构造管理器并初始化磁盘存储
    public SessionManager() {
        this.storage = new SessionStorage();
    }

    // ==================== 创建 ====================

    // @anchor: sessionManager_create
    // 创建新会话：加入内存并立即落盘
    /**
     * 创建一个新会话，自动落盘并加入内存。
     */
    public Session create(AgentConfig config) {
        Session session = new Session(config);
        activeSessions.put(session.getSessionId(), session);
        try {
            storage.save(session);
        } catch (IOException e) {
            logger.error("创建会话时落盘失败", e);
        }
        logger.info("📂 新会话已创建: {}", session.getSessionId());
        return session;
    }

    // ==================== 获取 ====================

    // @anchor: sessionManager_getOrLoad
    // 按 sessionId 获取会话：优先内存，未命中则从磁盘加载到内存
    /**
     * 按 sessionId 获取会话。
     * 优先从内存取；如果不在内存，从磁盘加载到内存。
     */
    public Session get(String sessionId, AgentConfig config) {
        Session session = activeSessions.get(sessionId);
        if (session != null) {
            return session;
        }
        // 内存中没有，尝试从磁盘加载
        try {
            session = storage.load(sessionId, config);
            activeSessions.put(sessionId, session);
            logger.info("📂 会话已从磁盘加载: {}", sessionId);
            return session;
        } catch (IOException e) {
            logger.warn("无法加载会话 {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    // @anchor: sessionManager_getFromMemory
    // 仅从内存映射中获取会话（不触发磁盘加载）
    public Session get(String sessionId) {
        return activeSessions.get(sessionId);
    }

    // ==================== 切换 ====================

    // @anchor: sessionManager_switchTo
    // 切换会话：存在运行中任务时抛出异常，否则确保目标会话在内存并返回
    /**
     * 切换会话。
     * <p>
     * 语义：
     * - 从 fromId 的会话如果没有运行任务，直接离开（不落盘，因为它随时在内存里）
     * - 要进入的 toId 会话如果不在内存，从磁盘加载
     * <p>
     * 返回目标会话。
     */
    public Session switchTo(String sessionId, AgentConfig config) throws IOException {
        // 检查当前是否有任务在跑
        Session running = findRunningSession();
        if (running != null && !running.getSessionId().equals(sessionId)) {
            throw new IOException(
                    "另一个会话正在运行（" + running.getSessionId() + "），请先停止后再切换。");
        }

        Session target = get(sessionId, config);
        if (target == null) {
            throw new IOException("会话不存在: " + sessionId);
        }
        return target;
    }

    // @anchor: sessionManager_findRunningSession
    // 查找当前处于运行状态的会话
    private Session findRunningSession() {
        for (Session s : activeSessions.values()) {
            if (s.isRunning()) return s;
        }
        return null;
    }

    // ==================== 关闭 ====================

    // @anchor: sessionManager_save
    // 显式将指定会话落盘，保证跨重启持久化
    /**
     * 显式保存会话到磁盘。
     * 由 RunHandler 在任务结束后调用，保证跨重启的持久化。
     */
    public void save(Session session) {
        try {
            storage.save(session);
        } catch (IOException e) {
            logger.error("会话落盘失败: {}", session.getSessionId(), e);
        }
    }

    // @anchor: sessionManager_close
    // 关闭会话：运行中先停止、压缩日志并等待空闲，然后落盘并从内存移除
    /**
     * 关闭会话：落盘后从内存移除。
     * 如果会话正在运行，先停止。
     */
    public void close(String sessionId) {
        Session session = activeSessions.get(sessionId);
        if (session == null) return;

        if (session.isRunning()) {
            session.stopTask();
            if(session.getContextManager() != null){
                session.getContextManager().compressRawLog();
            }
            // 等待任务自然退出（最多 5 秒）
            waitForIdle(session, 5000);
        }

        try {
            storage.save(session);
            logger.info("💾 会话已落盘: {}", sessionId);
        } catch (IOException e) {
            logger.error("会话落盘失败: {}", sessionId, e);
        }
        activeSessions.remove(sessionId);
    }

    // @anchor: sessionManager_waitForIdle
    // 轮询等待会话退出运行状态，超时即返回
    private void waitForIdle(Session session, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (session.isRunning() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ==================== 并发控制 ====================

    // @anchor: sessionManager_acquireRunningPermit
    // 尝试获取运行许可，成功表示可以开始执行任务
    /**
     * 尝试获取运行许可。
     * 获取成功则调用方可以开始执行任务；失败说明已有任务在跑。
     */
    public boolean acquireRunningPermit() {
        return runningPermits.tryAcquire();
    }

    // @anchor: sessionManager_releaseRunningPermit
    // 释放运行许可，允许下一个任务执行
    public void releaseRunningPermit() {
        runningPermits.release();
    }

    // ==================== 列表 / 查询 ====================

    // @anchor: sessionManager_listAll
    // 返回磁盘上所有会话的元数据列表
    public List<SessionMeta> listAll() {
        return storage.listAll();
    }

    // @anchor: sessionManager_listActive
    // 返回当前内存中的所有活跃会话
    public List<Session> listActive() {
        return List.copyOf(activeSessions.values());
    }

    // @anchor: sessionManager_exists
    // 判断会话是否存在于内存或磁盘
    public boolean exists(String sessionId) {
        return activeSessions.containsKey(sessionId) || storageHas(sessionId);
    }

    // @anchor: sessionManager_storageHas
    // 在磁盘元数据索引中查找指定会话是否存在
    private boolean storageHas(String sessionId) {
        for (SessionMeta m : storage.listAll()) {
            if (m.sessionId().equals(sessionId)) return true;
        }
        return false;
    }

    // ==================== 生命周期 ====================

    // @anchor: sessionManager_shutdown
    // 优雅关闭：停止运行中任务并将所有活跃会话落盘后清空内存
    /**
     * 优雅关闭：落盘所有活跃会话。
     */
    public void shutdown() {
        for (Session session : activeSessions.values()) {
            if (session.isRunning()) {
                session.stopTask();
                if(session.getContextManager() != null){
                    session.getContextManager().compressRawLog();
                }
                waitForIdle(session, 5000);
            }
            try {
                storage.save(session);
            } catch (IOException e) {
                logger.error("关闭时落盘失败: {}", session.getSessionId(), e);
            }
        }
        activeSessions.clear();
        logger.info("📂 SessionManager 已关闭");
    }
}
