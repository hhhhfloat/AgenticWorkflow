package com.myagent.workflow.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                appendDescribeOne(sb, hint, projectAnchors);
            }
            return sb.toString();
        } catch (IOException e) {
            logger.error("描述锚点失败", e);
            return "❌ 描述锚点失败: " + e.getMessage();
        }
    }

    // @anchor: anchorFormatter_appendOne
    private void appendDescribeOne(StringBuilder sb, String hint,
                                   Map<String, List<Map<String, Object>>> projectAnchors) {
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

        String dirPrefix = normalizeDirHint(hint);
        appendDirIntro(sb, dirPrefix, projectAnchors);
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
                                Map<String, List<Map<String, Object>>> projectAnchors) {
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
            sb.append("📌 ").append(label).append(" 下没有锚点记录。\n\n");
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

    private void appendFileDetail(StringBuilder sb, String fileKey,
                                  List<Map<String, Object>> anchors) {
        sb.append("📄 ").append(fileKey).append("\n");
        if (anchors == null || anchors.isEmpty()) {
            sb.append("   （无锚点）\n\n");
            return;
        }
        for (Map<String, Object> a : anchors) {
            String id = (String) a.get("id");
            int line = (int) a.get("line");
            String desc = (String) a.getOrDefault("desc", "");
            String symbol = (String) a.get("symbol");
            if (desc == null || desc.isEmpty()) desc = "（无描述）";
            sb.append("L").append(line).append(" | ").append(id);
            if (symbol != null && !symbol.isEmpty()) sb.append(" | ").append(symbol);
            sb.append(" | ").append(desc).append("\n");
        }
        sb.append("\n");
    }
}