package com.myagent.workflow.session;

/**
 * 会话元数据 —— 用于前端列表展示和磁盘持久化。
 * <p>
 * 这是一个不可变 Record，状态更新通过 with* 方法返回新实例。
 * 真正可变的运行时状态（runningAgent、lastHeartbeatTime）不放这里，
 * 那些属于 Session 实例的瞬时状态。
 */
public record SessionMeta(
        String sessionId,
        String title,
        String createdAt,
        String lastActiveAt,
        int messageCount,
        SessionState state
) {

    public SessionMeta withTitle(String newTitle) {
        return new SessionMeta(sessionId, newTitle, createdAt, lastActiveAt, messageCount, state);
    }

    public SessionMeta withLastActiveAt(String newTime) {
        return new SessionMeta(sessionId, title, createdAt, newTime, messageCount, state);
    }

    public SessionMeta withMessageCount(int newCount) {
        return new SessionMeta(sessionId, title, createdAt, lastActiveAt, newCount, state);
    }

    public SessionMeta withState(SessionState newState) {
        return new SessionMeta(sessionId, title, createdAt, lastActiveAt, messageCount, newState);
    }
}