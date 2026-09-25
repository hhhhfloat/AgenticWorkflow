// @anchor: stopHandler_tot_desc
// 停止处理器：POST /stop，向指定会话发送停止信号
package com.myagent.workflow.http.handlers;

import com.fasterxml.jackson.databind.JsonNode;
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

// @anchor: stopHandler_class
// 停止处理器：根据会话状态返回 not_found/idle/stopped 并触发停止
/**
 * POST /stop
 * 请求体：{ "sessionId": "xxx" }
 * 停止指定会话的当前任务。
 */
public class StopHandler implements HttpHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    // @anchor: stopHandler_handle
    // 处理停止请求：定位会话并按运行状态决定是否停止任务
    @Override
    public void handle(HttpExchange exchange) throws IOException {
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
            writeJson(exchange, 400, error("请求格式错误"));
            return;
        }

        if (sessionId == null || sessionId.isEmpty()) {
            writeJson(exchange, 400, error("缺少 sessionId"));
            return;
        }

        Session session = HttpServerMain.getSessionManager().get(sessionId);
        Map<String, Object> response = new LinkedHashMap<>();

        if (session == null) {
            response.put("status", "not_found");
            response.put("message", "会话不存在");
        } else if (!session.isRunning()) {
            response.put("status", "idle");
            response.put("message", "该会话没有正在运行的任务");
        } else {
            session.stopTask();
            response.put("status", "stopped");
            response.put("message", "已发送停止信号");
        }

        writeJson(exchange, 200, mapper.writeValueAsString(response));
    }

    // @anchor: stopHandler_error
    // 组装标准错误响应 JSON
    private String error(String msg) throws IOException {
        return mapper.writeValueAsString(Map.of("status", "error", "message", msg));
    }

    // @anchor: stopHandler_writeJson
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
