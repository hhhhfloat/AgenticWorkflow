// @anchor: handlerUtils_tot_desc
// HTTP handler 公共工具：查询串解析与 JSON 响应封装
package com.myagent.workflow.http.utils;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

// @anchor: handlerUtils_class
// 处理器工具类：为各 handler 提供统一的请求解析与响应发送
public final class HandlerUtils {

    private HandlerUtils() {}

    // @anchor: handlerUtils_parseQuery
    // 解析 URL 查询字符串为键值 Map
    /**
     * 解析查询字符串为 Map
     */
    public static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                params.put(kv[0], kv[1]);
            }
        }
        return params;
    }

    // @anchor: handlerUtils_sendResponse
    // 以 UTF-8 JSON 形式发送响应（含 CORS 与内容类型头）
    /**
     * 发送 JSON 响应
     */
    public static void sendResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
