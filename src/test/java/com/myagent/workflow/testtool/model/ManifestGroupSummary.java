package com.myagent.workflow.testtool.model;

import java.util.List;

/**
 * Manifest 的 groups 数组元素 —— 专用于 0_manifest.json 序列化
 */
public record ManifestGroupSummary(
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