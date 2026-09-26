// @anchor: sessionUsageHandler_tot_desc
// GET /session/usage?sessionId=xxx，返回会话级累计用量
package com.myagent.workflow.http.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.http.HttpServerMain;
import com.myagent.workflow.http.utils.HandlerUtils;
import com.myagent.workflow.session.SessionUsage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

// @anchor: sessionUsageHandler_class
// 会话用量查询：前端页面加载和切换会话时拉取
public class SessionUsageHandler implements HttpHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    // @anchor: sessionUsageHandler_handle
    // 解析 sessionId，返回累计用量（无记录时返回全 0）
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        Map<String, String> params = HandlerUtils.parseQuery(exchange.getRequestURI().getQuery());
        String sessionId = params.get("sessionId");
        if (sessionId == null || sessionId.isEmpty()) {
            writeJson(exchange, 400,
                    mapper.writeValueAsString(Map.of("status", "error", "message", "缺少 sessionId")));
            return;
        }

        SessionUsage u = HttpServerMain.getSessionManager().getUsage(sessionId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionId", sessionId);
        response.put("promptTokens", u.promptTokens());
        response.put("cachedTokens", u.cachedTokens());
        response.put("completionTokens", u.completionTokens());
        response.put("apiCalls", u.apiCalls());
        response.put("cost", u.cost());

        writeJson(exchange, 200, mapper.writeValueAsString(response));
    }

    // @anchor: sessionUsageHandler_writeJson
    // 发送 UTF-8 JSON 响应
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