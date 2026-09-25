// @anchor: scanResult_tot_desc
// 安全扫描结果模型：汇总是否通过、违规列表与摘要文本
package com.myagent.workflow.security;

import java.util.Collections;
import java.util.List;

// @anchor: scanResult_class
// 扫描结果 record：承载通过标志、违规明细与摘要，并负责格式化可读报告
public record ScanResult(
        boolean passed,
        List<Violation> violations,
        String summary
)
{
    // @anchor: scanResult_getFormattedReport
    // 将扫描结果渲染为带文件路径、行号、规则与建议的可读文本报告
    public String getFormattedReport() {
        if (violations.isEmpty()) {
            return "✅ 安全扫描通过";
        }
        StringBuilder sb = new StringBuilder("❌ 发现 " + violations.size() + " 项违规：\n");
        for (Violation v : violations) {
            sb.append("  [文件: ").append(v.filePath())   // ✅ 新增文件路径显示
                    .append(" 行 ").append(v.lineNumber()).append("] ")
                    .append(v.ruleId()).append(": ")
                    .append(v.matchedText()).append("\n")
                    .append("    💡 ").append(v.suggestion()).append("\n");
        }
        if (summary != null && !summary.isEmpty()) {
            sb.append("\n📌 ").append(summary);
        }
        return sb.toString();
    }

    // @anchor: scanResult_success
    // 快速构建“通过”结果（无违规）
    /**
     * 快速构建“通过”结果
     */
    public static ScanResult success() {
        return new ScanResult(true, Collections.emptyList(), null);
    }

    // @anchor: scanResult_failed
    // 快速构建“失败”结果（携带违规列表与摘要）
    /**
     * 快速构建“失败”结果
     */
    public static ScanResult failed(List<Violation> violations, String summary) {
        return new ScanResult(false, violations, summary);
    }
}
