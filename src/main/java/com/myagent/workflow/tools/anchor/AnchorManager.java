// @anchor: anchorManager_intro
// 锚点工具统一入口：区间读写、脏文件刷新与索引重建
package com.myagent.workflow.tools.anchor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.config.AgentConfig;
import com.myagent.workflow.model.AnchorLocation;
import com.myagent.workflow.tools.FileOperator;
import com.myagent.workflow.tools.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

// @anchor: anchorManager_class
/**
 * 锚点管理器 —— 面向 Agent 的工具入口。
 * <p>
 * 委托 AnchorIndex 处理索引的构建与查询；
 * 自己负责文件内容级别的操作（插入、删除、读取）。
 */
public class AnchorManager {
    private static final Logger logger = LoggerFactory.getLogger(AnchorManager.class);

    private final Map<String, Set<String>> dirtyFiles = new LinkedHashMap<>();

    private final AnchorIndex anchorIndex;
    private final AnchorQuery anchorQuery;
    private final AnchorFormatter anchorFormatter;
    private final String workProject;

    // @anchor: anchorManager_constructor
// 构造：创建内部 AnchorIndex
    public AnchorManager(ObjectMapper objectMapper, String workProject) {
        AnchorIndexCache cache = new AnchorIndexCache(objectMapper);
        this.anchorIndex = new AnchorIndex(objectMapper, cache);
        this.anchorQuery = new AnchorQuery(objectMapper, cache);
        this.anchorFormatter = new AnchorFormatter(objectMapper, cache);
        this.workProject = workProject;
    }

    public AnchorManager(ObjectMapper objectMapper){
        this(objectMapper,null);
    }

    // ===== 转发：索引构建 =====


    // @anchor: anchorManager_buildIndex
// 重建锚点索引
    public String buildAnchorIndex(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) return "❌ 缺少参数 project_path";
        return anchorIndex.rebuild(projectPath);
    }

    // ===== 文件内容操作 =====

    // @anchor: anchorManager_insertAtAnchor
// 在锚点前/后插入代码，并标记文件为脏
    public String insertAtAnchor(String anchorId, String content, String position, String file) {
        if (anchorId == null || anchorId.isBlank()) {
            return "❌ 缺少参数 anchor_id";
        }
        if (content == null) return "❌ 缺少参数 content";
        if (!"before".equals(position) && !"after".equals(position)) {
            return "❌ position 必须是 'before' 或 'after'，收到: " + position;
        }
        AnchorLocation loc = resolveAnchor(anchorId, file);
        if (loc == null) return buildResolveError(anchorId, file);

        if (workProject != null && !workProject.equals(loc.projectPath)) {
            return "❌ 只能修改工作项目 [" + workProject + "]，当前锚点属于: " + loc.projectPath;
        }

        // UPDATE.md 特殊约束：末尾锚点只能在其之前插入
        if ("after".equals(position)
                && loc.filePath != null
                && loc.filePath.toLowerCase().endsWith("update.md")
                && isLastAnchorInFile(loc)) {
            return "❌ UPDATE.md 的末尾锚点 [" + anchorId + "] 只能在其之前插入。\n" +
                    "  请改用 position='before'，以保证末尾锚点始终位于文件末尾。\n";
        }

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

            FileOperator.writeLinesAtomic(filePath, lines);
            onFileModified(loc.projectPath, loc.filePath);
            return "✅ 已在 " + loc.filePath + " 的锚点 [" + anchorId + "] " + position + " 插入代码";

        } catch (IOException e) {
            logger.error("插入代码失败", e);
            return "❌ 插入失败: " + e.getMessage();
        }
    }

    // @anchor: anchorManager_deleteBetweenAnchors
// 删除两锚点之间的内容
    public String deleteBetweenAnchors(String startAnchor, String endAnchor, String file) {
        if (startAnchor == null || startAnchor.isBlank()) return "❌ 缺少参数 startAnchor";
        if (endAnchor == null || endAnchor.isBlank()) return "❌ 缺少参数 endAnchor";

        AnchorLocation startLoc = resolveAnchor(startAnchor, file);
        if (startLoc == null) return buildResolveError(startAnchor, file);

        if (workProject != null && !workProject.equals(startLoc.projectPath)) {
            return "❌ 只能修改工作项目 [" + workProject + "]，当前锚点属于: " + startLoc.projectPath;
        }

        AnchorLocation endLoc = resolveAnchor(endAnchor, file);
        if (endLoc == null) return buildResolveError(endAnchor, file);

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

            FileOperator.writeLinesAtomic(filePath, newLines);
            onFileModified(startLoc.projectPath, startLoc.filePath);

            int deletedLines = (endLine - startLine) - 1;
            return "✅ 已删除从 [" + startAnchor + "] 到 [" + endAnchor + "] 之间的 " + deletedLines + " 行代码";

        } catch (IOException e) {
            logger.error("删除代码块失败", e);
            return "❌ 删除失败: " + e.getMessage();
        }
    }

    // @anchor: anchorManager_readBetweenAnchors
    // 读取两锚点之间的代码
    public String readBetweenAnchors(String startAnchor, String endAnchor, String file) {
        if (startAnchor == null || startAnchor.isBlank()) return "❌ 缺少参数 startAnchor";
        if (endAnchor == null || endAnchor.isBlank()) return "❌ 缺少参数 endAnchor";

        AnchorLocation startLoc = resolveAnchor(startAnchor, file);
        if (startLoc == null) return buildResolveError(startAnchor, file);
        AnchorLocation endLoc = resolveAnchor(endAnchor, file);
        if (endLoc == null) return buildResolveError(endAnchor, file);

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

    // @anchor: anchorManager_markDirty
// 标记某文件为脏，待批量刷新索引
    private void markDirty(String projectPath, String fileRelPath) {
        dirtyFiles.computeIfAbsent(projectPath, k -> new LinkedHashSet<>()).add(fileRelPath);
    }

    // @anchor: anchorManager_splitSandboxPath
    /**
     * 把沙箱相对路径统一归一化为 (projectPath, fileRelPath)。
     * 兼容形态：
     *   "sandbox/foo/bar.java" → ["foo", "bar.java"]
     *   "foo/bar.java"         → ["foo", "bar.java"]
     *   "./foo/bar.java"       → ["foo", "bar.java"]
     *   "\\foo\\bar.java"      → ["foo", "bar.java"]
     * 无法推导时（无 /、剥完为空）返回 null。
     */
    private static String[] splitSandboxPath(String filename) {
        if (filename == null || filename.isBlank()) return null;
        String n = filename.replace('\\', '/').trim();
        while (n.startsWith("./")) n = n.substring(2);
        if (n.startsWith("sandbox/")) {
            n = n.substring("sandbox/".length());
        }
        if (n.isBlank()) return null;
        int slash = n.indexOf('/');
        if (slash <= 0) return null;
        return new String[]{ n.substring(0, slash), n.substring(slash + 1) };
    }

    // @anchor: anchorManager_markDirtyByFilename
    /**
     * 从沙箱相对路径推导 (projectPath, fileRelPath)，标记为脏文件。
     * 项目目录不存在时静默跳过；索引缺失由 flushDirty 兜底触发全量重建。
     */
    public void markDirtyByFilename(String filename) {
        String[] parts = splitSandboxPath(filename);
        if (parts == null) return;
        String projectPath = parts[0];
        String fileRelPath = parts[1];
        try {
            Path projectDir = PathUtils.safeResolve(projectPath);
            if (!Files.isDirectory(projectDir)) return;   // 项目目录不存在，跳过
        } catch (IOException e) {
            return;
        }

        onFileModified(projectPath, fileRelPath);
    }

    // @anchor: anchorManager_flushDirty
    /**
     * 批量刷新所有脏文件。由 ToolExecutor 在一轮工具调用结束后触发。
     */
    public void flushDirty() {
        if (dirtyFiles.isEmpty()) return;
        Set<String> fullRebuiltProjects = new HashSet<>();
        for (Map.Entry<String, Set<String>> e : dirtyFiles.entrySet()) {
            String project = e.getKey();
            try {
                Path projectDir = PathUtils.safeResolve(project);
                Path anchorsFile = projectDir.resolve(AgentConfig.getAnchorIndexName());
                if (!Files.exists(anchorsFile)) {
                    if (fullRebuiltProjects.contains(project)) continue;
                    anchorIndex.rebuild(project);
                    fullRebuiltProjects.add(project);
                } else {
                    // O5：整个项目一次读、多次改、一次写
                    anchorIndex.refreshProjectIndexFiles(project, e.getValue());
                }
            } catch (Exception ex) {
                logger.warn("批量刷新脏文件失败: {} - {}", project, ex.getMessage());
            }
        }
        dirtyFiles.clear();
    }

// @anchor: anchorManager_resolveAnchor
    /**
     * 解析锚点。
     * - fileHint 为空：全局查找，唯一命中才返回；多命中或无命中返回 null。
     * - fileHint 非空：按文件过滤，唯一命中才返回。
     */
    private AnchorLocation resolveAnchor(String anchorId, String fileHint) {
        if (fileHint == null || fileHint.isBlank()) {
            List<AnchorLocation> all = anchorQuery.findAllGlobally(anchorId, workProject);
            return all.size() == 1 ? all.get(0) : null;
        }
        return anchorQuery.findInFile(anchorId, fileHint, workProject);
    }

// @anchor: anchorManager_buildResolveError
    /**
     * 生成消歧失败时的错误信息。
     */
    private String buildResolveError(String anchorId, String fileHint) {
        if (fileHint != null && !fileHint.isBlank()) {
            return "❌ 在文件 " + fileHint + " 中未找到唯一锚点: " + anchorId;
        }
        List<AnchorLocation> all = anchorQuery.findAllGlobally(anchorId, workProject);
        if (all.isEmpty()) return "❌ 锚点不存在: " + anchorId;

        StringBuilder sb = new StringBuilder();
        sb.append("❌ 锚点 '").append(anchorId).append("' 在 ")
                .append(all.size()).append(" 个文件中出现：\n");
        for (AnchorLocation loc : all) {
            sb.append("   - ").append(loc.projectPath).append("/").append(loc.filePath)
                    .append(" (L").append(loc.line).append(")\n");
        }
        sb.append("请在 file 参数中指定目标文件。\n");
        sb.append("建议：后续避免跨文件使用相同锚点 ID。");
        return sb.toString();
    }

    // @anchor: anchorManager_isLastAnchorInFile
    /**
     * 判断给定锚点是否是其所在文件中的末位锚点（行号最大）。
     * 用于 UPDATE.md 的末尾锚点插入约束。
     * 查询失败时返回 false，保守放行（不阻塞正常插入）。
     */
    private boolean isLastAnchorInFile(AnchorLocation loc) {
        try {
            Path filePath = PathUtils.safeResolve(loc.projectPath, loc.filePath);
            if (!Files.exists(filePath)) return false;
            List<Map<String, Object>> anchors = AnchorScanner.scan(filePath);
            int maxLine = 0;
            for (Map<String, Object> a : anchors) {
                Object lineObj = a.get("line");
                if (lineObj instanceof Integer line && line > maxLine) maxLine = line;
            }
            return loc.line == maxLine && maxLine > 0;
        } catch (IOException e) {
            return false;
        }
    }

    // ===== 转发：查找锚点 =====

    // @anchor: anchorManager_findAnchor
    /**
     * 在指定项目中查找锚点。
     * <p>
     * 由 CallGraphAnalyzer 等模块通过 AnchorManager 调用，避免它们直接依赖 AnchorIndex。
     */
    public AnchorLocation findAnchor(String projectPath, String anchorId) {
        return anchorQuery.find(projectPath, anchorId);
    }

    // @anchor: anchorManager_rebuildProjectIndex
// 重建整个项目的锚点索引文件
    public String rebuildProjectIndex(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) return "❌ 缺少参数 project_path";
        return anchorIndex.rebuildProjectIndex(projectPath);
    }

    // @anchor: anchorManager_describeAnchors
// 返回各锚点及其紧邻描述
    public String describeAnchors(String projectPath, String filePath) {
        if (projectPath == null || projectPath.isBlank()) return "❌ 缺少参数 project_path";

        return anchorFormatter.describe(projectPath, filePath);
    }

    // @anchor: anchorManager_onFileModified
    /**
     * 文件被改动后的统一处理：立即刷新锚点位置（保证同轮后续编辑使用新行号），
     * 标记为脏文件等待本批工具结束后刷新 desc/symbol。
     */
    private void onFileModified(String projectPath, String fileRelPath) {
        anchorIndex.refreshAnchorsFile(projectPath, fileRelPath);
        markDirty(projectPath, fileRelPath);
    }

    // @anchor: anchorManager_describeByPath
    // 从沙箱相对路径推导 (project, file) 并转发给 describeAnchors。
    // 用于 get_file_structure 在 .md 分支上的转发。
    public String describeAnchorsByPath(String filename) {
        String[] parts = splitSandboxPath(filename);
        if (parts == null) return "❌ 无法推导项目: " + filename;
        String project = parts[0];
        String file = parts[1];
        String result = describeAnchors(project, file);
        if (result.startsWith("📌") && result.contains("中没有找到文件")) {
            return "📄 " + file + " 是纯文本文件，无代码结构，且未标注任何锚点。";
        }
        return result;
    }

    // @anchor: anchorManager_isProjectFresh
    // 转发 AnchorIndex 的 freshness 判定
    public boolean isProjectFresh(String projectPath) {
        return anchorIndex.isProjectFresh(projectPath);
    }
}
