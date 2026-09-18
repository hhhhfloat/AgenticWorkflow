package com.myagent.workflow.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.model.AnchorLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// @anchor: anchorManager_class
/**
 * 锚点管理器 —— 面向 Agent 的工具入口。
 * <p>
 * 委托 AnchorIndex 处理索引的构建与查询；
 * 自己负责文件内容级别的操作（插入、删除、读取）。
 */
public class AnchorManager {
    private static final Logger logger = LoggerFactory.getLogger(AnchorManager.class);
    private final AnchorIndex anchorIndex;

    // @anchor: anchorManager_constructor
    public AnchorManager(ObjectMapper objectMapper) {
        this.anchorIndex = new AnchorIndex(objectMapper);
    }

    // ===== 转发：索引构建 =====

    // @anchor: anchorManager_buildIndex
    String buildAnchorIndex(String projectPath) {
        return anchorIndex.rebuild(projectPath);
    }

    // ===== 转发：锚点列表 =====

    // @anchor: anchorManager_listAnchors
    String listAnchors(String projectPath) {
        return anchorIndex.list(projectPath, null);
    }

    // @anchor: anchorManager_listAnchorsByFile
    String listAnchors(String projectPath, String filePath) {
        return anchorIndex.list(projectPath, filePath);
    }

    // ===== 文件内容操作 =====

    // @anchor: anchorManager_insertAtAnchor
    String insertAtAnchor(String anchorId, String content, String position) {
        AnchorLocation loc = anchorIndex.findGlobally(anchorId);
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
            anchorIndex.rebuild(loc.projectPath);
            return "✅ 已在 " + loc.filePath + " 的锚点 [" + anchorId + "] " + position + " 插入代码";

        } catch (IOException e) {
            logger.error("插入代码失败", e);
            return "❌ 插入失败: " + e.getMessage();
        }
    }

    // @anchor: anchorManager_deleteBetweenAnchors
    String deleteBetweenAnchors(String startAnchor, String endAnchor) {
        AnchorLocation startLoc = anchorIndex.findGlobally(startAnchor);
        AnchorLocation endLoc = anchorIndex.findGlobally(endAnchor);
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
            anchorIndex.rebuild(startLoc.projectPath);

            int deletedLines = (endLine - startLine) - 1;
            return "✅ 已删除从 [" + startAnchor + "] 到 [" + endAnchor + "] 之间的 " + deletedLines + " 行代码";

        } catch (IOException e) {
            logger.error("删除代码块失败", e);
            return "❌ 删除失败: " + e.getMessage();
        }
    }

    // @anchor: anchorManager_readBetweenAnchors
    String readBetweenAnchors(String startAnchor, String endAnchor) {
        AnchorLocation startLoc = anchorIndex.findGlobally(startAnchor);
        AnchorLocation endLoc = anchorIndex.findGlobally(endAnchor);
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

    // ===== 转发：查找锚点 =====

    // @anchor: anchorManager_findAnchor
    /**
     * 在指定项目中查找锚点。
     * <p>
     * 由 CallGraphAnalyzer 等模块通过 AnchorManager 调用，避免它们直接依赖 AnchorIndex。
     */
    AnchorLocation findAnchor(String projectPath, String anchorId) {
        return anchorIndex.find(projectPath, anchorId);
    }
}