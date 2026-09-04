package com.myagent.workflow.testtool.model;

/**
 * 单轮迭代数据 —— 用于实时折线图
 */
public record IterationData(
        int iteration,
        long promptTokens,
        long cachedTokens,
        long completionTokens,
        double cumulativeHitRate,   // 累计命中率（原 cacheHitRate）
        double currentRoundHitRate, // 🆕 本轮命中率
        double cost
) {
    // 保留原有方法名，返回累计命中率
    public double cacheHitRate() {
        return cumulativeHitRate;
    }

    // 🆕 新增方法：返回本轮命中率
    public double getCurrentRoundHitRate() {
        return currentRoundHitRate;
    }
}