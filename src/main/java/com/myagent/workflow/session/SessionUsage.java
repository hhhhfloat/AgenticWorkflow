// @anchor: sessionUsage_tot_desc
// 会话级累计用量：prompt/cached/completion 与成本
package com.myagent.workflow.session;

// @anchor: sessionUsage_class
// 可累加的用量记录（不可变 record）
public record SessionUsage(
        long promptTokens,
        long cachedTokens,
        long completionTokens,
        int apiCalls,
        double cost
) {
    public static SessionUsage empty() {
        return new SessionUsage(0, 0, 0, 0, 0);
    }

    public SessionUsage add(SessionUsage other) {
        if (other == null) return this;
        return new SessionUsage(
                promptTokens + other.promptTokens,
                cachedTokens + other.cachedTokens,
                completionTokens + other.completionTokens,
                apiCalls + other.apiCalls,
                cost + other.cost
        );
    }
}