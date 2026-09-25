// @anchor: archiveHandler_tot_desc
// 归档处理器：POST /archive，把 sandbox 项目复制到 TestProjects 版本目录
package com.myagent.workflow.http.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.AgentConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

// @anchor: archiveHandler_class
// 项目归档处理器：校验项目名与目标存在性后执行目录递归复制
public class ArchiveHandler implements HttpHandler {
    // @anchor: archiveHandler_handle
    // 处理归档请求：校验参数、检测目标冲突并按 force 决定是否覆盖
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // 只接受 POST
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // 解析请求体
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root;
        String projectName;
        try {
            root = mapper.readTree(body);
            projectName = root.get("projectName").asText();
        } catch (Exception e) {
            sendResponse(exchange, 400, "{\"status\":\"error\", \"message\":\"请求格式错误: " + e.getMessage() + "\"}");
            return;
        }

        // 安全检查：防止路径穿越
        if (projectName == null || projectName.trim().isEmpty() || projectName.contains("..") || projectName.contains("/") || projectName.contains("\\")) {
            sendResponse(exchange, 400, "{\"status\":\"error\", \"message\":\"项目名不合法\"}");
            return;
        }

        // 源路径：sandbox/{projectName}
        Path src = Paths.get("./sandbox", projectName);
        if (!Files.exists(src) || !Files.isDirectory(src)) {
            sendResponse(exchange, 404, "{\"status\":\"error\", \"message\":\"项目不存在: " + projectName + "\"}");
            return;
        }

        // 目标路径：TestProjects/目前版本/{projectName}
        Path destDir = Paths.get("./TestProjects", AgentConfig.getArchiveVersion());
        if (!Files.exists(destDir)) {
            Files.createDirectories(destDir);
        }
        Path dest = destDir.resolve(projectName);

        // 检查是否已存在
        boolean force = false;
        String query = exchange.getRequestURI().getQuery();
        if (query != null && query.contains("force=true")) {
            force = true;
        }

        if (Files.exists(dest) && !force) {
            sendResponse(exchange, 200, "{\"status\":\"exists\", \"message\":\"目标已存在，是否覆盖？\"}");
            return;
        }

        // 执行复制
        try {
            // 如果目标存在且 force=true，先删除
            if (Files.exists(dest)) {
                deleteDirectory(dest);
            }
            // 递归复制
            copyDirectory(src, dest);
            sendResponse(exchange, 200, "{\"status\":\"success\", \"path\":\"TestProjects/" + AgentConfig.getArchiveVersion() + "/" + projectName + "\"}");
        } catch (IOException e) {
            sendResponse(exchange, 500, "{\"status\":\"error\", \"message\":\"复制失败: " + e.getMessage() + "\"}");
        }
    }

    // @anchor: archiveHandler_copyDirectory
    // 递归复制目录：逐项创建子目录或复制文件（覆盖同名）
    /**
     * 递归复制目录
     */
    private void copyDirectory(Path src, Path dest) throws IOException {
        Files.walk(src).forEach(source -> {
            try {
                Path target = dest.resolve(src.relativize(source));
                if (Files.isDirectory(source)) {
                    if (!Files.exists(target)) {
                        Files.createDirectories(target);
                    }
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // @anchor: archiveHandler_deleteDirectory
    // 递归删除目录：按深度倒序删除以避免父目录先于子项
    /**
     * 递归删除目录
     */
    private void deleteDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            Files.walk(dir)
                    .sorted((a, b) -> b.compareTo(a)) // 先删除子文件
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }

    // @anchor: archiveHandler_sendResponse
    // 发送 UTF-8 JSON 响应（含 CORS 头）
    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}

