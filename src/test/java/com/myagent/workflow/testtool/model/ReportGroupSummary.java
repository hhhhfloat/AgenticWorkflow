package com.myagent.workflow.testtool.model;

import java.util.List;

public record ReportGroupSummary(
        String id,
        String label,
        ThresholdConfig thresholds,
        int totalIterations,
        double totalCost,
        int compressionCount,
        List<Integer> compressionRounds,
        double cacheHitRate,
        int workingSizePeak
) {
    public boolean isValid() {
        return totalIterations > 0;
    }
}