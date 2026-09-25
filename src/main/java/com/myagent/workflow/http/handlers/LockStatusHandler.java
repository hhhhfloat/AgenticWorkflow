// @anchor: lockStatusHandler_tot_desc
// 锁状态查询：GET /lock-status，返回当前是否被手机独占
package com.myagent.workflow.http.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.http.HttpServerMain;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

// @anchor: lockStatusHandler_class
// 供前端页面加载时检查当前锁定状态
public class LockStatusHandler implements HttpHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    // @anchor: lockStatusHandler_handle
    // 返回 { "mobileLocked": true/false }
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("mobileLocked", HttpServerMain.isMobileLocked());

        String ua = exchange.getRequestHeaders().getFirst("User-Agent");
        boolean isMobile = ua != null && (
                ua.toLowerCase().contains("mobile")
                        || ua.toLowerCase().contains("android")
                        || ua.toLowerCase().contains("iphone")
                        || ua.toLowerCase().contains("ipad"));
        response.put("youAreMobile", isMobile);

        byte[] bytes = mapper.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}