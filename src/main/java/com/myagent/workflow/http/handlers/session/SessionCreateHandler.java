// @anchor: sessionCreateHandler_tot_desc
// 会话创建处理器：POST /session/create，显式创建一个空会话并绑定工作项目
package com.myagent.workflow.http.handlers.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.http.HttpServerMain;
import com.myagent.workflow.session.Session;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

// @anchor: sessionCreateHandler_class
// 会话创建处理器：必须指定工作项目，创建会话并返回元信息
public class SessionCreateHandler implements HttpHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    // @anchor: sessionCreateHandler_handle
    // 处理创建请求：解析 project 参数、校验合法性后创建会话
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // 解析 body
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String project = null;
        if (!body.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = mapper.readValue(body, Map.class);
                Object p = parsed.get("project");
                if (p instanceof String s) project = s.trim();
            } catch (Exception ignored) { /* 保持 null */ }
        }

        if (project == null || project.isBlank()) {
            writeJson(exchange, 400,
                    "{\"status\":\"error\",\"message\":\"必须指定工作项目\"}");
            return;
        }

        // 轻量校验（不做存在性检查，允许新项目在首次写入时创建）
        if (project.contains("..") || project.startsWith("/")
                || project.contains("\\") || project.contains(":")) {
            writeJson(exchange, 400,
                    "{\"status\":\"error\",\"message\":\"项目名不合法\"}");
            return;
        }

        Session session = HttpServerMain.getSessionManager()
                .create(HttpServerMain.getGlobalConfig(), project);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("sessionId", session.getSessionId());
        response.put("title", session.getMeta().title());
        response.put("createdAt", session.getMeta().createdAt());
        response.put("workProject", project);

        writeJson(exchange, 200, mapper.writeValueAsString(response));
    }

    // @anchor: sessionCreateHandler_writeJson
    // 发送 UTF-8 JSON 响应（含 CORS 头）
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