package com.myagent.workflow.testtool.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ThresholdConfig(
        boolean compressionEnabled,
        Integer minInterval,
        Integer maxInterval
) {
    public static ThresholdConfig from(boolean enabled, int min, int max) {
        return new ThresholdConfig(enabled, enabled ? min : null, enabled ? max : null);
    }

    public static ThresholdConfig disabled() {
        return new ThresholdConfig(false, null, null);
    }

    public String label() {
        if (!compressionEnabled) return "压缩关闭";
        return "min=" + minInterval + ", max=" + maxInterval;
    }
}