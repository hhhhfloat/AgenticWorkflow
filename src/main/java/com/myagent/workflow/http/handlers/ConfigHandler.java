// @anchor: configHandler_tot_desc
// 配置处理器：GET /config，向前端返回默认配置项
package com.myagent.workflow.http.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.AgentConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

// @anchor: configHandler_class
// 配置处理器：输出最大迭代次数等运行参数供前端展示
public class ConfigHandler implements HttpHandler {
    // @anchor: configHandler_handle
    // 处理配置查询：组装并返回配置 JSON
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // 构建配置 JSON
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("maxIterations", AgentConfig.getDefaultMaxIterations());

        String json = new ObjectMapper().writeValueAsString(config);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, json.getBytes().length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(json.getBytes());
        }
    }
}
