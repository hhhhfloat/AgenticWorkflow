// @anchor: iterationGuard_intro
// 迭代上限预警：在工具结果末尾注入收尾提醒，避免最后一轮硬截断
package com.myagent.workflow.core;

/**
 * 迭代上限守卫 —— 在接近最大迭代次数时，通过工具结果注入收尾提醒。
 * <p>
 * 为什么用 tool 消息注入，而不是 system/user：
 * - system 消息会被服务端重排（前置），破坏 prompt cache 前缀
 * - user 消息会诱发"回复用户"惯性，破坏工具调用循环
 * - tool 消息是"环境反馈"，模型天然接受且不破坏协议
 */
public final class IterationGuard {
    private IterationGuard() {}

    // @anchor: iterationGuard_getWarning
    // 根据当前迭代位置返回警告文本；未触发阈值时返回 null
    public static String getWarning(int currentIteration, int maxIterations) {
        int shownIteration = currentIteration + 1;  // 1-based 显示
        int remaining = maxIterations - shownIteration;

        if (remaining <= 1) {
            return "\n\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                    "⚠️ 【系统提示】这是第 " + shownIteration + " 次迭代，也是**最后一次**。\n" +
                    "请立即停止所有工具调用，直接输出 <snapshot> 任务交付，包含：\n" +
                    "- MISSION_REPORT\n" +
                    "- PROJECT_STATE_SNAPSHOT（TOTAL_GOAL / COMPLETED / NEXT_STEP / DIRTY_FILES）\n" +
                    "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━";
        }
        if (remaining <= 5) {
            return "\n\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                    "⚠️ 【系统提示】第 " + shownIteration + " 次迭代，剩余 " + remaining + " 次机会。\n" +
                    "请立即执行收尾动作：\n" +
                    "1. 停止探索（不要再发起新的读取或搜索）\n" +
                    "2. 更新 TODO.md：标记已完成 ✅，未完成项写明下一步\n" +
                    "3. 输出 <snapshot> 任务交付\n" +
                    "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━";
        }
        if (remaining <= 10) {
            return "\n\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                    "💡 【系统提示】第 " + shownIteration + " 次迭代，剩余 " + remaining + " 次机会。\n" +
                    "请开始规划收尾：确认已完成的工作，将未完成事项整理进 TODO.md，为下一步交付做准备。\n" +
                    "避免发起新的、耗时的探索。\n" +
                    "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━";
        }
        return null;
    }
}