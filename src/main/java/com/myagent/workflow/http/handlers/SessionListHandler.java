package com.myagent.workflow.http.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.http.HttpServerMain;
import com.myagent.workflow.session.SessionMeta;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /session/list
 * <p>
 * 响应：
 * {
 *   "sessions": [
 *     { "sessionId":"xxx", "title":"...", "createdAt":"...", "lastActiveAt":"...",
 *       "messageCount":12, "state":"IDLE" },
 *     ...
 *   ]
 * }
 * <p>
 * 说明：
 * - 返回磁盘上所有会话的元数据（含内存中的活跃会话）
 * - 按 lastActiveAt 倒序，最近使用的排最前
 * - 前端据此渲染侧边栏的会话列表
 */
public class SessionListHandler implements HttpHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        List<SessionMeta> metas = HttpServerMain.getSessionManager().listAll();

        List<Map<String, Object>> sessions = new ArrayList<>();
        for (SessionMeta meta : metas) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sessionId", meta.sessionId());
            item.put("title", meta.title());
            item.put("createdAt", meta.createdAt());
            item.put("lastActiveAt", meta.lastActiveAt());
            item.put("messageCount", meta.messageCount());
            item.put("state", meta.state().name());
            sessions.add(item);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessions", sessions);

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