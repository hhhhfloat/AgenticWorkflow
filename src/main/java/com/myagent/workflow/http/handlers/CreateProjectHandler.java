package com.myagent.workflow.http.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import static com.myagent.workflow.http.utils.HandlerUtils.sendResponse;

public class CreateProjectHandler implements HttpHandler {
    private static final Pattern SAFE_NAME = Pattern.compile("^(?!.*\\.\\.)[^\\\\/:*?\"<>|]+$");

    @Override
    public void handle(HttpExchange exchange) throws IOException {
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

        // 校验项目名
        if (projectName == null || projectName.trim().isEmpty()) {
            sendResponse(exchange, 400, "{\"status\":\"error\", \"message\":\"项目名不能为空\"}");
            return;
        }
        projectName = projectName.trim();
        if (!SAFE_NAME.matcher(projectName).matches()) {
            sendResponse(exchange, 400, "{\"status\":\"error\", \"message\":\"项目名不合法，仅允许字母、数字、- 和 _\"}");
            return;
        }

        // 目标路径：sandbox/{projectName}
        Path projectDir = Paths.get("./sandbox", projectName).normalize();
        Path sandboxRoot = Paths.get("./sandbox").normalize();
        if (!projectDir.startsWith(sandboxRoot)) {
            sendResponse(exchange, 403, "{\"status\":\"error\", \"message\":\"路径非法\"}");
            return;
        }

        // 检查是否已存在
        if (Files.exists(projectDir)) {
            sendResponse(exchange, 409, "{\"status\":\"exists\", \"message\":\"项目已存在: " + projectName + "\"}");
            return;
        }

        // 创建目录
        try {
            Files.createDirectories(projectDir);
            sendResponse(exchange, 200, "{\"status\":\"success\", \"path\":\"sandbox/" + projectName + "\"}");
        } catch (IOException e) {
            sendResponse(exchange, 500, "{\"status\":\"error\", \"message\":\"创建失败: " + e.getMessage() + "\"}");
        }
    }

}

