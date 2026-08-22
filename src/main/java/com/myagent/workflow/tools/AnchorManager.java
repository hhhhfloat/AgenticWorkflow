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

public class AnchorManager {
    private static final Logger logger = LoggerFactory.getLogger(AnchorManager.class);
    private final ObjectMapper objectMapper;
    private boolean migrationAttempted = false;

    public AnchorManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ===== 工具方法 =====

    /**
     * 获取项目锚点索引文件的路径
     * @param projectPath 项目相对路径（如 "task-cli"）
     * @return 项目目录下的 .anchors.json 路径，如果路径不安全则返回 null
     */
    private Path getIndexPath(String projectPath) {
        try {
            Path projectDir = PathUtils.safeResolve(projectPath);
            return projectDir.resolve(AgentConfig.getAnchorIndexName());
        } catch (IOException e) {
            logger.warn("⚠️ 项目路径不安全，无法获取索引文件: {}", projectPath, e);
            return null;
        }
    }

    /**
     * 迁移旧的全局锚点索引文件（sandbox/.anchor_index.json）
     * 拆分为每个项目的 .anchors.json
     */
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

                // 检查项目目录是否存在
                Path projectDir = PathUtils.safeResolve(projectPath);
                if (!Files.exists(projectDir) || !Files.isDirectory(projectDir)) {
                    logger.warn("⚠️ 项目目录不存在，跳过迁移: {}", projectPath);
                    continue;
                }

                // 写入项目级索引
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

            // 备份并删除旧索引
            Path backupPath = oldIndex.resolveSibling(".anchor_index.json.bak");
            Files.move(oldIndex, backupPath);
            logger.info("📌 旧索引已备份到: {}，共迁移 {} 个项目，{} 个锚点", backupPath, migratedProjects, totalAnchors);

        } catch (IOException e) {
            logger.error("迁移旧锚点索引失败", e);
        }
    }

    // ===== 工具方法 =====

    String buildAnchorIndex(String projectPath) {
        // 首先执行迁移（如果尚未执行）
        migrateOldIndexIfNeeded();

        try {
            Path projectDir = PathUtils.safeResolve(projectPath);
            if (!Files.exists(projectDir) || !Files.isDirectory(projectDir)) {
                return "❌ 项目目录不存在: " + projectPath;
            }

            // 支持的文件扩展名
            List<String> textExtensions = Arrays.asList(
                    ".java", ".html", ".htm", ".css", ".js", ".jsx", ".ts", ".tsx",
                    ".txt", ".xml", ".json", ".md", ".properties", ".yml", ".yaml",
                    ".sh", ".bat", ".gradle", ".sql",
                    ".cpp", ".cc", ".cxx", ".h", ".hpp", ".py", ".pyw"
            );

            // 锚点正则表达式
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
                        // 1. 排除锚点索引文件本身
                        if (name.equals(AgentConfig.getAnchorIndexName())) return false;
                        if (name.equals(".anchor_index.json")) return false;
                        if (name.equals(".agent_entry.json")) return false;
                        if (name.startsWith(".")) return false;

                        // 2. 排除构建/依赖目录
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
                            // 忽略异常
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
                                        // 🔥 重名检测：如果 ID 已存在，添加后缀
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

            // 写入项目级索引文件
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

    /**
     * 列出指定项目的所有锚点
     */
    String listAnchors(String projectPath) {
        return listAnchors(projectPath, null);
    }

    /**
     * 列出指定项目的锚点，可指定具体文件
     * @param projectPath 项目路径
     * @param filePath 可选，指定文件（如 "task.py"），为 null 时列出所有文件
     */
    String listAnchors(String projectPath, String filePath) {
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

            StringBuilder sb = new StringBuilder();
            sb.append("📌 ").append(projectPath).append(" 的锚点列表");
            if (filePath != null && !filePath.isBlank()) {
                sb.append("（文件: ").append(filePath).append("）");
            }
            sb.append("：\n");

            boolean foundAny = false;
            for (Map.Entry<String, List<Map<String, Object>>> entry : projectAnchors.entrySet()) {
                String file = entry.getKey();
                // 如果指定了文件，只匹配该文件
                if (filePath != null && !filePath.isBlank()) {
                    if (!file.equals(filePath) && !file.endsWith("/" + filePath) && !file.endsWith("\\" + filePath)) {
                        continue;
                    }
                }
                for (Map<String, Object> anchor : entry.getValue()) {
                    String id = (String) anchor.get("id");
                    int line = (int) anchor.get("line");
                    String preview = (String) anchor.get("preview");
                    sb.append("  - ").append(file).append(":").append(line)
                            .append(" [").append(id).append("] ").append(preview).append("\n");
                    foundAny = true;
                }
            }

            if (!foundAny) {
                if (filePath != null && !filePath.isBlank()) {
                    return "📌 项目 " + projectPath + " 中没有找到文件 " + filePath + " 的锚点记录。";
                }
                return "📌 项目 " + projectPath + " 没有锚点记录。";
            }

            return sb.toString();
        } catch (IOException e) {
            logger.error("列出锚点失败", e);
            return "❌ 列出锚点失败: " + e.getMessage();
        }
    }

    String insertAtAnchor(String anchorId, String content, String position) {
        // 注意：由于需要 projectPath，我们需要先查找锚点所在项目
        AnchorLocation loc = findAnchorGlobally(anchorId);
        if (loc == null) return "❌ 锚点不存在: " + anchorId;

        try {
            Path filePath = PathUtils.safeResolve(loc.projectPath, loc.filePath);
            if (!Files.exists(filePath)) return "❌ 文件不存在: " + loc.filePath;

            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            int targetLine = loc.line - 1;

            if ("before".equals(position)) {
                lines.add(targetLine, content);
            } else {
                lines.add(targetLine + 1, content);
            }

            Files.write(filePath, lines, StandardCharsets.UTF_8);
            buildAnchorIndex(loc.projectPath);
            return "✅ 已在 " + loc.filePath + " 的锚点 [" + anchorId + "] " + position + " 插入代码";

        } catch (IOException e) {
            logger.error("插入代码失败", e);
            return "❌ 插入失败: " + e.getMessage();
        }
    }

    String deleteBetweenAnchors(String startAnchor, String endAnchor) {
        AnchorLocation startLoc = findAnchorGlobally(startAnchor);
        AnchorLocation endLoc = findAnchorGlobally(endAnchor);
        if (startLoc == null) return "❌ 起始锚点不存在: " + startAnchor;
        if (endLoc == null) return "❌ 结束锚点不存在: " + endAnchor;

        if (!startLoc.projectPath.equals(endLoc.projectPath)) {
            return "❌ 两个锚点不在同一个项目中";
        }
        if (!startLoc.filePath.equals(endLoc.filePath)) {
            return "❌ 两个锚点不在同一个文件中";
        }
        if (startLoc.line >= endLoc.line) {
            return "❌ 起始锚点必须在结束锚点之前";
        }

        try {
            Path filePath = PathUtils.safeResolve(startLoc.projectPath, startLoc.filePath);
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);

            int startLine = startLoc.line - 1;
            int endLine = endLoc.line - 1;

            if (endLine - startLine <= 1) {
                return "⚠️ 两个锚点之间没有内容可删除";
            }

            List<String> newLines = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (i > startLine && i < endLine) {
                    continue;
                }
                newLines.add(lines.get(i));
            }

            Files.write(filePath, newLines, StandardCharsets.UTF_8);
            buildAnchorIndex(startLoc.projectPath);

            int deletedLines = (endLine - startLine) - 1;
            return "✅ 已删除从 [" + startAnchor + "] 到 [" + endAnchor + "] 之间的 " + deletedLines + " 行代码";

        } catch (IOException e) {
            logger.error("删除代码块失败", e);
            return "❌ 删除失败: " + e.getMessage();
        }
    }

    String readBetweenAnchors(String startAnchor, String endAnchor) {
        AnchorLocation startLoc = findAnchorGlobally(startAnchor);
        AnchorLocation endLoc = findAnchorGlobally(endAnchor);
        if (startLoc == null) return "❌ 起始锚点不存在: " + startAnchor;
        if (endLoc == null) return "❌ 结束锚点不存在: " + endAnchor;

        if (!startLoc.projectPath.equals(endLoc.projectPath)) {
            return "❌ 两个锚点不在同一个项目中\n" +
                    "  起始锚点: " + startLoc.projectPath + "\n" +
                    "  结束锚点: " + endLoc.projectPath;
        }
        if (!startLoc.filePath.equals(endLoc.filePath)) {
            return "❌ 两个锚点不在同一个文件中\n" +
                    "  起始锚点: " + startLoc.filePath + "\n" +
                    "  结束锚点: " + endLoc.filePath;
        }
        if (startLoc.line >= endLoc.line) {
            return "❌ 起始锚点必须在结束锚点之前\n" +
                    "  起始锚点: " + startLoc.filePath + " 行 " + startLoc.line + "\n" +
                    "  结束锚点: " + endLoc.filePath + " 行 " + endLoc.line;
        }

        try {
            Path filePath = PathUtils.safeResolve(startLoc.projectPath, startLoc.filePath);
            if (!Files.exists(filePath)) {
                return "❌ 文件不存在: " + startLoc.filePath;
            }

            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            int startLine = startLoc.line - 1;
            int endLine = endLoc.line - 1;

            List<String> resultLines = new ArrayList<>();
            for (int i = startLine; i <= endLine && i < lines.size(); i++) {
                resultLines.add(lines.get(i));
            }

            if (resultLines.isEmpty()) {
                return "⚠️ 两个锚点之间没有内容可读取";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("📖 读取 ").append(startLoc.filePath)
                    .append(" 从行 ").append(startLoc.line)
                    .append(" 到行 ").append(endLoc.line)
                    .append("（共 ").append(resultLines.size()).append(" 行）\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

            for (int i = 0; i < resultLines.size(); i++) {
                int lineNum = startLoc.line + i;
                sb.append(String.format("%4d | %s", lineNum, resultLines.get(i))).append("\n");
            }

            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("📌 起始锚点: ").append(startAnchor).append(" (行 ").append(startLoc.line).append(")\n");
            sb.append("📌 结束锚点: ").append(endAnchor).append(" (行 ").append(endLoc.line).append(")");

            return sb.toString();

        } catch (IOException e) {
            logger.error("读取锚点区间失败", e);
            return "❌ 读取失败: " + e.getMessage();
        }
    }

    // ===== 辅助：查找锚点 =====

    /**
     * 在指定项目中查找锚点
     */
    private AnchorLocation findAnchor(String projectPath, String anchorId) {
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

    /**
     * 全局查找锚点（遍历所有项目，用于 insertAtAnchor 等需要自动定位的方法）
     * 注意：此方法仅在旧有工具方法中使用，新代码应使用 findAnchor(projectPath, anchorId)
     */
    private AnchorLocation findAnchorGlobally(String anchorId) {
        // 首先尝试迁移
        migrateOldIndexIfNeeded();

        try {
            Path sandboxRoot = Paths.get(AgentConfig.getSandboxDir()).toAbsolutePath().normalize();
            Path anchorRoot = sandboxRoot.resolve(".anchors");

            // 遍历 sandbox 下的所有子目录，查找 .anchors.json
            if (!Files.exists(anchorRoot)) {
                // 兼容旧方式：直接在项目目录下查找
                try (var stream = Files.list(sandboxRoot)) {
                    for (Path projectDir : (Iterable<Path>) stream::iterator) {
                        if (!Files.isDirectory(projectDir)) continue;
                        String projectName = projectDir.getFileName().toString();
                        if (projectName.startsWith(".")) continue;

                        AnchorLocation loc = findAnchor(projectName, anchorId);
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

    // ===== 兼容旧有的 findAnchor 方法（用于未修改的调用方）=====
    // 注意：此方法已废弃，仅用于兼容，新代码应使用 findAnchor(projectPath, anchorId)
    @Deprecated
    AnchorLocation findAnchor(String anchorId) {
        return findAnchorGlobally(anchorId);
    }
}