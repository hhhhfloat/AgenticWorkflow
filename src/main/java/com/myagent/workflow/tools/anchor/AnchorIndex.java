// @anchor: anchorIndex_intro
// 锚点索引构建：生成与增量刷新 .anchors.json / .project_index.json
package com.myagent.workflow.tools.anchor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.config.AgentConfig;
import com.myagent.workflow.model.MethodDefinition;
import com.myagent.workflow.parser.StructureParserRegistry;
import com.myagent.workflow.tools.FileOperator;
import com.myagent.workflow.tools.PathUtils;
import com.myagent.workflow.tools.ProjectLayout;
import com.myagent.workflow.tools.search.SearchFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

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
    private final AnchorIndexCache cache;
    private boolean migrationAttempted = false;
    private static final String PROJECT_INDEX_NAME = ".project_index.json";



    // @anchor: anchorIndex_constructor
// 构造：注入 ObjectMapper
    AnchorIndex(ObjectMapper objectMapper, AnchorIndexCache cache) {
        this.objectMapper = objectMapper;
        this.cache = cache;
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
                FileOperator.writeAtomic(newIndex, json);
                cache.invalidate(newIndex);

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

            // O2: 用剪枝遍历，跳过 .git / target / node_modules 等大目录
            List<Path> files = SearchFileFilter.collectFiles(
                    projectDir,
                    SearchFileFilter.DEFAULT_EXCLUDED_DIRS,
                    List.of());   // 文件级排除交给下方循环（点开头 + 具体索引名）

            for (Path file : files) {
                String name = file.getFileName().toString();
                // 统一口径：隐藏名（含索引文件）+ 显式排除文件
                if (ProjectLayout.isHiddenBasename(name)) continue;
                if (ProjectLayout.isExcludedFile(name)) continue;
                try {
                    String rel = projectDir.relativize(file).toString().replace('\\', '/');
                    List<Map<String, Object>> anchors = AnchorScanner.scan(file);
                    if (!anchors.isEmpty()) projectAnchors.put(rel, anchors);
                } catch (IOException e) {
                    logger.warn("扫描失败，跳过该文件: {} - {}",
                            projectDir.relativize(file), e.getMessage());
                }
            }
            // 写 .anchors.json（精简版：id + line + preview）
            Path indexFile = getIndexPath(projectPath);
            if (indexFile == null) {
                return "❌ 项目路径无效: " + projectPath;
            }

            // O8：先在内存中构造两份 JSON，序列化失败不会留下部分写入
            String anchorsJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(AnchorScanner.leanize(projectAnchors));
            String projectIndexJson = buildProjectIndexJson(projectPath, projectAnchors);

            // 先写 .anchors.json；失败则中止，两份都是旧状态
            FileOperator.writeAtomic(indexFile, anchorsJson);
            cache.invalidate(indexFile);

            // 再写 .project_index.json；失败时 .anchors.json 已是新版本，
            // 但 isProjectFresh 会因 .project_index.json 的旧 mtime 判定为 stale，
            // 下一次任务收尾自动重建 —— 不会永久分叉
            Path projIdxFile = PathUtils.safeResolve(projectPath).resolve(PROJECT_INDEX_NAME);
            FileOperator.writeAtomic(projIdxFile, projectIndexJson);
            cache.invalidate(projIdxFile);

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
    // 把锚点列表转为项目索引条目（id/line/desc/symbol），包含全部锚点
    private List<Map<String, Object>> buildIndexEntries(Path file, List<Map<String, Object>> anchors) {
        StructureParserRegistry registry = StructureParserRegistry.getInstance();
        List<MethodDefinition> methods = AnchorScanner.parseMethods(file, registry);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> a : anchors) {
            String id = (String) a.get("id");
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
    // @anchor: anchorIndex_buildProjectIndexJson
    // O8：只构造 .project_index.json 的 JSON 字符串，不写盘
    private String buildProjectIndexJson(String projectPath,
                             Map<String, List<Map<String, Object>>> projectAnchors) throws IOException {
        Path projectDir = PathUtils.safeResolve(projectPath);
        Map<String, List<Map<String, Object>>> index = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : projectAnchors.entrySet()) {
            String relPath = e.getKey();
            Path filePath = projectDir.resolve(relPath);
            List<Map<String, Object>> entries = buildIndexEntries(filePath, e.getValue());
            if (!entries.isEmpty()) index.put(relPath, entries);
        }
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(index);
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
            // 复制一份可变 Map，不污染缓存持有的对象
            Map<String, List<Map<String, Object>>> projectAnchors = new LinkedHashMap<>(cache.load(anchorsFile));

            Path file = projectDir.resolve(fileRelPath);
            List<Map<String, Object>> anchors;
            if (!Files.exists(file) || !Files.isRegularFile(file)) {
                anchors = List.of();
            } else {
                // 排除目录里的文件不进索引（与 rebuild / refreshAnchorsFile 保持同一口径）
                Path rel = projectDir.relativize(file);
                String basename = file.getFileName().toString();
                if (SearchFileFilter.isExcludedDir(rel, SearchFileFilter.DEFAULT_EXCLUDED_DIRS)
                        || ProjectLayout.isHiddenBasename(basename)
                        || ProjectLayout.isExcludedFile(basename)) {
                    anchors = List.of();
                } else {
                    anchors = AnchorScanner.scan(file);
                }
            }

            if (anchors.isEmpty()) {
                projectAnchors.remove(fileRelPath);
            } else {
                projectAnchors.put(fileRelPath, anchors);
            }
            FileOperator.writeAtomic(anchorsFile,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(AnchorScanner.leanize(projectAnchors)));
            cache.invalidate(anchorsFile);
            return "✅ 已刷新位置 " + fileRelPath + "（" + anchors.size() + " 个锚点）";

        } catch (IOException e) {
            logger.error("刷新锚点位置失败", e);
            return "❌ 刷新锚点位置失败: " + e.getMessage();
        }
    }

    // @anchor: anchorIndex_refreshProjectIndexFiles
    /**
     * 批量更新一个项目的多个文件条目（O5）：
     * 一次读、多次改、一次写 .project_index.json，替代逐文件 refreshProjectIndexFile。
     */
    String refreshProjectIndexFiles(String projectPath, Set<String> fileRelPaths) {
        try {
            Path projectDir = PathUtils.safeResolve(projectPath);
            Path indexFile = projectDir.resolve(PROJECT_INDEX_NAME);

            Map<String, List<Map<String, Object>>> index = new LinkedHashMap<>();
            if (Files.exists(indexFile)) {
                index = new LinkedHashMap<>(cache.load(indexFile));
            }

            for (String fileRelPath : fileRelPaths) {
                Path file = projectDir.resolve(fileRelPath);
                List<Map<String, Object>> anchors;
                if (!Files.exists(file) || !Files.isRegularFile(file)) {
                    anchors = List.of();
                } else {
                    Path rel = projectDir.relativize(file);
                    String basename = file.getFileName().toString();
                    if (SearchFileFilter.isExcludedDir(rel, SearchFileFilter.DEFAULT_EXCLUDED_DIRS)
                            || ProjectLayout.isHiddenBasename(basename)
                            || ProjectLayout.isExcludedFile(basename)) {
                        anchors = List.of();
                    } else {
                        try {
                                anchors = AnchorScanner.scan(file);
                            } catch (IOException e) {
                            // O7：扫描失败时保留索引中的旧条目，不中止其他文件的刷新
                            logger.warn("刷新描述失败，保留旧条目: {} - {}",
                                    fileRelPath, e.getMessage());
                            continue;
                        }
                    }
                }

                if (anchors.isEmpty()) {
                    index.remove(fileRelPath);
                } else {
                    List<Map<String, Object>> entries = buildIndexEntries(file, anchors);
                    if (entries.isEmpty()) index.remove(fileRelPath);
                    else index.put(fileRelPath, entries);
                }
            }

            FileOperator.writeAtomic(indexFile,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(index));
            cache.invalidate(indexFile);
            return "✅ 已刷新 " + fileRelPaths.size() + " 个文件的描述";
        } catch (IOException e) {
            logger.error("批量刷新项目索引失败", e);
            return "❌ 批量刷新项目索引失败: " + e.getMessage();
        }
    }

    // @anchor: anchorIndex_isProjectFresh
    /**
     * O1：判断项目索引是否新鲜。
     * 判定基准：min(.anchors.json mtime, .project_index.json mtime) >= 源文件/子目录最大 mtime。
     * 取 min 而非只看 .anchors.json，是为了覆盖"Agent 写文件后 .anchors.json 已更新、
     * 但 .project_index.json 尚未被 flushDirty 刷新"的中间态。
     * projectDir 自身的 mtime 不计入源侧（它会被索引文件写入污染）。
     */
    boolean isProjectFresh(String projectPath) {
        try {
            Path projectDir = PathUtils.safeResolve(projectPath);
            if (!Files.isDirectory(projectDir)) return true;

            Path anchorsFile = projectDir.resolve(AgentConfig.getAnchorIndexName());
            Path projIdxFile = projectDir.resolve(PROJECT_INDEX_NAME);
            if (!Files.exists(anchorsFile) || !Files.exists(projIdxFile)) return false;

            long anchorsMtime = Files.getLastModifiedTime(anchorsFile).toMillis();
            long projIdxMtime = Files.getLastModifiedTime(projIdxFile).toMillis();
            // 取 min 作为基准：两份索引都必须晚于源侧最大 mtime，否则判 stale。
            // 注意：不判 "anchorsMtime != projIdxMtime 则 stale"。
            // Agent 每轮写文件后，refreshAnchorsFile 只更新 .anchors.json，
            // 轮末 flushDirty 才更新 .project_index.json；两次原子写 mtime 必然不同。
            // 若以此判 stale，将导致每轮收尾都全量重建，抵消 O1 的性能优化。
            // 分叉（第二次写失败）场景由 "srcMax > min" 兜底：只要本轮有文件被写，
            // 源文件 mtime 就会超过旧的 .project_index.json，判 stale → 自愈。
            long indexMtime = Math.min(anchorsMtime, projIdxMtime);

            long srcMax = computeSourceMaxMtime(projectDir);
            return srcMax <= indexMtime;
        } catch (IOException e) {
            logger.warn("freshness 检查失败: {} - {}", projectPath, e.getMessage());
            return false;
        }
    }

    // @anchor: anchorIndex_computeSourceMaxMtime
    // 剪枝遍历项目，取源文件与子目录（不含 projectDir 自身）的最大 mtime
    private long computeSourceMaxMtime(Path projectDir) throws IOException {
        final long[] max = { 0L };
        Files.walkFileTree(projectDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(projectDir)) {
                    String name = dir.getFileName().toString();
                    if (SearchFileFilter.DEFAULT_EXCLUDED_DIRS.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    max[0] = Math.max(max[0], attrs.lastModifiedTime().toMillis());
                }
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    String name = file.getFileName().toString();
                    if (!name.equals(AgentConfig.getAnchorIndexName())
                            && !name.equals(PROJECT_INDEX_NAME)
                            && !SearchFileFilter.DEFAULT_EXCLUDED_FILES.contains(name)
                            && !name.startsWith(".")) {
                        max[0] = Math.max(max[0], attrs.lastModifiedTime().toMillis());
                    }
                }
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return max[0];
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
