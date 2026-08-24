package com.myagent.workflow.testtool.model;

/**
 * 测试结果 —— 单次测试的完整数据
 */
public record TestResult(
        String testName,
        String projectName,              // ← 新增
        int totalIterations,
        long totalPromptTokens,
        long totalCachedTokens,
        long totalCompletionTokens,
        double totalCost,
        double cacheHitRate,
        int compressionCount,
        int roundsSinceLastCheckpoint
) {
    // 空结果（用于错误情况）
    public static final TestResult EMPTY = new TestResult(
            "空", "", 0, 0, 0, 0, 0.0, 0.0, 0, 0
    );

    // 判断是否有效（有实际数据）
    public boolean isValid() {
        return totalCost > 0 && totalIterations > 0;
    }

    @Override
    public String toString() {
        return String.format(
                "项目: %s | 迭代: %d轮 | 输入: %d | 命中率: %.2f%% | 成本: ¥%.6f",
                projectName, totalIterations, totalPromptTokens, cacheHitRate, totalCost
        );
    }
}