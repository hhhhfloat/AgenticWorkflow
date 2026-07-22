package com.myagent.workflow.security;

public record Violation(
        String filePath,      // ✅ 新增：违规文件路径
        int lineNumber,
        String ruleId,
        String matchedText,
        Severity severity,
        String suggestion
) {}