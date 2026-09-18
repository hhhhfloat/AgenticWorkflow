package com.myagent.workflow.http.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.http.utils.HandlerUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * GET /project-meta?path=sandbox/xxx
 * <p>
 * 读取项目目录下的 .agent_entry.json，返回其中的入口文件与编译模式。
 * 用于前端"运行项目"按钮自动填充参数。
 * <p>
 * 安全：path 必须以 sandbox/ 或 TestProjects/ 开头，且不得包含 ..
 */
public class ProjectMetaHandler implements HttpHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = HandlerUtils.parseQuery(query);
        String path = params.get("path");

        if (path == null || path.isEmpty()) {
            writeJson(exchange, 400, "{\"error\":\"缺少 path 参数\"}");
            return;
        }

        // 安全检查
        if (!path.startsWith("sandbox/") && !path.startsWith("TestProjects/")) {
            writeJson(exchange, 403, "{\"error\":\"路径非法\"}");
            return;
        }
        if (path.contains("..")) {
            writeJson(exchange, 403, "{\"error\":\"路径非法\"}");
            return;
        }

        Path projectDir = Paths.get(path).normalize();
        Path metaFile = projectDir.resolve(".agent_entry.json");

        if (Files.exists(metaFile) && Files.isRegularFile(metaFile)) {
            try {
                String content = Files.readString(metaFile, StandardCharsets.UTF_8);
                // 验证 JSON 有效性
                mapper.readTree(content);
                writeJson(exchange, 200, content);
            } catch (Exception e) {
                writeJson(exchange, 500, "{\"error\":\"读取注册表失败: " + e.getMessage() + "\"}");
            }
        } else {
            writeJson(exchange, 200, "{\"exists\":false}");
        }
    }

    private void writeJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}