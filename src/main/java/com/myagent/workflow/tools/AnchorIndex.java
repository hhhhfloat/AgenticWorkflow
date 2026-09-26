package com.myagent.workflow.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.AgentConfig;
import com.myagent.workflow.model.AnchorLocation;
import com.myagent.workflow.model.MethodDefinition;
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
 * - 扫描项目源码，提取 @anchor 注释，写入 .anchors.json 与 .project_index.json
 * - 从索引中查询锚点位置
 * - 全局查找锚点（遍历所有项目）
 * - 格式化锚点列表供 Agent 消费
 * <p>
 * 由 AnchorManager 持有。不涉及文件内容修改。
 */
// @anchor: anchorIndex_class
// 锚点索引：构建并查询 .anchors.json（坐标）与 .project_index.json（描述+符号）
class AnchorIndex {
    private static final Logger logger = LoggerFactory.getLogger(AnchorIndex.class);
    private final ObjectMapper objectMapper;
    private boolean migrationAttempted = false;
    private static final String PROJECT_INDEX_NAME = ".project_index.json";
    private static final Pattern END_ANCHOR_PATTERN = Pattern.compile("_end(\\d+)?$");

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "target", "node_modules", ".git", "classes", "build", "dist", "out");

    // @anchor: anchorIndex_constructor
// 构造：注入 ObjectMapper
    AnchorIndex(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ===== 索引路径 =====

    // @anchor: anchorIndex_getIndexPath
// 返回索引文件（.anchors.json）路径
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
// 把旧的 .anchor_index.json 迁移为各项目的 .anchors.json 并备份原文件
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

    /**
     * 重建锚点索引：扫描项目下的所有源文件，提取 @anchor 注释，
     * 重写 .anchors.json（坐标）与 .project_index.json（描述+符号）。
     */
    // @anchor: anchorIndex_rebuild
    // 全量重建：扫描项目全部文本文件，重写两份索引文件
    String rebuild(String projectPath) {
        migrateOldIndexIfNeeded();

        try {
            Path projectDir = PathUtils.safeResolve(projectPath);
            if (!Files.exists(projectDir) || !Files.isDirectory(projectDir)) {
                return "❌ 项目目录不存在: " + projectPath;
            }

            Map<String, List<Map<String, Object>>> projectAnchors = new LinkedHashMap<>();

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
                            List<Map<String, Object>> anchors = AnchorScanner.scan(file);
                            if (!anchors.isEmpty()) projectAnchors.put(rel, anchors);
                        } catch (IOException ignored) {}
                    });

            // 写 .anchors.json（精简版：id + line + preview）
            Path indexFile = getIndexPath(projectPath);
            if (indexFile == null) {
                return "❌ 项目路径无效: " + projectPath;
            }

            Files.writeString(indexFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(AnchorScanner.leanize(projectAnchors)));

            // 写 .project_index.json（精简版：id + line + desc）
            writeProjectIndex(projectPath, projectAnchors);

            int totalFiles = projectAnchors.size();
            int totalAnchors = projectAnchors.values().stream().mapToInt(List::size).sum();

            StringBuilder result = new StringBuilder();
            result.append("✅ 锚点索引已重建：").append(projectPath)
                    .append(" 中找到 ").append(totalAnchors).append(" 个锚点")
                    .append("，分布在 ").append(totalFiles).append(" 个文件中。");
            return result.toString();

        } catch (IOException e) {
            logger.error("重建锚点索引失败", e);
            return "❌ 重建锚点索引失败: " + e.getMessage();
        }
    }


    // @anchor: anchorIndex_buildIndexEntries
// 把锚点列表转为项目索引条目（id/line/desc/symbol），跳过 _end 锚点
    private List<Map<String, Object>> buildIndexEntries(Path file, List<Map<String, Object>> anchors) {
        StructureParserRegistry registry = StructureParserRegistry.getInstance();
        List<MethodDefinition> methods = AnchorScanner.parseMethods(file, registry);
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
                String symbol = AnchorScanner.findSymbolForAnchor(line, methods);
                if (symbol != null && !symbol.isEmpty()) m.put("symbol", symbol);
            }
            list.add(m);
        }
        return list;
    }

    /**
     * 将锚点结果（含 desc）写入 .project_index.json。
     */
    // @anchor: anchorIndex_writeProjectIndex
    // 把锚点结果（含描述）写入 .project_index.json
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
     * 单文件全刷：先刷新位置，再刷新 desc/symbol。
     * 索引文件不存在时退化为全量 rebuild。
     */
    String rebuildFile(String projectPath, String fileRelPath) {
        String r1 = refreshAnchorsFile(projectPath, fileRelPath);
        String r2 = refreshProjectIndexFile(projectPath, fileRelPath);
        return r1 + " | " + r2;
    }

// @anchor: anchorIndex_refreshAnchorsFile
    /**
     * 只刷新 .anchors.json 里该文件的位置（id + line + preview）。
     * 供编辑操作后立即调用，不跑 AST 解析，快速保证行号准确。
     * 索引文件不存在时退化为全量 rebuild。
     */
    String refreshAnchorsFile(String projectPath, String fileRelPath) {
        try {
            Path projectDir = PathUtils.safeResolve(projectPath);
            Path anchorsFile = getIndexPath(projectPath);
            if (anchorsFile == null || !Files.exists(anchorsFile)) {
                return rebuild(projectPath);
            }

            String content = Files.readString(anchorsFile, StandardCharsets.UTF_8);
            Map<String, List<Map<String, Object>>> projectAnchors =
                    objectMapper.readValue(content, new TypeReference<>() {});


            Path file = projectDir.resolve(fileRelPath);
            List<Map<String, Object>> anchors;
            if (!Files.exists(file) || !Files.isRegularFile(file)) {
                anchors = List.of();
            } else {
                anchors = AnchorScanner.scan(file);
            }

            if (anchors.isEmpty()) {
                projectAnchors.remove(fileRelPath);
            } else {
                projectAnchors.put(fileRelPath, anchors);
            }
            Files.writeString(anchorsFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(AnchorScanner.leanize(projectAnchors)));
            return "✅ 已刷新位置 " + fileRelPath + "（" + anchors.size() + " 个锚点）";

        } catch (IOException e) {
            logger.error("刷新锚点位置失败", e);
            return "❌ 刷新锚点位置失败: " + e.getMessage();
        }
    }

// @anchor: anchorIndex_refreshProjectIndexFile
    /**
     * 只刷新 .project_index.json 里该文件的条目（desc + symbol）。
     * 供 flushDirty 一轮结束统一调用，代价是 AST 解析，较慢。
     */
    String refreshProjectIndexFile(String projectPath, String fileRelPath) {
        try {
            Path projectDir = PathUtils.safeResolve(projectPath);

            Path file = projectDir.resolve(fileRelPath);
            List<Map<String, Object>> anchors;
            if(!Files.exists(file) || !Files.isRegularFile(file)){
                anchors = List.of();
            } else{
                anchors = AnchorScanner.scan(file);
            }
            updateProjectIndexForFile(projectDir, fileRelPath, anchors);

            return "✅ 已刷新描述 " + fileRelPath;

        } catch (IOException e) {
            logger.error("刷新项目索引失败", e);
            return "❌ 刷新项目索引失败: " + e.getMessage();
        }
    }

    // @anchor: anchorIndex_updateProjectIndexForFile
// 更新 .project_index.json 中单个文件的条目（无锚点时移除）
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
     * 单独重建 .project_index.json。供外部一行调用。
     */
    // @anchor: anchorIndex_rebuildProjectIndex
    // 重建项目的 .project_index.json（缺 .anchors.json 时退化为全量重建）
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

}
