// @anchor: anchorFormatter_intro
// 锚点描述输出：项目/目录/文件的锚点清单渲染
package com.myagent.workflow.tools.anchor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.tools.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

// @anchor: anchorFormatter_class
// 锚点描述输出：项目/目录/文件的锚点清单渲染
class AnchorFormatter {
    private static final Logger logger = LoggerFactory.getLogger(AnchorFormatter.class);
    private static final String PROJECT_INDEX_NAME = ".project_index.json";

    private final ObjectMapper objectMapper;
    // @anchor: anchorFormatter_maxDisplayed
// 单文件锚点列表的展示上限；超出时保留首锚点 + 最后 (N-1) 条
    private static final int MAX_DISPLAYED_ANCHORS = 30;

    AnchorFormatter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // @anchor: anchorFormatter_describe
    // 描述锚点：filePath 逗号分隔多个片段；目录 → _intro 概览，文件 → 全部锚点
    String describe(String projectPath, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return "❌ 必须指定 file 参数（目录路径或文件名，多个用逗号分隔）";
        }
        try {
            Path projectDir = PathUtils.safeResolve(projectPath);
            if (!Files.exists(projectDir) || !Files.isDirectory(projectDir)) {
                return "❌ 项目目录不存在: " + projectPath;
            }
            Path indexFile = projectDir.resolve(PROJECT_INDEX_NAME);
            if (!Files.exists(indexFile)) {
                return "📌 项目 " + projectPath + " 尚无项目索引。请先运行 build_anchor_index。";
            }

            String content = Files.readString(indexFile, StandardCharsets.UTF_8);
            Map<String, List<Map<String, Object>>> projectAnchors =
                    objectMapper.readValue(content, new TypeReference<>() {});

            StringBuilder sb = new StringBuilder();
            for (String raw : filePath.split("[,;]")) {
                String hint = raw.trim();
                if (hint.isEmpty()) continue;
                appendDescribeOne(sb, normalizeHint(hint, projectPath), projectAnchors, projectDir);
            }
            return sb.toString();
        } catch (IOException e) {
            logger.error("描述锚点失败", e);
            return "❌ 描述锚点失败: " + e.getMessage();
        }
    }

    // @anchor: anchorFormatter_appendOne
    private void appendDescribeOne(StringBuilder sb, String hint,
                                   Map<String, List<Map<String,
                                   Object>>> projectAnchors,
                                   Path projectDir) {
        List<String> fileMatches = new ArrayList<>();
        for (String key : projectAnchors.keySet()) {
            if (key.equals(hint) || key.endsWith("/" + hint)) fileMatches.add(key);
        }

        if (!fileMatches.isEmpty()) {
            if (fileMatches.size() > 1) {
                sb.append("❌ 文件 ").append(hint).append(" 有歧义，匹配到多个：\n");
                for (String m : fileMatches) sb.append("   - ").append(m).append("\n");
                sb.append("\n");
                return;
            }
            appendFileDetail(sb, fileMatches.get(0), projectAnchors.get(fileMatches.get(0)));
            return;
        }
        // 索引里没匹配 → 走目录概览；概览为空时在 appendDirIntro 内做文件系统诊断
        String dirPrefix = normalizeDirHint(hint);
        appendDirIntro(sb, dirPrefix, projectAnchors, projectDir, hint);
    }

    // @anchor: anchorFormatter_normalizeHint
    // 把 file 提示归一化为“相对项目根”的路径：
    // - 反斜杠统一为 "/"
    // - 去掉 "./" 前缀
    // - 剥离 projectPath 前缀（若存在）
    // - 传项目名本身等价于传 "."（返回空串）
    private String normalizeHint(String hint, String projectPath) {
        if (hint == null) return "";
        String h = hint.trim().replace('\\', '/');
        if (h.startsWith("./")) h = h.substring(2);

        if (projectPath != null && !projectPath.isBlank()) {
            String pp = projectPath.trim().replace('\\', '/');
            if (pp.startsWith("./")) pp = pp.substring(2);
            while (pp.endsWith("/")) pp = pp.substring(0, pp.length() - 1);

            if (h.equals(pp)) return "";
            if (h.startsWith(pp + "/")) h = h.substring(pp.length() + 1);
        }
        return h;
    }

    private String normalizeDirHint(String hint) {
        if (hint == null || hint.isEmpty() || ".".equals(hint)) return "";
        return hint.replace('\\', '/');
    }

    private boolean matchesDir(String fileKey, String dirPrefix) {
        if (dirPrefix.isEmpty()) return true;
        if (fileKey.equals(dirPrefix)) return true;
        return fileKey.startsWith(dirPrefix + "/") || fileKey.endsWith("/" + dirPrefix)
                || fileKey.contains("/" + dirPrefix + "/");
    }

    private void appendDirIntro(StringBuilder sb, String dirPrefix,
                                Map<String, List<Map<String, Object>>> projectAnchors,
                                Path projectDir, String rawHint) {
        int total = 0, withIntro = 0;
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, List<Map<String, Object>>> e : projectAnchors.entrySet()) {
            if (!matchesDir(e.getKey(), dirPrefix)) continue;
            total++;
            Map<String, Object> intro = findIntroAnchor(e.getValue());
            body.append("\n📄 ").append(e.getKey()).append("\n");
            if (intro != null) {
                withIntro++;
                String desc = (String) intro.getOrDefault("desc", "");
                if (desc == null || desc.isEmpty()) desc = "（无描述）";
                body.append("   ").append(desc).append("\n");
            } else {
                body.append("   （无 _intro 锚点）\n");
            }
        }

        String label = dirPrefix.isEmpty() ? "整个项目" : dirPrefix;
        if (total == 0) {
            // 索引里没有匹配 → 去文件系统确认具体原因
            if (dirPrefix.isEmpty()) {
                sb.append("📌 ").append(label).append(" 下没有锚点记录。\n\n");
                return;
            }
            try {
                Path candidate = projectDir.resolve(rawHint.replace('\\', '/')).normalize();
                if (candidate.startsWith(projectDir)) {
                    if (Files.isRegularFile(candidate)) {
                        sb.append("📄 ").append(rawHint).append("\n");
                        sb.append("   （文件存在，但未标注任何锚点）\n\n");
                        return;
                    }
                    if (Files.isDirectory(candidate)) {
                        sb.append("📌 ").append(rawHint)
                              .append(" 目录存在，但目录下没有锚点记录。\n\n");
                        return;
                    }
                }
            } catch (Exception ignored) {}
            sb.append("📌 ").append(rawHint).append(" 不存在，或没有锚点记录。\n\n");
            return;
        }
        sb.append("📌 ").append(label).append(" 文件职责概览（")
                .append(withIntro).append("/").append(total).append(" 已标注 _intro）\n")
                .append(body).append("\n");
    }

    private Map<String, Object> findIntroAnchor(List<Map<String, Object>> anchors) {
        if (anchors == null || anchors.isEmpty()) return null;
        Map<String, Object> first = anchors.get(0);
        String id = (String) first.get("id");
        if (id != null && id.endsWith("_intro")) return first;
        return null;
    }

    // @anchor: anchorFormatter_appendFileDetail
// 渲染单文件锚点列表；超出 MAX_DISPLAYED_ANCHORS 时保留首锚点与最新若干条
    private void appendFileDetail(StringBuilder sb, String fileKey,
                                  List<Map<String, Object>> anchors) {
        sb.append("📄 ").append(fileKey).append("\n");
        if (anchors == null || anchors.isEmpty()) {
            sb.append("   （无锚点）\n\n");
            return;
        }

        // 计算末位锚点行号（文件中行号最大的锚点）
        int maxLine = -1;
        for (Map<String, Object> a : anchors) {
            Object lineObj = a.get("line");
            if (lineObj instanceof Integer line && line > maxLine) maxLine = line;
        }

        // 截断：超出上限时保留第 1 条 + 最后 (N-1) 条，中间折叠
        List<Map<String, Object>> toShow;
        int omitted;
        if (anchors.size() <= MAX_DISPLAYED_ANCHORS) {
            toShow = anchors;
            omitted = 0;
        } else {
            int keepTail = MAX_DISPLAYED_ANCHORS - 1;
            toShow = new ArrayList<>(MAX_DISPLAYED_ANCHORS);
            toShow.add(anchors.get(0));
            toShow.addAll(anchors.subList(anchors.size() - keepTail, anchors.size()));
            omitted = anchors.size() - MAX_DISPLAYED_ANCHORS;
        }

        for (int i = 0; i < toShow.size(); i++) {
            Map<String, Object> a = toShow.get(i);
            String id = (String) a.get("id");
            int line = (int) a.get("line");
            String desc = (String) a.getOrDefault("desc", "");
            String symbol = (String) a.get("symbol");
            if (desc == null || desc.isEmpty()) desc = "（无描述）";
            sb.append("L").append(line).append(" | ").append(id);
            if (symbol != null && !symbol.isEmpty()) sb.append(" | ").append(symbol);
            if (line == maxLine) sb.append(" | [末尾锚点]");
            sb.append(" | ").append(desc).append("\n");

            // 首锚点之后插入省略提示
            if (omitted > 0 && i == 0) {
                sb.append("   ... [已省略 ").append(omitted)
                        .append(" 条中间锚点，仅显示首锚点与最新 ")
                        .append(MAX_DISPLAYED_ANCHORS - 1).append(" 条] ...\n");
            }
        }
        sb.append("\n");
    }
}