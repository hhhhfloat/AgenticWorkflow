// @anchor: anchorEditDiagnostics_intro
// edit_between_anchors 的报错诊断：多处匹配展示上下文、零匹配给相似候选
package com.myagent.workflow.tools.anchor;

import com.myagent.workflow.model.AnchorLocation;

import java.util.ArrayList;
import java.util.List;

// @anchor: anchorEditDiagnostics_class
// 编辑器诊断工具：生成给模型看的友善报错信息
final class AnchorEditDiagnostics {
    private AnchorEditDiagnostics() {}

    // @anchor: anchorEditDiagnostics_buildAmbiguousError
    // 多处匹配：列出每处位置及前后各 2 行上下文，引导模型加长 old_string
    static String buildAmbiguousError(String intervalText, String oldString,
                                      List<Integer> matches,
                                      AnchorLocation startLoc, AnchorLocation endLoc,
                                      String effectiveEndAnchor) {
        StringBuilder sb = new StringBuilder();
        sb.append("❌ old_string 在锚点区间内匹配到 ").append(matches.size()).append(" 处，无法确定要替换哪一个。\n\n");
        sb.append("📍 区间范围：").append(startLoc.filePath)
                .append(" 行 ").append(startLoc.line).append("-").append(endLoc.line)
                .append("（startAnchor=[").append(startLoc.id).append("], endAnchor=[").append(effectiveEndAnchor).append("]）\n\n");
        sb.append("🔍 匹配位置（已附带前后文，`>` 标记为匹配行）：\n\n");

        String[] matchLines = oldString.split("\n", -1);
        int matchLineCount = matchLines.length;

        for (int i = 0; i < matches.size(); i++) {
            int idx = matches.get(i);
            int relLine = idxToLineNum(intervalText, idx);
            int absLine = startLoc.line + 1 + relLine;

            sb.append("  第 ").append(i + 1).append(" 处（行 ").append(absLine)
                    .append("-").append(absLine + matchLineCount - 1).append("）：\n");

            int contextBefore = 2;
            int contextAfter = 2;
            int startCtxLine = relLine - contextBefore;
            int endCtxLine = relLine + matchLineCount + contextAfter;

            for (int j = startCtxLine; j < endCtxLine; j++) {
                if (j < 0 || j >= intervalLinesCount(intervalText)) continue;

                String lineText = getLine(intervalText, j);
                boolean isMatch = (j >= relLine && j < relLine + matchLineCount);
                int actualFileLine = startLoc.line + 1 + j;

                sb.append(String.format("    %s %d | %s%n",
                        isMatch ? ">" : " ", actualFileLine, lineText));
            }
            sb.append("\n");
        }

        sb.append("💡 可选做法：\n");
        sb.append("  1. 在 old_string 中增加上下文（如把上一行 `for...` 或 `if...` 一起贴进来），让它在区间内唯一\n");
        sb.append("  2. 用更精确的锚点缩小范围\n");
        sb.append("  3. 若确实想整段替换，改用 delete_between_anchors + insert_at_anchor\n");
        return sb.toString();
    }

    // @anchor: anchorEditDiagnostics_buildNotFoundError
    // 零处匹配：给最相似的 3 个候选，并标注差异
    static String buildNotFoundError(List<String> intervalLines, String oldString,
                                     AnchorLocation startLoc, AnchorLocation endLoc) {
        StringBuilder sb = new StringBuilder();
        sb.append("❌ 在锚点区间内未找到 old_string 的完全匹配。\n\n");
        sb.append("📍 区间范围：").append(startLoc.filePath)
                .append(" 行 ").append(startLoc.line).append("-").append(endLoc.line)
                .append("（共 ").append(intervalLines.size()).append(" 行）\n\n");

        List<SimilarWindow> candidates = findTopSimilarWindows(intervalLines, oldString, 3);
        if (!candidates.isEmpty()) {
            sb.append("🔍 最接近的候选片段（按相似度排序）：\n\n");
            for (int i = 0; i < candidates.size(); i++) {
                SimilarWindow w = candidates.get(i);
                int absLine = startLoc.line + 1 + w.startLineIdx();
                sb.append("  候选 ").append(i + 1)
                        .append("（原文件行 ").append(absLine)
                        .append("-").append(absLine + w.lines().size() - 1)
                        .append("，相似度 ").append(Math.round(w.similarity * 100)).append("%）：\n");
                for (int j = 0; j < w.lines().size(); j++) {
                    sb.append(String.format("    %d | %s%n", absLine + j, w.lines().get(j)));
                }
                sb.append("\n");
            }
        }

        sb.append("💡 请先用 read_between_anchors 重新读取该区间，从返回内容中精确复制 old_string（含缩进）。\n");
        return sb.toString();
    }

    // ===== 内部辅助 =====

    private record SimilarWindow(int startLineIdx, List<String> lines, double similarity) {}

    private static List<SimilarWindow> findTopSimilarWindows(
            List<String> intervalLines, String oldString, int limit) {
        String[] oldLines = oldString.split("\n", -1);
        int windowSize = oldLines.length;
        if (windowSize == 0 || intervalLines.size() < windowSize) return List.of();

        List<SimilarWindow> all = new ArrayList<>();
        for (int i = 0; i + windowSize <= intervalLines.size(); i++) {
            List<String> window = intervalLines.subList(i, i + windowSize);
            String windowText = String.join("\n", window);
            int dist = levenshtein(windowText, oldString);
            int maxLen = Math.max(windowText.length(), oldString.length());
            double similarity = maxLen == 0 ? 1.0 : 1.0 - (double) dist / maxLen;
            all.add(new SimilarWindow(i, new ArrayList<>(window), similarity));
        }

        return all.stream()
                .filter(w -> w.similarity() >= 0.5)
                .sorted((a, b) -> Double.compare(b.similarity(), a.similarity()))
                .limit(limit)
                .toList();
    }

    private static int intervalLinesCount(String text) {
        return text.split("\n", -1).length;
    }

    private static String getLine(String text, int lineNum) {
        String[] lines = text.split("\n", -1);
        if (lineNum >= 0 && lineNum < lines.length) return lines[lineNum];
        return "";
    }

    static List<Integer> findAllOccurrences(String text, String target) {
        List<Integer> result = new ArrayList<>();
        if (target.isEmpty()) return result;
        int from = 0;
        while (true) {
            int idx = text.indexOf(target, from);
            if (idx < 0) break;
            result.add(idx);
            from = idx + target.length();
        }
        return result;
    }

    private static int idxToLineNum(String text, int idx) {
        int lineNum = 0;
        for (int i = 0; i < idx; i++) {
            if (text.charAt(i) == '\n') lineNum++;
        }
        return lineNum;
    }

    // 标准编辑距离
    private static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }
}