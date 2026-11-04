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
import java.util.function.Consumer;
import java.util.*;

// @anchor: anchorManager_class
/**
 * 锚点管理器 —— 面向 Agent 的工具入口。
 * <p>
 * 委托 AnchorIndex 处理索引的构建与查询；
 * 委托 AnchorResolver 处理锚点定位与相似度报错；
 * 自己负责文件内容级别的操作（插入、删除、读取、编辑）与索引导航。
 */
public class AnchorManager {
    private static final Logger logger = LoggerFactory.getLogger(AnchorManager.class);

    private final Map<String, Set<String>> dirtyFiles = new LinkedHashMap<>();

    private final AnchorIndex anchorIndex;
    private final AnchorQuery anchorQuery;
    private final AnchorFormatter anchorFormatter;
    private final AnchorResolver resolver;
    private final String workProject;

    // @anchor: anchorManager_constructor
    // 构造：创建内部 AnchorIndex 与 Resolver
    public AnchorManager(ObjectMapper objectMapper, String workProject) {
        AnchorIndexCache cache = new AnchorIndexCache(objectMapper);
        this.anchorIndex = new AnchorIndex(objectMapper, cache);
        this.anchorQuery = new AnchorQuery(objectMapper, cache);
        this.anchorFormatter = new AnchorFormatter(objectMapper, cache);
        this.resolver = new AnchorResolver(anchorQuery, workProject);
        this.workProject = workProject;
    }

    public AnchorManager(ObjectMapper objectMapper){
        this(objectMapper,null);
    }

    // ===== 转发：索引构建 =====

    // @anchor: anchorManager_buildIndex
    // 重建锚点索引（显式沙箱校验：拦绝对路径与 ".."，错误信息比底层 IOException 清晰）
    public String buildAnchorIndex(String projectPath) {
        return buildAnchorIndex(projectPath, null);
    }

    // @anchor: anchorManager_buildIndex
    // 工具入口：Agent 显式调用 build_anchor_index 时走这里
    public String buildAnchorIndex(String projectPath, Consumer<String> progress) {
        return rebuildInternal(projectPath, "build_anchor_index", progress);
    }

    // @anchor: anchorManager_refreshIndex
    // 内部刷新入口：启动/结束自动刷新、脏文件兜底全量时走这里
    // 与 buildAnchorIndex 共用底层，tag 区分来源（前端显示不同文案）
    public String refreshProjectIndexes(String projectPath, Consumer<String> progress) {
        return rebuildInternal(projectPath, "refresh_index", progress);
    }

    // @anchor: anchorManager_rebuildInternal
    // 全量重建的唯一底层入口：显式路径校验 + 统一进度格式
    // 进度消息格式：<tag> <project> <phase> <cur>/<total> <file>
    private String rebuildInternal(String projectPath, String tag, Consumer<String> progress) {
        if (projectPath == null || projectPath.isBlank()) return "❌ 缺少参数 project_path";
        try {
            PathUtils.safeResolve(projectPath);
        } catch (IOException e) {
            return "❌ 非法的项目路径: " + projectPath + "（" + e.getMessage() + "）";
        }
        return anchorIndex.rebuild(projectPath, p -> {
            if (progress == null) return;
            String phaseLabel = "scan".equals(p.phase()) ? "扫描" : "解析";
            progress.accept(String.format("%s %s %s %d/%d %s",
                    tag, projectPath, phaseLabel, p.current(), p.total(), p.currentFile()));
        });
    }

    // ===== 文件内容操作 =====

    // @anchor: anchorManager_insertAtAnchor
    // 在锚点前/后插入代码，并标记文件为脏
    public String insertAtAnchor(String anchorId, String content, String position, String file) {
        anchorId = normalizeAnchorId(anchorId);
        if (anchorId == null || anchorId.isBlank()) {
            return "❌ 缺少参数 anchor_id";
        }
        if (content == null) return "❌ 缺少参数 content";
        
        position = normalizePosition(position);
        if (!"before".equals(position) && !"after".equals(position)) {
            return "❌ position 必须是 'before' 或 'after'，收到: " + position;
        }
        AnchorResolver.ResolveResult locRes = resolver.resolveOrError(anchorId, file);
        if (!locRes.isOk()) return locRes.error();
        AnchorLocation loc = locRes.location();

        String permErr = checkWorkProjectPermission(loc);
        if (permErr != null) return permErr;
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
    // 删除两锚点之间的内容（严格模式：必须双参数、顺序正确，不支持自动配对）
    public String deleteBetweenAnchors(String startAnchor, String endAnchor, String file) {
        startAnchor = normalizeAnchorId(startAnchor);
        endAnchor = normalizeAnchorId(endAnchor);

        if (startAnchor == null || startAnchor.isBlank()) return "❌ 缺少参数 startAnchor";
        if (endAnchor == null || endAnchor.isBlank()) return "❌ 缺少参数 endAnchor";
        if (startAnchor.equals(endAnchor)) {
            return "❌ 无法删除：startAnchor 和 endAnchor 不能相同。\n" +
                   "  💡 删除操作需要明确界定区间边界，请提供两个不同的锚点 ID（例如 'my_func' 和 'my_func_end'）。";
        }

        AnchorResolver.ResolveResult startRes = resolver.resolveOrError(startAnchor, file);
        if (!startRes.isOk()) return startRes.error();
        AnchorLocation startLoc = startRes.location();

        AnchorResolver.ResolveResult endRes = resolver.resolveOrError(endAnchor, file);
        if (!endRes.isOk()) return endRes.error();
        AnchorLocation endLoc = endRes.location();

        String permErr = checkWorkProjectPermission(startLoc);
        if (permErr != null) return permErr;

        // UPDATE.md 检查
        if (startLoc.filePath != null && startLoc.filePath.toLowerCase().endsWith("update.md")) {
            return "❌ UPDATE.md 只允许追加，不允许删除内容。\n"
                    + "  如需修正记录，请用 insert_at_anchor 在末尾锚点之前插入新记录。";
        }

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
                    "  起始锚点所在行 " + startLoc.line + "\n" +
                    "  结束锚点所在行 " + endLoc.line + "\n" +
                    "  💡 检查是否把 startAnchor 和 endAnchor 传反了。";
        }

        try {
            Path filePath = PathUtils.safeResolve(startLoc.projectPath, startLoc.filePath);
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);

            int startLine = startLoc.line - 1;
            int endLine = endLoc.line - 1;

            if (endLine - startLine <= 1) {
                return "⚠️ 锚点间无内容可删除";
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
            return "✅ 已删除从 [" + startAnchor + "] 到 [" + endAnchor + "] 之间的 "
                    + deletedLines + " 行代码";
        } catch (IOException e) {
            logger.error("删除代码块失败", e);
            return "❌ 删除失败: " + e.getMessage();
        }
    }

    // @anchor: anchorManager_readBetweenAnchors
    // 读取两锚点之间的代码（宽容模式：支持单锚点回退、支持传反自动纠正）
    public String readBetweenAnchors(String startAnchor, String endAnchor, String file) {
        startAnchor = normalizeAnchorId(startAnchor);
        endAnchor = normalizeAnchorId(endAnchor);

        if (startAnchor == null || startAnchor.isBlank()) return "❌ 缺少参数 startAnchor";
        if (endAnchor == null || endAnchor.isBlank()) return "❌ 缺少参数 endAnchor";

        AnchorResolver.ResolveResult startRes = resolver.resolveOrError(startAnchor, file);
        if (!startRes.isOk()) return startRes.error();
        AnchorLocation startLoc = startRes.location();

        String effectiveEndAnchor = endAnchor;
        AnchorLocation endLoc = null;
        String note = "";

        if (startAnchor.equals(endAnchor) && !startAnchor.endsWith("_end")) {
            // 场景1：单锚点读取，优先找配对的 _end
            String pairedId = startAnchor + "_end";
            AnchorResolver.ResolveResult pairedRes = resolver.resolveOrError(pairedId, file);
            
            if (pairedRes.isOk()) {
                effectiveEndAnchor = pairedId;
                endLoc = pairedRes.location();
                note = "（单锚点快捷读取，已自动匹配到配对锚点）";
            } else {
                // 场景2：没有配对 _end，找文件中的下一个锚点
                List<AnchorLocation> anchorsInFile = anchorQuery.listAnchorsInFile(startLoc.filePath, workProject);
                anchorsInFile.sort(Comparator.comparingInt(a -> a.line));
                
                for (AnchorLocation a : anchorsInFile) {
                    if (a.line > startLoc.line) {
                        endLoc = a;
                        effectiveEndAnchor = a.id;
                        note = "（单锚点回退，已自动读取到下一个锚点）";
                        break;
                    }
                }
                
                // 场景3：已经是文件最后一个锚点，读到文件末尾
                if (endLoc == null) {
                    try {
                        Path filePath = PathUtils.safeResolve(startLoc.projectPath, startLoc.filePath);
                        List<String> allLines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
                        int totalLines = allLines.size();
                        
                        endLoc = new AnchorLocation();
                        endLoc.projectPath = startLoc.projectPath;
                        endLoc.filePath = startLoc.filePath;
                        endLoc.line = totalLines;
                        endLoc.id = "[EOF]";
                        effectiveEndAnchor = "[EOF] 文件末尾";
                        note = "（单锚点回退，已读取至文件末尾）";
                    } catch (IOException e) {
                        logger.error("读取文件末尾失败: {}", startLoc.filePath, e);
                        return "❌ 读取文件末尾失败: " + e.getMessage();
                    }
                }
            }
        } else {
            // 场景4：传了两个不同的参数
            AnchorResolver.ResolveResult endRes = resolver.resolveOrError(endAnchor, file);
            if (!endRes.isOk()) return endRes.error();
            endLoc = endRes.location();

            // 宽容处理：传反了自动纠正
            if (startLoc.line > endLoc.line) {
                AnchorLocation temp = startLoc;
                startLoc = endLoc;
                endLoc = temp;
                
                String tempId = startAnchor;
                startAnchor = effectiveEndAnchor;
                effectiveEndAnchor = tempId;
                
                note = "（⚠️ 检测到起止锚点顺序传反，已自动纠正）";
            }
        }

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
                return "⚠️ 锚点间无内容";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("📖 读取 ").append(startLoc.filePath)
                    .append(" 从行 ").append(startLoc.line)
                    .append(" 到行 ").append(endLoc.line)
                    .append("（共 ").append(resultLines.size()).append(" 行） ").append(note).append("\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

            for (int i = 0; i < resultLines.size(); i++) {
                int lineNum = startLoc.line + i;
                sb.append(String.format("%4d | %s", lineNum, resultLines.get(i))).append("\n");
            }

            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("📌 起始锚点: ").append(startAnchor).append(" (行 ").append(startLoc.line).append(")\n");
            sb.append("📌 结束锚点: ").append(effectiveEndAnchor).append(" (行 ").append(endLoc.line).append(")");

            return sb.toString();

        } catch (IOException e) {
            logger.error("读取锚点区间失败", e);
            return "❌ 读取失败: " + e.getMessage();
        }
    }

    // @anchor: anchorManager_editBetweenAnchors
    // 在锚点区间内精确替换 old_string → new_string（方案 B：old_string 只覆盖要改的片段）
    public String editBetweenAnchors(String startAnchor, String endAnchor,
                                    String file, String oldString, String newString) {
        startAnchor = normalizeAnchorId(startAnchor);
        endAnchor = normalizeAnchorId(endAnchor);

        if (startAnchor == null || startAnchor.isBlank()) return "❌ 缺少参数 startAnchor";
        if (endAnchor == null || endAnchor.isBlank()) return "❌ 缺少参数 endAnchor";
        if (oldString == null || oldString.isEmpty()) {
            return "❌ old_string 不能为空。\n" +
                "  💡 若要删除某段内容，请改用 delete_between_anchors";
        }
        if (newString == null) newString = "";  // 允许空字符串 = 删除

        // === 1. 解析锚点（复用宽容读逻辑，但不做传反纠正——写操作要求严格） ===
        AnchorResolver.ResolveResult startRes = resolver.resolveOrError(startAnchor, file);
        if (!startRes.isOk()) return startRes.error();
        AnchorLocation startLoc = startRes.location();

        AnchorLocation endLoc;
        String effectiveEndAnchor;
        if (startAnchor.equals(endAnchor) && !startAnchor.endsWith("_end")) {
            String pairedId = startAnchor + "_end";
            AnchorResolver.ResolveResult pairedRes = resolver.resolveOrError(pairedId, file);
            if (pairedRes.isOk()) {
                effectiveEndAnchor = pairedId;
                endLoc = pairedRes.location();
            } else {
                return "❌ 锚点 " + startAnchor + " 没有配对锚点 " + pairedId + "\n" +
                    "  💡 edit_between_anchors 需要明确的区间。若是最后一个锚点，请显式传入一个更靠后的锚点作为 endAnchor。";
            }
        } else {
            AnchorResolver.ResolveResult endRes = resolver.resolveOrError(endAnchor, file);
            if (!endRes.isOk()) return endRes.error();
            endLoc = endRes.location();
            effectiveEndAnchor = endAnchor;
        }

        // === 2. 权限与一致性校验（严格） ===
        String permErr = checkWorkProjectPermission(startLoc);
        if (permErr != null) return permErr;
        // UPDATE.md 检查：edit 也属于修改既有内容，必须拦住
        if (startLoc.filePath != null && startLoc.filePath.toLowerCase().endsWith("update.md")) {
            return "❌ UPDATE.md 只允许追加，不允许修改既有内容。\n" +
                    "  如需修正记录，请用 insert_at_anchor 在末尾锚点之前插入新记录。";
        }
        if (!startLoc.projectPath.equals(endLoc.projectPath)) {
            return "❌ 两个锚点不在同一个项目中";
        }
        if (!startLoc.filePath.equals(endLoc.filePath)) {
            return "❌ 两个锚点不在同一个文件中";
        }
        if (startLoc.line >= endLoc.line) {
            return "❌ 起始锚点必须在结束锚点之前（行 " + startLoc.line + " >= 行 " + endLoc.line + "）\n" +
                "  💡 检查是否把 startAnchor 和 endAnchor 传反了。";
        }
        
        try {
            Path filePath = PathUtils.safeResolve(startLoc.projectPath, startLoc.filePath);
            if (!Files.exists(filePath)) return "❌ 文件不存在: " + startLoc.filePath;

            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);

            // === 3. 提取区间内容（严格开区间：锚点行本身不可被编辑） ===
            int startLine = startLoc.line - 1;     // 0-based 锚点行
            int endLine = endLoc.line - 1;

            if (endLine - startLine <= 1) {
                return "⚠️ 锚点区间内无内容可编辑（锚点之间只有 " + (endLine - startLine - 1) + " 行）\n" +
                    "  💡 锚点行本身不可被 edit 修改。如需修改锚点行附近，请用 insert_at_anchor。";
            }

            // 区间内容行（不含起止锚点行）
            List<String> intervalLines = new ArrayList<>(lines.subList(startLine + 1, endLine));
            String intervalText = String.join("\n", intervalLines);

            // === 4. 搜索 oldString 在区间内的所有匹配位置 ===
            List<Integer> matches = AnchorEditDiagnostics.findAllOccurrences(intervalText, oldString);

            if (matches.isEmpty()) {
                return AnchorEditDiagnostics.buildNotFoundError(intervalLines, oldString, startLoc, endLoc);
            }
            if (matches.size() > 1) {
                return AnchorEditDiagnostics.buildAmbiguousError(intervalText, oldString, matches, startLoc, endLoc, effectiveEndAnchor);
            }

            // === 5. 唯一匹配：执行替换 ===
            int idx = matches.get(0);
            String newIntervalText = intervalText.substring(0, idx)
                    + newString
                    + intervalText.substring(idx + oldString.length());

            // 把替换后的内容按 \n 切回行，注意保留尾部空串（trailing newline 场景）
            List<String> newIntervalLines = new ArrayList<>(
                    Arrays.asList(newIntervalText.split("\n", -1)));

            // 如果原区间末尾没有 trailing \n，split 会多出尾部 ""，需要处理
            if (!newIntervalText.endsWith("\n") && !newIntervalLines.isEmpty()
                    && newIntervalLines.get(newIntervalLines.size() - 1).isEmpty()) {
                newIntervalLines.remove(newIntervalLines.size() - 1);
            }

            // === 6. 组装新文件内容 ===
            List<String> newFileLines = new ArrayList<>();
            newFileLines.addAll(lines.subList(0, startLine + 1));      // 含起锚点行
            newFileLines.addAll(newIntervalLines);
            newFileLines.addAll(lines.subList(endLine, lines.size()));  // 含终锚点行及之后

            FileOperator.writeLinesAtomic(filePath, newFileLines);
            onFileModified(startLoc.projectPath, startLoc.filePath);

            int oldLineCount = oldString.split("\n", -1).length;
            int newLineCount = newString.isEmpty() ? 0 : newString.split("\n", -1).length;
            int delta = newLineCount - oldLineCount;

            return "✅ 已在 " + startLoc.filePath + " 的锚点区间 [" + startAnchor + " → " + effectiveEndAnchor + "] 内完成替换\n" +
                "   行数变化：" + oldLineCount + " 行 → " + newLineCount + " 行（净 " + (delta >= 0 ? "+" : "") + delta + " 行）";

        } catch (IOException e) {
            logger.error("editBetweenAnchors 失败", e);
            return "❌ 编辑失败: " + e.getMessage();
        }
    }

    // ===== 索引导航与脏文件 =====

    // @anchor: anchorManager_markDirty
    // 标记某文件为脏，待批量刷新索引
    private void markDirty(String projectPath, String fileRelPath) {
        dirtyFiles.computeIfAbsent(projectPath, k -> new LinkedHashSet<>()).add(fileRelPath);
    }

    // @anchor: anchorManager_onFileModified
    // 文件被改动后的统一处理：立即刷新锚点位置，标记为脏文件
    private void onFileModified(String projectPath, String fileRelPath) {
        anchorIndex.refreshAnchorsFile(projectPath, fileRelPath);
        markDirty(projectPath, fileRelPath);
    }

     // @anchor: anchorManager_flushDirty
    // 批量刷新所有脏文件。由 ToolExecutor 在一轮工具调用结束后触发。
    // 缺失索引的全量兜底用 tag=flush_rebuild 走带进度的路径
    public void flushDirty(Consumer<String> progress) {
        if (dirtyFiles.isEmpty()) return;
        Set<String> fullRebuiltProjects = new HashSet<>();
        for (Map.Entry<String, Set<String>> e : dirtyFiles.entrySet()) {
            String project = e.getKey();
            try {
                Path projectDir = PathUtils.safeResolve(project);
                Path anchorsFile = projectDir.resolve(AgentConfig.getAnchorIndexName());
                if (!Files.exists(anchorsFile)) {
                    if (fullRebuiltProjects.contains(project)) continue;
                    rebuildInternal(project, "flush_rebuild", progress);
                    fullRebuiltProjects.add(project);
                } else {
                    anchorIndex.refreshProjectIndexFiles(project, e.getValue());
                }
            } catch (Exception ex) {
                logger.warn("批量刷新脏文件失败: {} - {}", project, ex.getMessage());
           }
        }
        dirtyFiles.clear();
    }

    // @anchor: anchorManager_markDirtyByFilename
    // 从沙箱相对路径推导 (projectPath, fileRelPath)，标记为脏文件。
    public void markDirtyByFilename(String filename) {
        String[] parts = splitSandboxPath(filename);
        if (parts == null) return;
        String projectPath = parts[0];
        String fileRelPath = parts[1];
        try {
            Path projectDir = PathUtils.safeResolve(projectPath);
            if (!Files.isDirectory(projectDir)) return;
        } catch (IOException e) {
            logger.warn("标记脏文件失败（跳过）: {} - {}", projectPath, e.getMessage());
            return;
        }

        onFileModified(projectPath, fileRelPath);
    }

    // @anchor: anchorManager_splitSandboxPath
    // 把沙箱相对路径统一归一化为 (projectPath, fileRelPath)。
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

    // ===== 转发：查找锚点 =====

    // @anchor: anchorManager_findAnchor
    public AnchorLocation findAnchor(String projectPath, String anchorId) {
        return anchorQuery.find(projectPath, anchorId);
    }

    // @anchor: anchorManager_describeAnchors
    public String describeAnchors(String projectPath, String filePath) {
        if (projectPath == null || projectPath.isBlank()) return "❌ 缺少参数 project_path";
        return anchorFormatter.describe(projectPath, filePath);
    }

    // @anchor: anchorManager_describeByPath
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
    public boolean isProjectFresh(String projectPath) {
        return anchorIndex.isProjectFresh(projectPath);
    }

    // ===== 权限与辅助校验 =====

    // @anchor: anchorManager_checkWorkProjectPermission
    // 工作项目写权限校验。
    private String checkWorkProjectPermission(AnchorLocation loc) {
        if (loc == null || loc.projectPath == null || loc.projectPath.isBlank()) {
            return "❌ 无法解析锚点所属项目";
        }
        String project = loc.projectPath;
        if ("tmp".equals(project) || project.startsWith("tmp/")) {
            return null;
        }
        if (workProject == null) {
            return null;
        }
        if (!workProject.equals(project)) {
            return "❌ 只能修改工作项目 [" + workProject + "] 或 tmp/ 临时区，当前锚点属于: " + project;
        }
        return null;
    }

    // @anchor: anchorManager_isLastAnchorInFile
    // 判断给定锚点是否是其所在文件中的末位锚点（行号最大）。
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

    // ===== 归一化辅助方法 =====

    // @anchor: anchorManager_normalizeAnchorId
    // 剥离模型可能误传的 @anchor: 前缀、注释符、空格等
    private static String normalizeAnchorId(String raw) {
        if (raw == null) return null;
        String id = raw.trim();
        id = id.replaceAll("^//\\s*", "")
               .replaceAll("^/\\*\\s*", "")
               .replaceAll("^<!--\\s*", "")
               .replaceAll("^#\\s*", "")
               .replaceAll("\\s*\\*/$", "")
               .replaceAll("\\s*-->$", "");
        if (id.toLowerCase().startsWith("@anchor:")) {
            id = id.substring(8).trim();
        }
        return id;
    }

    // @anchor: anchorManager_normalizePosition
    // 兼容模型误传的 append/prepend，以及空值默认
    private static String normalizePosition(String raw) {
        if (raw == null || raw.isBlank()) return "after";
        String p = raw.trim().toLowerCase();
        if ("append".equals(p) || "after".equals(p)) return "after";
        if ("prepend".equals(p) || "before".equals(p)) return "before";
        return raw; // 交给后续逻辑报错
    }
}