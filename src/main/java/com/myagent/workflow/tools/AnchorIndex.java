package com.myagent.workflow.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.AgentConfig;
import com.myagent.workflow.model.AnchorLocation;
import com.myagent.workflow.model.ClassDefinition;
import com.myagent.workflow.model.FileStructure;
import com.myagent.workflow.model.MethodDefinition;
import com.myagent.workflow.parser.StructureParser;
import com.myagent.workflow.parser.StructureParserRegistry;
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
    private static final String PROJECT_INDEX_NAME = ".project_index.json";
    private static final Pattern END_ANCHOR_PATTERN = Pattern.compile("_end(\\d+)?$");

    private static final List<String> TEXT_EXTENSIONS = List.of(
            ".java", ".html", ".htm", ".css", ".js", ".jsx", ".ts", ".tsx",
            ".txt", ".xml", ".json", ".md", ".properties", ".yml", ".yaml",
            ".sh", ".bat", ".gradle", ".sql",
            ".cpp", ".cc", ".cxx", ".h", ".hpp", ".py", ".pyw");

    private static final Pattern ANCHOR_PATTERN = Pattern.compile(
            "//\\s*@anchor:\\s*(\\w+)|" +
                    "/\\*\\s*@anchor:\\s*(\\w+)\\s*\\*/|" +
                    "<!--\\s*@anchor:\\s*(\\w+)\\s*-->|" +
                    "#\\s*@anchor:\\s*(\\w+)");

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "target", "node_modules", ".git", "classes", "build", "dist", "out");

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

            Map<String, List<Map<String, Object>>> projectAnchors = new LinkedHashMap<>();
            Set<String> seenIds = new HashSet<>();

            Files.walk(projectDir)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        String name = file.getFileName().toString();
                        if (name.equals(AgentConfig.getAnchorIndexName())) return;
                        if (name.equals(".anchor_index.json")) return;
                        if (name.equals(".agent_entry.json")) return;
                        if (name.startsWith(".")) return;

                        try {
                            Path relPath = projectDir.relativize(file);
                            if (relPath.getNameCount() > 0 && EXCLUDED_DIRS.contains(relPath.getName(0).toString())) return;

                            String rel = projectDir.relativize(file).toString().replace('\\', '/');
                            List<Map<String, Object>> anchors = scanAnchorsFromFile(file, seenIds);
                            if (!anchors.isEmpty()) projectAnchors.put(rel, anchors);
                        } catch (IOException ignored) {}
                    });

            // 写 .anchors.json（精简版：id + line + preview）
            Path indexFile = getIndexPath(projectPath);
            if (indexFile == null) {
                return "❌ 项目路径无效: " + projectPath;
            }
            Map<String, List<Map<String, Object>>> leanAnchors = new LinkedHashMap<>();
            for (Map.Entry<String, List<Map<String, Object>>> e : projectAnchors.entrySet()) {
                List<Map<String, Object>> lean = new ArrayList<>();
                for (Map<String, Object> a : e.getValue()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", a.get("id"));
                    m.put("line", a.get("line"));
                    m.put("preview", a.get("preview"));
                    lean.add(m);
                }
                leanAnchors.put(e.getKey(), lean);
            }
            Files.writeString(indexFile,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(leanAnchors));

            // 写 .project_index.json（精简版：id + line + desc）
            writeProjectIndex(projectPath, projectAnchors);

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

    private List<Map<String, Object>> scanAnchorsFromFile(Path file, Set<String> seenIds) throws IOException {
        String fileName = file.getFileName().toString();
        String ext = "";
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx > 0) ext = fileName.substring(dotIdx).toLowerCase();
        if (!TEXT_EXTENSIONS.contains(ext)) return List.of();

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<Map<String, Object>> anchors = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            java.util.regex.Matcher matcher = ANCHOR_PATTERN.matcher(lines.get(i));
            if (!matcher.find()) continue;
            String id = null;
            for (int j = 1; j <= matcher.groupCount(); j++) {
                String c = matcher.group(j);
                if (c != null) { id = c; break; }
            }
            if (id == null) continue;
            String finalId = id;
            int suffix = 2;
            while (seenIds.contains(finalId)) finalId = id + "_" + suffix++;
            seenIds.add(finalId);

            Map<String, Object> anchor = new LinkedHashMap<>();
            anchor.put("id", finalId);
            anchor.put("line", i + 1);
            anchor.put("preview", lines.get(i).trim());
            anchor.put("desc", extractDesc(lines, i));
            anchors.add(anchor);
        }
        return anchors;
    }

    private List<Map<String, Object>> buildIndexEntries(Path file, List<Map<String, Object>> anchors) {
        StructureParserRegistry registry = StructureParserRegistry.getInstance();
        List<MethodDefinition> methods = parseMethods(file, registry);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> a : anchors) {
            String id = (String) a.get("id");
            if (id != null && END_ANCHOR_PATTERN.matcher(id).find()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("line", a.get("line"));
            m.put("desc", a.getOrDefault("desc", ""));
            Object lineObj = a.get("line");
            if (lineObj instanceof Integer line) {
                String symbol = findSymbolForAnchor(line, methods);
                if (symbol != null && !symbol.isEmpty()) m.put("symbol", symbol);
            }
            list.add(m);
        }
        return list;
    }

    /**
     * 从锚点行的下一行开始，提取紧邻的注释作为描述。
     * 支持 //、#、/* * /、<!-- -->。
     * 取不到则返回空字符串。
     */
    private String extractDesc(List<String> lines, int anchorLineIdx) {
        int next = anchorLineIdx + 1;
        if (next >= lines.size()) return "";

        String line = lines.get(next).trim();

        // 不跳过空行——锚点后必须紧贴注释，否则视为无描述
        if (line.isEmpty()) return "";

        // 遇到下一个锚点行，说明本锚点无描述
        if (line.contains("@anchor:")) return "";

        // 单行注释
        if (line.startsWith("//")) {
            String content = line.substring(2).trim();
            return content.contains("@anchor:") ? "" : content;
        }
        if (line.startsWith("#") && !line.startsWith("#!")) {
            String content = line.substring(1).trim();
            return content.contains("@anchor:") ? "" : content;
        }

        // 块注释 /** ... */ 或 /* ... */
        if (line.startsWith("/**") || line.startsWith("/*")) {
            String rest = line.startsWith("/**") ? line.substring(3) : line.substring(2);
            rest = rest.trim();
            if (rest.endsWith("*/")) {
                rest = rest.substring(0, rest.length() - 2).trim();
            }
            if (!rest.isEmpty()) return rest;

            // 块内后续行——允许块内跳空行，因为块注释本身是完整的语义单元
            for (int i = next + 1; i < lines.size(); i++) {
                String l = lines.get(i).trim();
                if (l.equals("*/") || l.equals("* /")) return "";
                if (l.contains("@anchor:")) return "";
                if (l.startsWith("*")) l = l.substring(1).trim();
                if (l.endsWith("*/")) l = l.substring(0, l.length() - 2).trim();
                if (!l.isEmpty()) return l;
            }
            return "";
        }

        // HTML 注释
        if (line.startsWith("<!--")) {
            String rest = line.substring(4).trim();
            if (rest.endsWith("-->")) {
                rest = rest.substring(0, rest.length() - 3).trim();
            }
            return rest;
        }

        return "";
    }


    /**
     * 将锚点结果（含 desc）写入 .project_index.json。
     */
    private void writeProjectIndex(String projectPath, Map<String, List<Map<String, Object>>> projectAnchors) throws IOException {
        Path projectDir = PathUtils.safeResolve(projectPath);
        Map<String, List<Map<String, Object>>> index = new LinkedHashMap<>();

        for (Map.Entry<String, List<Map<String, Object>>> e : projectAnchors.entrySet()) {
            String relPath = e.getKey();
            Path filePath = projectDir.resolve(relPath);
            List<Map<String, Object>> entries = buildIndexEntries(filePath, e.getValue());
            if (!entries.isEmpty()) index.put(relPath, entries);
        }

        Path indexFile = projectDir.resolve(PROJECT_INDEX_NAME);
        Files.writeString(indexFile,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(index));
    }

    // @anchor: anchorIndex_rebuildFile
    /**
     * 增量重建单个文件的锚点索引，只重扫一个文件。
     * 索引文件不存在时退化为全量 rebuild。
     */
    String rebuildFile(String projectPath, String fileRelPath) {
        try {
            Path projectDir = PathUtils.safeResolve(projectPath);
            Path anchorsFile = getIndexPath(projectPath);
            if (anchorsFile == null || !Files.exists(anchorsFile)) {
                return rebuild(projectPath);
            }

            String content = Files.readString(anchorsFile, StandardCharsets.UTF_8);
            Map<String, List<Map<String, Object>>> projectAnchors =
                    objectMapper.readValue(content, new TypeReference<>() {});

            // 收集其他文件的锚点 ID
            Set<String> seenIds = new HashSet<>();
            for (Map.Entry<String, List<Map<String, Object>>> e : projectAnchors.entrySet()) {
                if (e.getKey().equals(fileRelPath)) continue;
                for (Map<String, Object> a : e.getValue()) seenIds.add((String) a.get("id"));
            }

            Path file = projectDir.resolve(fileRelPath);
            List<Map<String, Object>> anchors;
            if (!Files.exists(file) || !Files.isRegularFile(file)) {
                anchors = List.of();
            } else {
                anchors = scanAnchorsFromFile(file, seenIds);
            }

            // 更新 .anchors.json
            if (anchors.isEmpty()) {
                projectAnchors.remove(fileRelPath);
            } else {
                projectAnchors.put(fileRelPath, anchors);
            }
            Files.writeString(anchorsFile,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(projectAnchors));

            // 更新 .project_index.json 里该文件的条目
            updateProjectIndexForFile(projectDir, fileRelPath, anchors);
            return "✅ 已增量更新 " + fileRelPath + "（" + anchors.size() + " 个锚点）";

        } catch (IOException e) {
            logger.error("增量重建失败", e);
            return "❌ 增量重建失败: " + e.getMessage();
        }
    }

    private void updateProjectIndexForFile(Path projectDir, String fileRelPath,
                                           List<Map<String, Object>> anchors) throws IOException {
        Path indexFile = projectDir.resolve(PROJECT_INDEX_NAME);
        Map<String, List<Map<String, Object>>> index = new LinkedHashMap<>();
        if (Files.exists(indexFile)) {
            index = objectMapper.readValue(Files.readString(indexFile, StandardCharsets.UTF_8),
                    new TypeReference<>() {});
        }

        if (anchors.isEmpty()) {
            index.remove(fileRelPath);
        } else {
            List<Map<String, Object>> entries = buildIndexEntries(projectDir.resolve(fileRelPath), anchors);
            if (entries.isEmpty()) index.remove(fileRelPath);
            else index.put(fileRelPath, entries);
        }

        Files.writeString(indexFile,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(index));
    }

    /**
     * 用 StructureParser 解析文件，返回其中的全部方法定义（含类方法）。
     * 解析器不支持该文件或解析失败时返回空列表。
     */
    private List<MethodDefinition> parseMethods(Path file, StructureParserRegistry registry) {
        StructureParser parser = registry.getParser(file);
        if (parser == null) return List.of();

        try {
            FileStructure structure = parser.parse(file);
            List<MethodDefinition> all = new ArrayList<>();
            if (structure.functions() != null) all.addAll(structure.functions());
            if (structure.classes() != null) {
                for (ClassDefinition c : structure.classes()) {
                    if (c.methods() != null) all.addAll(c.methods());
                }
            }
            return all;
        } catch (Exception ex) {
            logger.debug("解析方法列表失败: {} - {}", file, ex.getMessage());
            return List.of();
        }
    }

    /**
     * 为锚点匹配对应的方法名。
     * 规则：
     *   1. 锚点行落在某方法 [startLine, endLine] 内 → 直接返回该方法名
     *   2. 否则取锚点行之后 startLine 最近的方法
     *   3. 都没有则返回 null
     */
    private String findSymbolForAnchor(int anchorLine, List<MethodDefinition> methods) {
        if (methods == null || methods.isEmpty()) return null;

        MethodDefinition best = null;
        int bestDist = Integer.MAX_VALUE;

        for (MethodDefinition m : methods) {
            // 规则 1：锚点在方法内
            if (m.startLine() > 0 && m.endLine() > 0
                    && m.startLine() <= anchorLine && anchorLine <= m.endLine()) {
                return m.name();
            }
            // 规则 2：方法在锚点之后，取最近
            if (m.startLine() >= anchorLine) {
                int dist = m.startLine() - anchorLine;
                if (dist < bestDist) {
                    bestDist = dist;
                    best = m;
                }
            }
        }
        return best != null ? best.name() : null;
    }

    /**
     * 单独重建 .project_index.json。供外部一行调用。
     */
    String rebuildProjectIndex(String projectPath) {
        migrateOldIndexIfNeeded();
        try {
            Path projectDir = PathUtils.safeResolve(projectPath);
            if (!Files.exists(projectDir) || !Files.isDirectory(projectDir)) {
                return "❌ 项目目录不存在: " + projectPath;
            }
            Path indexFile = getIndexPath(projectPath);
            if (indexFile == null || !Files.exists(indexFile)) {
                return rebuild(projectPath);  // 没有 .anchors.json，走完整重建
            }
            // 复用 .anchors.json 的扫描结果不现实（desc 不在其中），直接 rebuild 一次
            return rebuild(projectPath);
        } catch (IOException e) {
            logger.error("重建项目索引失败", e);
            return "❌ 重建项目索引失败: " + e.getMessage();
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
                    if (!file.equals(filePath) && !file.endsWith("/" + filePath)) {
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

    /**
     * 描述指定文件的锚点：返回 id + line + desc。
     * 如果 file 匹配多个文件，返回冲突提示并列出全部路径。
     */
    String describe(String projectPath, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return "❌ 必须指定 file 参数";
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

            // 匹配
            List<String> matched = new ArrayList<>();
            for (String key : projectAnchors.keySet()) {
                if (key.equals(filePath)
                        || key.endsWith("/" + filePath)) {
                    matched.add(key);
                }
            }

            if (matched.isEmpty()) {
                return "📌 项目 " + projectPath + " 中没有找到文件 " + filePath + " 的锚点记录。";
            }
            if (matched.size() > 1) {
                StringBuilder sb = new StringBuilder();
                sb.append("❌ 文件名有歧义：").append(filePath).append(" 匹配到多个文件。\n");
                sb.append("请使用完整路径重新调用：\n");
                for (String m : matched) {
                    sb.append("   - ").append(m).append("\n");
                }
                return sb.toString();
            }

            String key = matched.get(0);
            List<Map<String, Object>> anchors = projectAnchors.get(key);
            if (anchors == null || anchors.isEmpty()) {
                return "📌 文件 " + key + " 中没有锚点记录。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("📄 ").append(key).append("\n");
            for (Map<String, Object> a : anchors) {
                String id = (String) a.get("id");
                int line = (int) a.get("line");
                String desc = (String) a.getOrDefault("desc", "");
                String symbol = (String) a.get("symbol");
                if (desc == null || desc.isEmpty()) desc = "（无描述）";
                sb.append("L").append(line).append(" | ").append(id);
                if (symbol != null && !symbol.isEmpty()) {
                    sb.append(" | ").append(symbol);
                }
                sb.append(" | ").append(desc).append("\n");
            }
            return sb.toString();
        } catch (IOException e) {
            logger.error("描述锚点失败", e);
            return "❌ 描述锚点失败: " + e.getMessage();
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