package com.myagent.workflow.session;

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