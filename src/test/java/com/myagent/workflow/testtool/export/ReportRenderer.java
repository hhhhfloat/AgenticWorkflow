package com.myagent.workflow.testtool.export;

import com.myagent.workflow.testtool.model.IterationData;
import com.myagent.workflow.testtool.model.ReportData;
import com.myagent.workflow.testtool.model.ReportGroupSummary;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Markdown 报告渲染器
 */
public class ReportRenderer {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static String render(ReportData data) {
        StringBuilder sb = new StringBuilder();

        // ----- 头部 -----
        sb.append("# 压缩时机测试报告\n\n");
        sb.append("> **Run ID**: `").append(data.meta().runId()).append("`\n");
        sb.append("> **时间**: ").append(data.meta().timestamp()).append("\n");
        sb.append("> **模型**: ").append(data.meta().model()).append("\n");
        sb.append("> **最大迭代**: ").append(data.meta().maxIterations()).append("\n\n");

        // ----- 测试目标 -----
        sb.append("## 📌 测试目标\n\n");
        sb.append("对比不同压缩阈值（MIN/MAX）对同一任务完成效率、成本及上下文管理的影响。\n\n");

        // ----- 核心指标对比表 -----
        sb.append("## 📊 核心指标对比\n\n");
        sb.append("| 组别 | 阈值 | 总轮数 | 总成本(¥) | 压缩次数 | 触发轮次 | 缓存命中率 |\n");
        sb.append("|------|------|--------|-----------|----------|----------|------------|\n");

        for (ReportGroupSummary g : data.groups()) {
            String roundsStr = g.compressionRounds().isEmpty()
                    ? "-"
                    : g.compressionRounds().stream().map(String::valueOf).collect(Collectors.joining(", "));
            sb.append("| ").append(g.id())
                    .append(" | ").append(g.thresholds().label())
                    .append(" | ").append(g.totalIterations())
                    .append(" | ").append(String.format("%.6f", g.totalCost()))
                    .append(" | ").append(g.compressionCount())
                    .append(" | ").append(roundsStr)
                    .append(" | ").append(String.format("%.2f%%", g.cacheHitRate()))
                    .append(" |\n");
        }
        sb.append("\n");

        // ----- 结论 -----
        sb.append("## 🏆 结论\n\n");
        sb.append("**推荐阈值**: ").append(data.verdict().betterGroup()).append("\n\n");
        sb.append("**理由**: ").append(data.verdict().reason()).append("\n\n");

        // ----- 逐轮差分 -----
        List<IterationData> iterA = data.iterationsA();
        List<IterationData> iterB = data.iterationsB();
        int maxRounds = Math.max(iterA.size(), iterB.size());

        if (maxRounds > 0) {
            sb.append("## 🔍 逐轮 Token 消耗差分\n\n");
            sb.append("| 轮次 | 压缩开启 Token | 压缩关闭 Token | 差值 |\n");
            sb.append("|------|----------------|----------------|------|\n");
            for (int i = 0; i < maxRounds; i++) {
                long tA = i < iterA.size() ? iterA.get(i).promptTokens() + iterA.get(i).completionTokens() : 0;
                long tB = i < iterB.size() ? iterB.get(i).promptTokens() + iterB.get(i).completionTokens() : 0;
                long diff = tA - tB;
                String diffStr = diff == 0 ? "0" : (diff > 0 ? "+" + diff : String.valueOf(diff));
                sb.append("| ").append(i + 1)
                        .append(" | ").append(tA)
                        .append(" | ").append(tB)
                        .append(" | ").append(diffStr)
                        .append(" |\n");
            }
            sb.append("\n");
        }

        // ----- 数据来源 -----
        sb.append("## 📎 原始数据\n\n");
        sb.append("- 逐轮明细 CSV: `raw_data/group_a_iterations.csv`, `raw_data/group_b_iterations.csv`\n");
        sb.append("- 完整配置: `configs/thresholds.json`\n");
        sb.append("- 存档索引: `0_manifest.json`\n");

        return sb.toString();
    }
}