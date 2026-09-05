package com.myagent.workflow.testtool.model;

import java.util.List;

public record ReportData(
        RunMeta meta,
        List<ReportGroupSummary> groups,
        Verdict verdict,
        List<IterationData> iterationsA,
        List<IterationData> iterationsB
) {}