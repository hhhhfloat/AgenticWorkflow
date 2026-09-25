package com.myagent.workflow.model;

// @anchor: anchorSummary_class
// 锚点摘要 record：结构解析产物中的锚点（id/行号/预览）
public record AnchorSummary(
        String id,
        int line,
        String preview
) {}
