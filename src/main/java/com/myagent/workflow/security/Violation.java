// @anchor: violation_tot_desc
// 单条违规记录模型：描述命中的规则、位置、文本与修复建议
package com.myagent.workflow.security;

// @anchor: violation_class
// 违规记录 record：包含文件路径、行号、规则 ID、命中文本、级别与修复建议
public record Violation(
        String filePath,      // ✅ 新增：违规文件路径
        int lineNumber,
        String ruleId,
        String matchedText,
        Severity severity,
        String suggestion
) {}
