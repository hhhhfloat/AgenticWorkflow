// @anchor: anchorQuery_intro
// 锚点查询：按 id、文件或全局定位锚点位置
package com.myagent.workflow.tools.anchor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.config.AgentConfig;
import com.myagent.workflow.model.AnchorLocation;
import com.myagent.workflow.tools.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

// @anchor: anchorQuery_class
// 锚点查询：在项目内/跨项目查找锚点位置
class AnchorQuery {
    private static final Logger logger = LoggerFactory.getLogger(AnchorQuery.class);
    private final ObjectMapper objectMapper;
    private final AnchorIndexCache cache;

    AnchorQuery(ObjectMapper objectMapper, AnchorIndexCache cache) {
        this.objectMapper = objectMapper;
        this.cache = cache;
    }

    private Path getIndexPath(String projectPath) {
        try {
            Path projectDir = PathUtils.safeResolve(projectPath);
            return projectDir.resolve(AgentConfig.getAnchorIndexName());
        } catch (IOException e) {
            return null;
        }
    }

    // @anchor: anchorQuery_find
    AnchorLocation find(String projectPath, String anchorId) {
        try {
            Path indexFile = getIndexPath(projectPath);
            if (indexFile == null || !Files.exists(indexFile)) return null;

            Map<String, List<Map<String, Object>>> projectAnchors = cache.load(indexFile);
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

    // @anchor: anchorQuery_findAllInProject
    List<AnchorLocation> findAllInProject(String projectPath, String anchorId) {
        List<AnchorLocation> results = new ArrayList<>();
        try {
            Path indexFile = getIndexPath(projectPath);
            if (indexFile == null || !Files.exists(indexFile)) return results;

            Map<String, List<Map<String, Object>>> projectAnchors = cache.load(indexFile);
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
                        results.add(loc);
                    }
                }
            }
        } catch (IOException e) {
            logger.error("查找锚点失败", e);
        }
        return results;
    }

    // @anchor: anchorQuery_findAllGlobally
    List<AnchorLocation> findAllGlobally(String anchorId, String workProject) {
        List<AnchorLocation> results = new ArrayList<>();
        try {
            Path sandboxRoot = Paths.get(AgentConfig.getSandboxDir()).toAbsolutePath().normalize();
            try (var stream = Files.list(sandboxRoot)) {
                for (Path projectDir : (Iterable<Path>) stream::iterator) {
                    if (!Files.isDirectory(projectDir)) continue;
                    String projectName = projectDir.getFileName().toString();
                    if (projectName.startsWith(".")) continue;
                    if (workProject != null && !workProject.equals(projectName)) continue;
                    results.addAll(findAllInProject(projectName, anchorId));
                }
            }
        } catch (IOException e) {
            logger.error("全局查找锚点失败", e);
        }
        return results;
    }

    // @anchor: anchorQuery_findAllInFile
    // 按文件提示查找锚点的全部匹配位置。返回 0 个（未找到）、1 个（唯一）、多个（歧义）。
    // fileHint 匹配口径：宽松（fullRel 精确 / filePath 精确 / 路径后缀），
    // 并做 "./" 与反斜杠归一化（与 AnchorFormatter.normalizeHint 对齐）。
    List<AnchorLocation> findAllInFile(String anchorId, String fileHint, String workProject) {
        List<AnchorLocation> matches = new ArrayList<>();
        if (fileHint == null || fileHint.isBlank()) return matches;

        // 归一化：反斜杠统一为 /，去掉开头的 ./
        String hint = fileHint.replace('\\', '/');
        while (hint.startsWith("./")) hint = hint.substring(2);
        if (hint.isBlank()) return matches;

        try {
            Path sandboxRoot = Paths.get(AgentConfig.getSandboxDir()).toAbsolutePath().normalize();
            try (var stream = Files.list(sandboxRoot)) {
                for (Path projectDir : (Iterable<Path>) stream::iterator) {
                    if (!Files.isDirectory(projectDir)) continue;
                    String projectName = projectDir.getFileName().toString();
                    if (projectName.startsWith(".")) continue;
                    if (workProject != null && !workProject.equals(projectName)) continue;

                    Path indexFile = projectDir.resolve(AgentConfig.getAnchorIndexName());
                    if (!Files.exists(indexFile)) continue;
                    try {
                        Map<String, List<Map<String, Object>>> projectAnchors = cache.load(indexFile);
                        for (Map.Entry<String, List<Map<String, Object>>> fileEntry : projectAnchors.entrySet()) {
                            String filePath = fileEntry.getKey();
                            String fullRel = projectName + "/" + filePath;
                            // 宽松匹配：完整相对路径 / 纯文件相对路径 / 路径后缀
                            boolean fileMatches = fullRel.equals(hint)
                                    || filePath.equals(hint)
                                    || fullRel.endsWith("/" + hint);
                            if (!fileMatches) continue;

                            for (Map<String, Object> anchor : fileEntry.getValue()) {
                                String id = (String) anchor.get("id");
                                if (anchorId.equals(id)) {
                                    AnchorLocation loc = new AnchorLocation();
                                    loc.projectPath = projectName;
                                    loc.filePath = filePath;
                                    loc.line = (int) anchor.get("line");
                                    loc.id = id;
                                    loc.preview = (String) anchor.get("preview");
                                    matches.add(loc);
                                }
                            }
                        }
                    } catch (IOException e) {
                        logger.warn("读取 {} 的索引失败，跳过: {}", projectName, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            logger.error("按文件查找锚点失败", e);
        }
        return matches;
    }

    // @anchor: anchorQuery_findInFile
    // 保留原语义：唯一命中返回位置，否则 null。
    // 多命中时打 warn（服务端可见），调用方需要区分零/多命中时用 findAllInFile。
    AnchorLocation findInFile(String anchorId, String fileHint, String workProject) {
        List<AnchorLocation> all = findAllInFile(anchorId, fileHint, workProject);
        if (all.size() > 1) {
            logger.warn("锚点 {} 在 fileHint={} 下匹配到 {} 个位置，需更精确的路径",
                    anchorId, fileHint, all.size());
        }
        return all.size() == 1 ? all.get(0) : null;
    }

    // @anchor: anchorQuery_listAnchorsInFile
    // 列出指定文件中的全部锚点（用于"锚点 ID 猜错"时给出相似建议）。
    // fileHint 匹配口径与 findAllInFile 一致（宽松 + ./ 归一化）。
    List<AnchorLocation> listAnchorsInFile(String fileHint, String workProject) {
        List<AnchorLocation> result = new ArrayList<>();
        if (fileHint == null || fileHint.isBlank()) return result;

        String hint = fileHint.replace('\\', '/');
        while (hint.startsWith("./")) hint = hint.substring(2);
        if (hint.isBlank()) return result;

        try {
            Path sandboxRoot = Paths.get(AgentConfig.getSandboxDir()).toAbsolutePath().normalize();
            try (var stream = Files.list(sandboxRoot)) {
                for (Path projectDir : (Iterable<Path>) stream::iterator) {
                    if (!Files.isDirectory(projectDir)) continue;
                    String projectName = projectDir.getFileName().toString();
                    if (projectName.startsWith(".")) continue;
                    if (workProject != null && !workProject.equals(projectName)) continue;

                    Path indexFile = projectDir.resolve(AgentConfig.getAnchorIndexName());
                    if (!Files.exists(indexFile)) continue;
                    try {
                        Map<String, List<Map<String, Object>>> projectAnchors = cache.load(indexFile);
                        for (Map.Entry<String, List<Map<String, Object>>> fileEntry : projectAnchors.entrySet()) {
                            String filePath = fileEntry.getKey();
                            String fullRel = projectName + "/" + filePath;
                            boolean fileMatches = fullRel.equals(hint)
                                    || filePath.equals(hint)
                                    || fullRel.endsWith("/" + hint);
                            if (!fileMatches) continue;

                            for (Map<String, Object> anchor : fileEntry.getValue()) {
                                AnchorLocation loc = new AnchorLocation();
                                loc.projectPath = projectName;
                                loc.filePath = filePath;
                                loc.line = (int) anchor.get("line");
                                loc.id = (String) anchor.get("id");
                                loc.preview = (String) anchor.get("preview");
                                result.add(loc);
                            }
                        }
                    } catch (IOException e) {
                        logger.warn("读取 {} 的索引失败，跳过: {}", projectName, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            logger.error("列文件锚点失败", e);
        }
        return result;
    }
}