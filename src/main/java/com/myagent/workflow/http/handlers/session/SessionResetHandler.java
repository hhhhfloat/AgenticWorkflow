// @anchor: sessionResetHandler_tot_desc
// 会话重置处理器：POST /session/reset，强制将会话状态归一化为 IDLE
package com.myagent.workflow.http.handlers.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.http.HttpServerMain;
import com.myagent.workflow.session.Session;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

// @anchor: sessionResetHandler_class
// 强制会话退出 RUNNING 状态：用于清理卡死会话，不释放全局 Semaphore
public class SessionResetHandler implements HttpHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    // @anchor: sessionResetHandler_handle
    // 解析 sessionId，调用 forceIdle 并返回结果
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
        try {
            JsonNode root = mapper.readTree(body);
            sessionId = root.path("sessionId").asText(null);
        } catch (Exception e) {
            writeJson(exchange, 400, "{\"status\":\"error\",\"message\":\"请求格式错误\"}");
            return;
        }
        if (sessionId == null || sessionId.isEmpty()) {
            writeJson(exchange, 400, "{\"status\":\"error\",\"message\":\"缺少 sessionId\"}");
            return;
        }

        Session session = HttpServerMain.getSessionManager().get(sessionId);
        if (session == null) {
            writeJson(exchange, 404, "{\"status\":\"error\",\"message\":\"会话不在内存中\"}");
            return;
        }

        session.forceIdle();
        writeJson(exchange, 200, "{\"status\":\"ok\",\"message\":\"会话已重置为 IDLE\"}");
    }

    private void writeJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}