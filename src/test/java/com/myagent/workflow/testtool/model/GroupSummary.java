package com.myagent.workflow.testtool.model;

import java.util.List;

public record GroupSummary(
        String id,                      // "A" 或 "B"
        String label,                   // "min=5, max=15" 或 "压缩关闭"
        ThresholdConfig thresholds,
        int totalIterations,
        double totalCost,
        int compressionCount,
        List<Integer> compressionRounds, // 实际触发压缩的轮次列表
        double cacheHitRate,
        int workingSizePeak             // 工作区峰值字符数（从日志中提取）
) {}