package com.myagent.workflow.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.AgentConfig;
import com.myagent.workflow.model.AnchorLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 锚点索引 —— 负责索引文件的构建、查询、格式化。
 * <p>
 * 职责：
 * - 扫描项目源码，提取 @anchor 注释，写入 .anchors.json
 * - 从索引中查询锚点位置
 * - 全局查找锚点（遍历所有项目）
 * - 格式化锚点列表供 Agent 消费
 * <p>
 * 由 AnchorManager 持有。不涉及文件内容修改。
 */
// @anchor: anchorIndex_class
class AnchorIndex {
    private static final Logger logger = LoggerFactory.getLogger(AnchorIndex.class);
    private final ObjectMapper objectMapper;
    private boolean migrationAttempted = false;

    // @anchor: anchorIndex_constructor
    AnchorIndex(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ===== 索引路径 =====

    // @anchor: anchorIndex_getIndexPath
    private Path getIndexPath(String projectPath) {
        try {
            Path projectDir = PathUtils.safeResolve(projectPath);
            return projectDir.resolve(AgentConfig.getAnchorIndexName());
        } catch (IOException e) {
            logger.warn("⚠️ 项目路径不安全，无法获取索引文件: {}", projectPath, e);
            return null;
        }
    }

    // ===== 旧索引迁移 =====

    // @anchor: anchorIndex_migrateOldIndex
    private synchronized void migrateOldIndexIfNeeded() {
        if (migrationAttempted) return;
        migrationAttempted = true;

        Path oldIndex = Paths.get(AgentConfig.getSandboxDir(), ".anchor_index.json");
        if (!Files.exists(oldIndex)) {
            return;
        }

        logger.info("📦 检测到旧锚点索引文件，正在迁移...");

        try {
            String content = Files.readString(oldIndex);
            Map<String, Map<String, List<Map<String, Object>>>> oldIndexData =
                    objectMapper.readValue(content, new TypeReference<>() {});

            int migratedProjects = 0;
            int totalAnchors = 0;

            for (Map.Entry<String, Map<String, List<Map<String, Object>>>> projectEntry : oldIndexData.entrySet()) {
                String projectPath = projectEntry.getKey();
                Map<String, List<Map<String, Object>>> projectAnchors = projectEntry.getValue();

                Path projectDir = PathUtils.safeResolve(projectPath);
                if (!Files.exists(projectDir) || !Files.isDirectory(projectDir)) {
                    logger.warn("⚠️ 项目目录不存在，跳过迁移: {}", projectPath);
                    continue;
                }

                Path newIndex = getIndexPath(projectPath);
                if (newIndex == null) {
                    return;
                }
                String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(projectAnchors);
                Files.writeString(newIndex, json);

                int anchorCount = projectAnchors.values().stream().mapToInt(List::size).sum();
                migratedProjects++;
                totalAnchors += anchorCount;
                logger.info("✅ 迁移 {}: {} 个文件, {} 个锚点", projectPath, projectAnchors.size(), anchorCount);
            }

            Path backupPath = oldIndex.resolveSibling(".anchor_index.json.bak");
            Files.move(oldIndex, backupPath);
            logger.info("📌 旧索引已备份到: {}，共迁移 {} 个项目，{} 个锚点", backupPath, migratedProjects, totalAnchors);

        } catch (IOException e) {
            logger.error("迁移旧锚点索引失败", e);
        }
    }

    // ===== 构建索引 =====

    // @anchor: anchorIndex_rebuild
    /**
     * 重建锚点索引：扫描项目下的所有源文件，提取 @anchor 注释，写入 .anchors.json。
     * 原 AnchorManager.buildAnchorIndex。
     */
    String rebuild(String projectPath) {
        migrateOldIndexIfNeeded();

        try {
            Path projectDir = PathUtils.safeResolve(projectPath);
            if (!Files.exists(projectDir) || !Files.isDirectory(projectDir)) {
                return "❌ 项目目录不存在: " + projectPath;
            }

            List<String> textExtensions = Arrays.asList(
                    ".java", ".html", ".htm", ".css", ".js", ".jsx", ".ts", ".tsx",
                    ".txt", ".xml", ".json", ".md", ".properties", ".yml", ".yaml",
                    ".sh", ".bat", ".gradle", ".sql",
                    ".cpp", ".cc", ".cxx", ".h", ".hpp", ".py", ".pyw"
            );

            Pattern anchorPattern = Pattern.compile(
                    "//\\s*@anchor:\\s*(\\w+)|" +
                            "/\\*\\s*@anchor:\\s*(\\w+)\\s*\\*/|" +
                            "<!--\\s*@anchor:\\s*(\\w+)\\s*-->|" +
                            "#\\s*@anchor:\\s*(\\w+)"
            );

            Map<String, List<Map<String, Object>>> projectAnchors = new LinkedHashMap<>();
            Set<String> seenIds = new HashSet<>();

            Files.walk(projectDir)
                    .filter(Files::isRegularFile)
                    .filter(file -> {
                        String name = file.getFileName().toString();
                        if (name.equals(AgentConfig.getAnchorIndexName())) return false;
                        if (name.equals(".anchor_index.json")) return false;
                        if (name.equals(".agent_entry.json")) return false;
                        if (name.startsWith(".")) return false;

                        try {
                            Path relPath = projectDir.relativize(file);
                            if (relPath.getNameCount() > 0) {
                                String firstSegment = relPath.getName(0).toString();
                                if (firstSegment.equals("target")) return false;
                                if (firstSegment.equals("node_modules")) return false;
                                if (firstSegment.equals(".git")) return false;
                                if (firstSegment.equals("classes")) return false;
                                if (firstSegment.equals("build")) return false;
                                if (firstSegment.equals("dist")) return false;
                                if (firstSegment.equals("out")) return false;
                            }
                        } catch (Exception e) {
                            // 忽略
                        }
                        return true;
                    })
                    .forEach(file -> {
                        String fileName = file.getFileName().toString();
                        String ext = "";
                        int dotIdx = fileName.lastIndexOf('.');
                        if (dotIdx > 0) ext = fileName.substring(dotIdx).toLowerCase();
                        if (!textExtensions.contains(ext)) return;

                        try {
                            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                            String relPath = projectDir.relativize(file).toString().replace('\\', '/');
                            List<Map<String, Object>> anchors = new ArrayList<>();

                            for (int i = 0; i < lines.size(); i++) {
                                java.util.regex.Matcher matcher = anchorPattern.matcher(lines.get(i));
                                if (matcher.find()) {
                                    String id = null;
                                    for (int j = 1; j <= matcher.groupCount(); j++) {
                                        String candidate = matcher.group(j);
                                        if (candidate != null) {
                                            id = candidate;
                                            break;
                                        }
                                    }
                                    if (id != null) {
                                        String finalId = id;
                                        int suffix = 2;
                                        while (seenIds.contains(finalId)) {
                                            finalId = id + "_" + suffix;
                                            suffix++;
                                        }
                                        seenIds.add(finalId);

                                        if (!finalId.equals(id)) {
                                            logger.debug("🔧 锚点重名: '{}' → '{}'", id, finalId);
                                        }

                                        Map<String, Object> anchor = new LinkedHashMap<>();
                                        anchor.put("id", finalId);
                                        anchor.put("line", i + 1);
                                        anchor.put("preview", lines.get(i).trim());
                                        anchors.add(anchor);
                                    }
                                }
                            }

                            if (!anchors.isEmpty()) {
                                projectAnchors.put(relPath, anchors);
                            }
                        } catch (IOException ignored) {}
                    });

            Path indexFile = getIndexPath(projectPath);
            if (indexFile == null) {
                return "❌ 项目路径无效: " + projectPath;
            }
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(projectAnchors);
            Files.writeString(indexFile, json);

            int totalFiles = projectAnchors.size();
            int totalAnchors = projectAnchors.values().stream().mapToInt(List::size).sum();
            int duplicatesFixed = totalAnchors - seenIds.size();

            StringBuilder result = new StringBuilder();
            result.append("✅ 锚点索引已重建：").append(projectPath)
                    .append(" 中找到 ").append(totalAnchors).append(" 个锚点")
                    .append("，分布在 ").append(totalFiles).append(" 个文件中。");
            if (duplicatesFixed > 0) {
                result.append(" (自动修正 ").append(duplicatesFixed).append(" 个重名)");
            }
            return result.toString();

        } catch (IOException e) {
            logger.error("重建锚点索引失败", e);
            return "❌ 重建锚点索引失败: " + e.getMessage();
        }
    }

    // ===== 列出锚点 =====

    // @anchor: anchorIndex_list
    String list(String projectPath, String filePath) {
        try {
            Path projectDir = PathUtils.safeResolve(projectPath);
            if (!Files.exists(projectDir) || !Files.isDirectory(projectDir)) {
                return "❌ 项目目录不存在: " + projectPath;
            }

            Path indexFile = getIndexPath(projectPath);
            if (indexFile == null) {
                return "❌ 项目路径无效: " + projectPath;
            }
            if (!Files.exists(indexFile)) {
                return "📌 项目 " + projectPath + " 没有锚点记录。请先运行 build_anchor_index。";
            }

            String content = new String(Files.readAllBytes(indexFile), StandardCharsets.UTF_8);
            Map<String, List<Map<String, Object>>> projectAnchors =
                    objectMapper.readValue(content, new TypeReference<>() {});

            if (projectAnchors.isEmpty()) {
                return "📌 项目 " + projectPath + " 没有锚点记录。";
            }

            boolean isFileSpecified = filePath != null && !filePath.isBlank();

            Map<String, List<Map<String, Object>>> matched = new LinkedHashMap<>();
            for (Map.Entry<String, List<Map<String, Object>>> entry : projectAnchors.entrySet()) {
                String file = entry.getKey();
                if (isFileSpecified) {
                    if (!file.equals(filePath)
                            && !file.endsWith("/" + filePath)
                            && !file.endsWith("\\" + filePath)) {
                        continue;
                    }
                }
                if (!entry.getValue().isEmpty()) {
                    matched.put(file, entry.getValue());
                }
            }

            if (matched.isEmpty()) {
                if (isFileSpecified) {
                    return "📌 项目 " + projectPath + " 中没有找到文件 " + filePath + " 的锚点记录。";
                }
                return "📌 项目 " + projectPath + " 没有锚点记录。";
            }

            StringBuilder sb = new StringBuilder();

            if (isFileSpecified) {
                sb.append("📌 ").append(projectPath).append(" 的锚点（文件: ").append(filePath).append("）：\n\n");
                for (List<Map<String, Object>> anchors : matched.values()) {
                    appendAnchorLines(sb, anchors);
                }
            } else {
                sb.append("📌 项目 ").append(projectPath).append(" 的锚点列表：\n");
                for (Map.Entry<String, List<Map<String, Object>>> entry : matched.entrySet()) {
                    sb.append("\n📄 ").append(entry.getKey()).append("\n");
                    appendAnchorLines(sb, entry.getValue());
                }
            }

            return sb.toString();

        } catch (IOException e) {
            logger.error("列出锚点失败", e);
            return "❌ 列出锚点失败: " + e.getMessage();
        }
    }

    // @anchor: anchorIndex_appendAnchorLines
    private void appendAnchorLines(StringBuilder sb, List<Map<String, Object>> anchors) {
        for (Map<String, Object> anchor : anchors) {
            String id = (String) anchor.get("id");
            int line = (int) anchor.get("line");
            sb.append("   L").append(line).append("   ").append(id).append("\n");
        }
    }

    // ===== 查找锚点 =====

    // @anchor: anchorIndex_find
    AnchorLocation find(String projectPath, String anchorId) {
        try {
            Path indexFile = getIndexPath(projectPath);
            if (indexFile == null) {
                return null;
            }
            if (!Files.exists(indexFile)) return null;

            String content = Files.readString(indexFile);
            Map<String, List<Map<String, Object>>> projectAnchors =
                    objectMapper.readValue(content, new TypeReference<>() {});

            for (Map.Entry<String, List<Map<String, Object>>> fileEntry : projectAnchors.entrySet()) {
                String filePath = fileEntry.getKey();
                for (Map<String, Object> anchor : fileEntry.getValue()) {
                    String id = (String) anchor.get("id");
                    if (anchorId.equals(id)) {
                        AnchorLocation loc = new AnchorLocation();
                        loc.projectPath = projectPath;
                        loc.filePath = filePath;
                        loc.line = (int) anchor.get("line");
                        loc.id = id;
                        loc.preview = (String) anchor.get("preview");
                        return loc;
                    }
                }
            }
            return null;
        } catch (IOException e) {
            logger.error("查找锚点失败", e);
            return null;
        }
    }

    // @anchor: anchorIndex_findGlobally
    AnchorLocation findGlobally(String anchorId) {
        migrateOldIndexIfNeeded();

        try {
            Path sandboxRoot = Paths.get(AgentConfig.getSandboxDir()).toAbsolutePath().normalize();
            Path anchorRoot = sandboxRoot.resolve(".anchors");

            if (!Files.exists(anchorRoot)) {
                try (var stream = Files.list(sandboxRoot)) {
                    for (Path projectDir : (Iterable<Path>) stream::iterator) {
                        if (!Files.isDirectory(projectDir)) continue;
                        String projectName = projectDir.getFileName().toString();
                        if (projectName.startsWith(".")) continue;

                        AnchorLocation loc = find(projectName, anchorId);
                        if (loc != null) return loc;
                    }
                }
            }
            return null;
        } catch (IOException e) {
            logger.error("全局查找锚点失败", e);
            return null;
        }
    }
}