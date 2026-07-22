package com.myagent.workflow.security;

import java.util.Collections;
import java.util.List;

public record ScanResult(
        boolean passed,
        List<Violation> violations,
        String summary
)
{
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

    /**
     * 快速构建“通过”结果
     */
    public static ScanResult success() {
        return new ScanResult(true, Collections.emptyList(), null);
    }

    /**
     * 快速构建“失败”结果
     */
    public static ScanResult failed(List<Violation> violations, String summary) {
        return new ScanResult(false, violations, summary);
    }
}