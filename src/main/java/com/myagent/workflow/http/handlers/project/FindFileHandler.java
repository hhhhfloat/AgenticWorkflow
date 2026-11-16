// @anchor: findFileHandler_intro
// GET /find-file?project=X 返回项目所有源文件的相对路径列表（跳过构建产物）
package com.myagent.workflow.http.handlers.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myagent.workflow.http.utils.HandlerUtils;
import com.myagent.workflow.tools.PathUtils;
import com.myagent.workflow.tools.ProjectLayout;
import com.myagent.workflow.tools.search.SearchFileFilter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

// @anchor: findFileHandler_class
public class FindFileHandler implements HttpHandler {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        Map<String, String> params = HandlerUtils.parseQuery(exchange.getRequestURI().getQuery());
        String project = params.get("project");
        if (project == null || project.isBlank()) {
            HandlerUtils.sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"缺少 project 参数\"}");
            return;
        }

        try {
            Path projectDir = PathUtils.safeResolve(project);
            if (!Files.isDirectory(projectDir)) {
                HandlerUtils.sendResponse(exchange, 200, "{\"files\":[]}");
                return;
            }

            ObjectNode result = mapper.createObjectNode();
            ArrayNode files = mapper.createArrayNode();

            // 遍历项目目录（剪枝排除 target / node_modules / .git 等）
            Files.walkFileTree(projectDir, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (!dir.equals(projectDir)) {
                        String name = dir.getFileName().toString();
                        if (SearchFileFilter.DEFAULT_EXCLUDED_DIRS.contains(name)
                                || ProjectLayout.isHiddenBasename(name)) {
                            return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        String name = file.getFileName().toString();
                        if (!ProjectLayout.isExcludedFile(name) && !ProjectLayout.isHiddenBasename(name)) {
                            String rel = projectDir.relativize(file).toString().replace('\\', '/');
                            files.add(rel);
                        }
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });

            result.set("files", files);
            HandlerUtils.sendResponse(exchange, 200, mapper.writeValueAsString(result));
        } catch (Exception e) {
            HandlerUtils.sendResponse(exchange, 500,
                    "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }
}