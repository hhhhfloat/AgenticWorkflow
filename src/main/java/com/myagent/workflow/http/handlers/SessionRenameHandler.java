// @anchor: sessionRenameHandler_tot_desc
// 会话重命名处理器：POST /session/rename，更新会话标题并落盘
package com.myagent.workflow.http.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.http.HttpServerMain;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

// @anchor: sessionRenameHandler_class
// 会话重命名处理器：接收 {sessionId, title}，更新后返回 ok
/**
 * POST /session/rename
 * <p>
 * 请求体：{ "sessionId": "xxx", "title": "新标题" }
 * 响应：{ "status":"ok", "sessionId":"xxx", "title":"新标题" }
 * <p>
 * 语义：
 * - 会话不在内存时先从磁盘加载
 * - 只改 title，不改变 lastActiveAt（重命名不算活跃）
 * - 立即落盘
 */
public class SessionRenameHandler implements HttpHandler {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_TITLE_LENGTH = 100;

    // @anchor: sessionRenameHandler_handle
    // 处理重命名请求：校验参数后委托 SessionManager.rename
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

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String sessionId;
        String title;
        try {
            JsonNode root = mapper.readTree(body);
            sessionId = root.path("sessionId").asText(null);
            title = root.path("title").asText(null);
        } catch (Exception e) {
            writeJson(exchange, 400, error("请求格式错误"));
            return;
        }

        if (sessionId == null || sessionId.isEmpty()) {
            writeJson(exchange, 400, error("缺少 sessionId"));
            return;
        }
        if (title == null || title.isBlank()) {
            writeJson(exchange, 400, error("标题不能为空"));
            return;
        }
        title = title.trim();
        if (title.length() > MAX_TITLE_LENGTH) {
            writeJson(exchange, 400, error("标题过长（≤" + MAX_TITLE_LENGTH + " 字）"));
            return;
        }

        boolean ok = HttpServerMain.getSessionManager()
                .rename(sessionId, title, HttpServerMain.getGlobalConfig());
        if (!ok) {
            writeJson(exchange, 404, error("会话不存在: " + sessionId));
            return;
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("sessionId", sessionId);
        response.put("title", title);

        writeJson(exchange, 200, mapper.writeValueAsString(response));
    }

    // @anchor: sessionRenameHandler_error
    // 组装标准错误响应 JSON
    private String error(String msg) throws IOException {
        return mapper.writeValueAsString(Map.of("status", "error", "message", msg));
    }

    // @anchor: sessionRenameHandler_writeJson
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