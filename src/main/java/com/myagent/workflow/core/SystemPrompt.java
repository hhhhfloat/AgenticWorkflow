// @anchor: systemPrompt_tot_desc
// 系统提示词仓库：集中存放交给 Agent 的行为规范与工作流程说明
package com.myagent.workflow.core;

// @anchor: systemPrompt_class
// 系统提示词 —— 定义 Agent 的行为规则。
// 从 Main.java 中独立出来，便于单独维护和版本管理。
public final class SystemPrompt {

    private SystemPrompt() {
        // 工具类，禁止实例化
    }

    // @anchor: systemPrompt_get
    // 返回 Agent 系统提示词全文
    public static String get() {
        return PROMPT;
    }

    private static final String PROMPT = """
        你是全栈开发工程师，生成离线工具。

        
        """;



}
