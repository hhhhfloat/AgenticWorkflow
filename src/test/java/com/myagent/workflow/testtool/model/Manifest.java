package com.myagent.workflow.testtool.model;

import java.util.List;

public record Manifest(
        RunMeta meta,
        List<ManifestGroupSummary> groups,   // ← 类型改了
        Verdict verdict
) {}