package com.myagent.workflow.http.handlers;

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

/**
 * POST /session/create
 * <p>
 * 请求体：可为空（使用全局配置）
 * 响应：{ "status":"ok", "sessionId":"xxx", "title":"新会话", "createdAt":"..." }
 * <p>
 * 用途：前端点击"新对话"时调用。
 * 说明：即使不调用此接口，直接 POST /run 也会自动创建新会话。
 *       此接口仅用于"我想先建一个空会话，稍后再用"的场景。
 */
public class SessionCreateHandler implements HttpHandler {

    private final ObjectMapper mapper = new ObjectMapper();

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

        Session session = HttpServerMain.getSessionManager()
                .create(HttpServerMain.getGlobalConfig());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("sessionId", session.getSessionId());
        response.put("title", session.getMeta().title());
        response.put("createdAt", session.getMeta().createdAt());

        writeJson(exchange, 200, mapper.writeValueAsString(response));
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