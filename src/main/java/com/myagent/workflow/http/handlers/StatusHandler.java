// @anchor: statusHandler_tot_desc
// 状态处理器：GET /status，返回全局是否有任务在运行及所在会话
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

// @anchor: statusHandler_class
// 状态处理器：汇总会话运行状态供前端轮询展示
/**
 * GET /status
 * 返回全局运行状态：是否有任务在跑、在哪个会话。
 */
public class StatusHandler implements HttpHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    // @anchor: statusHandler_handle
    // 处理状态请求：查找正在运行的会话并返回其 ID 与标题
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        Session running = null;
        for (Session s : HttpServerMain.getSessionManager().listActive()) {
            if (s.isRunning()) {
                running = s;
                break;
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("running", running != null);
        if (running != null) {
            response.put("sessionId", running.getSessionId());
            response.put("title", running.getMeta().title());
        }

        writeJson(exchange, 200, mapper.writeValueAsString(response));
    }

    // @anchor: statusHandler_writeJson
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
