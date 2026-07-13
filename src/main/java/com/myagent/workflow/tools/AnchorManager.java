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
    private final String anchorIndexFile;
    private final ObjectMapper objectMapper;

    public AnchorManager(ObjectMapper objectMapper) {
        this.anchorIndexFile = AgentConfig.getAnchorIndexFile();
        this.objectMapper = objectMapper;
    }

    // ===== 工具方法 =====
    String buildAnchorIndex(String projectPath) {
        try {
            Path projectDir = PathUtils.safeResolve(projectPath);
            if (!Files.exists(projectDir) || !Files.isDirectory(projectDir)) {
                return "项目目录不存在: " + projectPath;
            }

            Map<String, Map<String, List<Map<String, Object>>>> index = new HashMap<>();
            Path indexFile = Paths.get(anchorIndexFile);
            if (Files.exists(indexFile)) {
                String content = new String(Files.readAllBytes(indexFile), StandardCharsets.UTF_8);
                index = objectMapper.readValue(content, new TypeReference<>() {});
            }
            index.remove(projectPath);

            List<String> textExtensions = Arrays.asList(".java", ".html", ".htm", ".css", ".js", ".jsx", ".ts", ".tsx",
                    ".txt", ".xml", ".json", ".md", ".properties", ".yml", ".yaml", ".sh", ".bat", ".gradle", ".sql");
            Map<String, List<Map<String, Object>>> projectAnchors = new HashMap<>();

            Pattern anchorPattern = Pattern.compile(
                    "//\\s*@anchor:\\s*(\\w+)|/\\*\\s*@anchor:\\s*(\\w+)\\s*\\*/|<!--\\s*@anchor:\\s*(\\w+)\\s*-->");

            Files.walk(projectDir)
                    .filter(Files::isRegularFile)
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
                                    String id = matcher.group(1);
                                    if (id == null) id = matcher.group(2);
                                    if (id == null) id = matcher.group(3);
                                    if (id != null) {
                                        Map<String, Object> anchor = new HashMap<>();
                                        anchor.put("id", id);
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

            if (projectAnchors.isEmpty()) {
                index.remove(projectPath);
            } else {
                index.put(projectPath, projectAnchors);
            }

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(index);
            Files.write(indexFile, json.getBytes(StandardCharsets.UTF_8));

            int totalFiles = projectAnchors.size();
            int totalAnchors = projectAnchors.values().stream().mapToInt(List::size).sum();
            return "✅ 锚点索引已重建：" + projectPath + " 中找到 " + totalAnchors + " 个锚点，分布在 " + totalFiles + " 个文件中。";

        } catch (IOException e) {
            logger.error("重建锚点索引失败", e);
            return "重建锚点索引失败: " + e.getMessage();
        }
    }

    String listAnchors(String projectPath) {
        try {
            Path indexFile = Paths.get(anchorIndexFile);
            if (!Files.exists(indexFile)) {
                return "锚点索引文件不存在，请先运行 build_anchor_index。";
            }

            String content = new String(Files.readAllBytes(indexFile), StandardCharsets.UTF_8);
            Map<String, Map<String, List<Map<String, Object>>>> index =
                    objectMapper.readValue(content, new TypeReference<>() {});

            if (!index.containsKey(projectPath)) {
                return "项目 " + projectPath + " 没有锚点记录。";
            }

            Map<String, List<Map<String, Object>>> projectAnchors = index.get(projectPath);
            StringBuilder sb = new StringBuilder();
            sb.append("📌 ").append(projectPath).append(" 的锚点列表：\n");
            for (Map.Entry<String, List<Map<String, Object>>> entry : projectAnchors.entrySet()) {
                String file = entry.getKey();
                for (Map<String, Object> anchor : entry.getValue()) {
                    String id = (String) anchor.get("id");
                    int line = (int) anchor.get("line");
                    String preview = (String) anchor.get("preview");
                    sb.append("  - ").append(file).append(":").append(line)
                            .append(" [").append(id).append("] ").append(preview).append("\n");
                }
            }
            return sb.toString();
        } catch (IOException e) {
            logger.error("列出锚点失败", e);
            return "列出锚点失败: " + e.getMessage();
        }
    }

    String insertAtAnchor(String anchorId, String content, String position) {
        AnchorLocation loc = findAnchor(anchorId);
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
            return "✅ 已在 " + loc.filePath + " 的锚点 [" + anchorId + "] 之后插入代码";

        } catch (IOException e) {
            logger.error("插入代码失败", e);
            return "❌ 插入失败: " + e.getMessage();
        }
    }

    String deleteBetweenAnchors(String startAnchor, String endAnchor) {
        // 1. 查找两个锚点
        AnchorLocation startLoc = findAnchor(startAnchor);
        AnchorLocation endLoc = findAnchor(endAnchor);
        if (startLoc == null) return "❌ 起始锚点不存在: " + startAnchor;
        if (endLoc == null) return "❌ 结束锚点不存在: " + endAnchor;

        // 2. 必须在同一文件中
        if (!startLoc.filePath.equals(endLoc.filePath)) {
            return "❌ 两个锚点不在同一个文件中";
        }

        // 3. 起始行必须在结束行之前
        if (startLoc.line >= endLoc.line) {
            return "❌ 起始锚点必须在结束锚点之前";
        }

        // 4. 读取文件，删除中间内容
        try {
            Path filePath = PathUtils.safeResolve(startLoc.projectPath, startLoc.filePath);
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);

            int startLine = startLoc.line - 1;
            int endLine = endLoc.line - 1;

            // 如果中间没有内容，返回提示
            if (endLine - startLine <= 1) {
                return "⚠️ 两个锚点之间没有内容可删除";
            }

            List<String> newLines = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (i > startLine && i < endLine) {
                    continue; // 跳过中间行
                }
                newLines.add(lines.get(i));
            }

            Files.write(filePath, newLines, StandardCharsets.UTF_8);

            // 5. 更新锚点索引
            buildAnchorIndex(startLoc.projectPath);

            int deletedLines = (endLine - startLine) - 1;
            return "✅ 已删除从 [" + startAnchor + "] 到 [" + endAnchor + "] 之间的 " + deletedLines + " 行代码";

        } catch (IOException e) {
            logger.error("删除代码块失败", e);
            return "❌ 删除失败: " + e.getMessage();
        }
    }

    // ===== 辅助 =====
    AnchorLocation findAnchor(String anchorId) {
        try {
            Path indexFile = Paths.get(anchorIndexFile);
            if (!Files.exists(indexFile)) return null;

            String content = new String(Files.readAllBytes(indexFile), StandardCharsets.UTF_8);
            Map<String, Map<String, List<Map<String, Object>>>> index =
                    objectMapper.readValue(content, new TypeReference<>() {});

            for (Map.Entry<String, Map<String, List<Map<String, Object>>>> project : index.entrySet()) {
                String projectPath = project.getKey();
                for (Map.Entry<String, List<Map<String, Object>>> file : project.getValue().entrySet()) {
                    String filePath = file.getKey();
                    for (Map<String, Object> anchor : file.getValue()) {
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
            }
            return null;
        } catch (IOException e) {
            logger.error("查找锚点失败", e);
            return null;
        }
    }
}