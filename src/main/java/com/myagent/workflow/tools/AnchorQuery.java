package com.myagent.workflow.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.AgentConfig;
import com.myagent.workflow.model.AnchorLocation;
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

    AnchorQuery(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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

    // @anchor: anchorQuery_findGlobally
    AnchorLocation findGlobally(String anchorId) {
        try {
            Path sandboxRoot = Paths.get(AgentConfig.getSandboxDir()).toAbsolutePath().normalize();
            try (var stream = Files.list(sandboxRoot)) {
                for (Path projectDir : (Iterable<Path>) stream::iterator) {
                    if (!Files.isDirectory(projectDir)) continue;
                    String projectName = projectDir.getFileName().toString();
                    if (projectName.startsWith(".")) continue;
                    AnchorLocation loc = find(projectName, anchorId);
                    if (loc != null) return loc;
                }
            }
            return null;
        } catch (IOException e) {
            logger.error("全局查找锚点失败", e);
            return null;
        }
    }

    // @anchor: anchorQuery_findAllInProject
    List<AnchorLocation> findAllInProject(String projectPath, String anchorId) {
        List<AnchorLocation> results = new ArrayList<>();
        try {
            Path indexFile = getIndexPath(projectPath);
            if (indexFile == null || !Files.exists(indexFile)) return results;

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
    List<AnchorLocation> findAllGlobally(String anchorId) {
        List<AnchorLocation> results = new ArrayList<>();
        try {
            Path sandboxRoot = Paths.get(AgentConfig.getSandboxDir()).toAbsolutePath().normalize();
            try (var stream = Files.list(sandboxRoot)) {
                for (Path projectDir : (Iterable<Path>) stream::iterator) {
                    if (!Files.isDirectory(projectDir)) continue;
                    String projectName = projectDir.getFileName().toString();
                    if (projectName.startsWith(".")) continue;
                    results.addAll(findAllInProject(projectName, anchorId));
                }
            }
        } catch (IOException e) {
            logger.error("全局查找锚点失败", e);
        }
        return results;
    }

    // @anchor: anchorQuery_findInFile
    AnchorLocation findInFile(String anchorId, String fileHint) {
        try {
            Path sandboxRoot = Paths.get(AgentConfig.getSandboxDir()).toAbsolutePath().normalize();
            AnchorLocation found = null;
            int matchCount = 0;

            try (var stream = Files.list(sandboxRoot)) {
                for (Path projectDir : (Iterable<Path>) stream::iterator) {
                    if (!Files.isDirectory(projectDir)) continue;
                    String projectName = projectDir.getFileName().toString();
                    if (projectName.startsWith(".")) continue;

                    Path indexFile = projectDir.resolve(AgentConfig.getAnchorIndexName());
                    if (!Files.exists(indexFile)) continue;

                    String content = Files.readString(indexFile);
                    Map<String, List<Map<String, Object>>> projectAnchors =
                            objectMapper.readValue(content, new TypeReference<>() {});

                    for (Map.Entry<String, List<Map<String, Object>>> fileEntry : projectAnchors.entrySet()) {
                        String filePath = fileEntry.getKey();
                        String fullRel = projectName + "/" + filePath;
                        boolean matches = fullRel.equals(fileHint)
                                || filePath.equals(fileHint)
                                || fullRel.endsWith("/" + fileHint);
                        if (!matches) continue;

                        for (Map<String, Object> anchor : fileEntry.getValue()) {
                            String id = (String) anchor.get("id");
                            if (anchorId.equals(id)) {
                                AnchorLocation loc = new AnchorLocation();
                                loc.projectPath = projectName;
                                loc.filePath = filePath;
                                loc.line = (int) anchor.get("line");
                                loc.id = id;
                                loc.preview = (String) anchor.get("preview");
                                found = loc;
                                matchCount++;
                            }
                        }
                    }
                }
            }
            return matchCount == 1 ? found : null;
        } catch (IOException e) {
            logger.error("按文件查找锚点失败", e);
            return null;
        }
    }
}