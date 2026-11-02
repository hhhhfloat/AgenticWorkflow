// @anchor: anchorResolver_intro
// 锚点定位：从 ID + fileHint 解析到具体行号，失败时给相似建议
package com.myagent.workflow.tools.anchor;

import com.myagent.workflow.model.AnchorLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// @anchor: anchorResolver_class
// 锚点解析器：封装解析逻辑与错误信息生成
public class AnchorResolver {
    private static final Logger logger = LoggerFactory.getLogger(AnchorResolver.class);

    private final AnchorQuery anchorQuery;
    private final String workProject;

    // @anchor: anchorResolver_constructor
    public AnchorResolver(AnchorQuery anchorQuery, String workProject) {
        this.anchorQuery = anchorQuery;
        this.workProject = workProject;
    }

    // @anchor: anchorResolver_ResolveResult
    // 锚点解析结果：成功时 location 非空，失败时 error 携带可读错误信息。
    public record ResolveResult(AnchorLocation location, String error) {
        public static ResolveResult ok(AnchorLocation loc) { return new ResolveResult(loc, null); }
        public static ResolveResult fail(String error) { return new ResolveResult(null, error); }
        public boolean isOk() { return location != null; }
    }

    // @anchor: anchorResolver_resolveOrError
    // 解析锚点并直接携带错误信息：唯一命中返回成功；零命中/多命中返回可读错误。
    public ResolveResult resolveOrError(String anchorId, String fileHint) {
        List<AnchorLocation> candidates = resolveAnchorCandidates(anchorId, fileHint);
        if (candidates.size() == 1) {
            return ResolveResult.ok(candidates.get(0));
        }
        return ResolveResult.fail(buildResolveError(anchorId, fileHint, candidates));
    }

    // @anchor: anchorResolver_resolveAnchorCandidates
    // 获取锚点的全部候选位置。fileHint 非空时只看该文件（宽松匹配），否则全局。
    private List<AnchorLocation> resolveAnchorCandidates(String anchorId, String fileHint) {
        if (fileHint == null || fileHint.isBlank()) {
            return anchorQuery.findAllGlobally(anchorId, workProject);
        }
        return anchorQuery.findAllInFile(anchorId, fileHint, workProject);
    }

    // @anchor: anchorResolver_buildResolveError
    // 生成锚点解析失败的错误信息。四分类 + 多命中歧义。
    private String buildResolveError(String anchorId, String fileHint, List<AnchorLocation> candidates) {
        boolean hasFileHint = fileHint != null && !fileHint.isBlank();
        String scope = workProject == null
                ? "沙箱全部项目"
                : "当前工作项目 [" + workProject + "]";

        // ===== 多命中：告知歧义，让模型提供更精确的路径 =====
        if (candidates.size() > 1) {
            StringBuilder sb = new StringBuilder();
            if (hasFileHint) {
                sb.append("❌ 文件 ").append(fileHint).append(" 中有 ")
                        .append(candidates.size()).append(" 处匹配锚点 ")
                        .append(anchorId).append("：\n");
            } else {
                sb.append("❌ 锚点 '").append(anchorId).append("' 在 ")
                        .append(candidates.size()).append(" 个文件中出现：\n");
            }
            for (AnchorLocation loc : candidates) {
                sb.append("   - ").append(loc.projectPath).append("/").append(loc.filePath)
                        .append(" (L").append(loc.line).append(")\n");
            }
            if (hasFileHint) {
                sb.append("  💡 请提供更精确的 file 路径（含子目录）。");
            } else {
                sb.append("请在 file 参数中指定目标文件。\n");
                sb.append("建议：后续避免跨文件使用相同锚点 ID。");
            }
            return sb.toString();
        }

        // ===== 零命中且指定了 file：先查全局是否有其他位置 =====
        if (hasFileHint) {
            List<AnchorLocation> elsewhere = anchorQuery.findAllGlobally(anchorId, workProject);
            if (!elsewhere.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("❌ 文件 ").append(fileHint).append(" 中没有锚点: ")
                        .append(anchorId).append("\n");
                sb.append("  但该锚点在以下位置存在：\n");
                for (AnchorLocation loc : elsewhere) {
                    sb.append("   - ").append(loc.projectPath).append("/").append(loc.filePath)
                            .append(" (L").append(loc.line).append(")\n");
                }
                sb.append("  💡 请检查 file 参数是否正确，或在上述位置之一查找。");
                return sb.toString();
            }

            // 全局也没有：尝试给出该文件中的相似锚点
            List<AnchorLocation> anchorsInFile = anchorQuery.listAnchorsInFile(fileHint, workProject);
            List<AnchorLocation> similar = findSimilarAnchors(anchorId, anchorsInFile, 5);
            if (!similar.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("❌ 文件 ").append(fileHint).append(" 中没有锚点: ")
                        .append(anchorId).append("\n");
                sb.append("  该文件中相似的锚点：\n");
                for (AnchorLocation loc : similar) {
                    sb.append("  ").append(loc.id)
                            .append(" (L").append(loc.line).append(")\n");
                }
                return sb.toString();
            }
        }

        // ===== 零命中且全局也没有 =====
        StringBuilder sb = new StringBuilder();
        sb.append("❌ 在").append(scope).append(" 中未找到锚点: ").append(anchorId).append("\n");
        if (hasFileHint) {
            sb.append("  已搜索文件 ").append(fileHint).append(" 和").append(scope).append("的全部文件。\n");
        }
        sb.append("  💡先 describe_anchors 确认锚点 id；\n");
        return sb.toString();
    }

    // @anchor: anchorResolver_findSimilarAnchors
    // 在候选锚点中按 ID 相似度找前 limit 个。编辑距离 + 公共前缀加权：
    private static List<AnchorLocation> findSimilarAnchors(
            String target, List<AnchorLocation> candidates, int limit) {
        if (candidates.isEmpty()) return List.of();
        int threshold = Math.max(3, target.length() / 3);
        record Scored(AnchorLocation loc, int score) {}
        List<Scored> scored = new ArrayList<>();
        for (AnchorLocation loc : candidates) {
            if (loc.id == null) continue;
            int dist = levenshtein(target, loc.id);
            if (dist > threshold) continue;
            int prefix = commonPrefixLength(target, loc.id);
            // 短 ID 保护：长度 < 8 且公共前缀 < 2，视为无关匹配，跳过
            if (target.length() < 8 && prefix < 2) continue;
            // 距离权重 10，公共前缀权重 1：距离每多 1 扣 10 分，前缀每多 1 加 1 分
            int score = dist * 10 - prefix;
            scored.add(new Scored(loc, score));
        }
        scored.sort(Comparator.comparingInt(Scored::score));
        return scored.stream().limit(limit).map(Scored::loc).toList();
    }

    // @anchor: anchorResolver_levenshtein
    // 标准编辑距离（插入/删除/替换各计 1）
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

    // @anchor: anchorResolver_commonPrefixLength
    // 两个字符串的最长公共前缀长度
    private static int commonPrefixLength(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            if (a.charAt(i) != b.charAt(i)) return i;
        }
        return n;
    }
}