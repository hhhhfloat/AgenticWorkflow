package com.myagent.workflow.testtool.model;

public record RunMeta(
        String runId,
        String timestamp,
        String prompt,
        String model,
        int maxIterations
) {}