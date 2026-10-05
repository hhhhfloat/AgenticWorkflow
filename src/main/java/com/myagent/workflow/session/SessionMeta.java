// @anchor: sessionMeta_intro
// 会话元数据模型：用于前端列表展示与磁盘持久化
package com.myagent.workflow.session;

// @anchor: sessionMeta_class
// 会话元数据 record：不可变，状态更新通过 with* 方法返回新实例
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
        SessionState state,
        String workProject
) {

    // @anchor: sessionMeta_withTitle
    // 返回替换标题后的新元数据实例
    public SessionMeta withTitle(String newTitle) {
        return new SessionMeta(sessionId, newTitle, createdAt, lastActiveAt, messageCount, state, workProject);
    }

    // @anchor: sessionMeta_withLastActiveAt
    // 返回更新最后活跃时间后的新元数据实例
    public SessionMeta withLastActiveAt(String newTime) {
        return new SessionMeta(sessionId, title, createdAt, newTime, messageCount, state, workProject);
    }

    // @anchor: sessionMeta_withMessageCount
    // 返回更新消息计数后的新元数据实例
    public SessionMeta withMessageCount(int newCount) {
        return new SessionMeta(sessionId, title, createdAt, lastActiveAt, newCount, state, workProject);
    }

    // @anchor: sessionMeta_withState
    // 返回更新会话状态后的新元数据实例
    public SessionMeta withState(SessionState newState) {
        return new SessionMeta(sessionId, title, createdAt, lastActiveAt, messageCount, newState, workProject);
    }

    // @anchor: sessionMeta_withWorkProject
    // 返回更新工作项目后的新元数据实例
    public SessionMeta withWorkProject(String newProject) {
        return new SessionMeta(sessionId, title, createdAt, lastActiveAt, messageCount, state, newProject);
    }
}
