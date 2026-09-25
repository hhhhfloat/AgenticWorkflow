// @anchor: sessionState_tot_desc
// 会话状态枚举：描述会话的空闲/运行/挂起/归档四种状态
package com.myagent.workflow.session;

// @anchor: sessionState_class
// 会话状态枚举：IDLE 空闲、RUNNING 运行、PAUSED 挂起（预留）、ARCHIVED 归档
/**
 * 会话状态。
 * <p>
 * - IDLE:     空闲，等待用户输入
 * - RUNNING:  正在执行任务
 * - PAUSED:   被抢占/挂起（预留，用于未来支持并行会话时的抢占机制）
 * - ARCHIVED: 已归档，仅存在于磁盘，不在内存
 */
public enum SessionState {
    IDLE,
    RUNNING,
    PAUSED,
    ARCHIVED
}
