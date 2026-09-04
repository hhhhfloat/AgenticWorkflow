package com.myagent.workflow.testtool.model;

public record ThresholdConfig(
        boolean compressionEnabled,
        Integer minInterval,   // null 表示未启用压缩
        Integer maxInterval    // null 表示未启用压缩
) {}