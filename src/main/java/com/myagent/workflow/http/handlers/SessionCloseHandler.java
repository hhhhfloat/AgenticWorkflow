// @anchor: sessionCloseHandler_tot_desc
// 会话关闭处理器：POST /session/close，停止并归档会话后从内存卸载
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

// @anchor: sessionCloseHandler_class
// 会话关闭处理器：先停任务再落盘，最后从内存移除（历史仍在磁盘）
/**
 * POST /session/close
 * <p>
 * 请求体：{ "sessionId": "xxx" }
 * 响应：{ "status":"ok", "message":"会话已归档" }
 * <p>
 * 语义：
 * - 如果会话正在运行，先 stopTask() 并等待退出
 * - 落盘到 ./sessions/{sessionId}/
 * - 从内存中移除（下次切换回来时从磁盘加载）
 * <p>
 * 说明：这不是"删除"，只是"从内存卸载"。会话历史仍在磁盘上。
 */
public class SessionCloseHandler implements HttpHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    // @anchor: sessionCloseHandler_handle
    // 处理关闭请求：校验 sessionId 后委托 SessionManager 关闭会话
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
            writeJson(exchange, 400, error("请求格式错误"));
            return;
        }

        if (sessionId == null || sessionId.isEmpty()) {
            writeJson(exchange, 400, error("缺少 sessionId"));
            return;
        }

        HttpServerMain.getSessionManager().close(sessionId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("message", "会话已归档");

        writeJson(exchange, 200, mapper.writeValueAsString(response));
    }

    // @anchor: sessionCloseHandler_error
    // 组装标准错误响应 JSON
    private String error(String msg) throws IOException {
        return mapper.writeValueAsString(Map.of("status", "error", "message", msg));
    }

    // @anchor: sessionCloseHandler_writeJson
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
