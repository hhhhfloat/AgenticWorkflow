// @anchor: toolHandler_intro
// 前端工具调用处理器：POST /tool，白名单内的工具可被前端按钮触发
package com.myagent.workflow.http.handlers.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.myagent.workflow.core.config.AgentConfig;
import com.myagent.workflow.http.HttpServerMain;
import com.myagent.workflow.http.utils.HandlerUtils;
import com.myagent.workflow.tools.ToolExecutor;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

// @anchor: toolHandler_class
// 前端工具调用处理器：仅允许白名单工具，参数 JSON 传入，结果 JSON 返回
public class ToolHandler implements HttpHandler {

    // @anchor: toolHandler_whitelist
    // 前端可调用的工具白名单（只读或幂等，不含写/删/执行）
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "build_anchor_index",
            "describe_anchors",
            "get_file_structure",
            "list_directory",
            "read_between_anchors"
    );

    private final ToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;

    // @anchor: toolHandler_constructor
    // 构造：从全局配置创建独立 ToolExecutor（不绑定 Session）
    public ToolHandler() {
        AgentConfig config = HttpServerMain.getGlobalConfig();
        this.objectMapper = new ObjectMapper();
        this.toolExecutor = new ToolExecutor(config, objectMapper);
        this.toolExecutor.setLogConsumer(msg -> System.out.println("[前端工具] " + msg));
    }

    // @anchor: toolHandler_handle
    // 处理前端工具调用：校验工具名后转发 dispatch，统一 JSON 响应
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        Map<String, Object> body;
        try {
            body = HandlerUtils.parseJsonBody(exchange);
        } catch (Exception e) {
            HandlerUtils.sendResponse(exchange, 400,
                    "{\"status\":\"error\",\"message\":\"请求体解析失败\"}");
            return;
        }

        String toolName = (String) body.get("tool");
        if (toolName == null || !ALLOWED_TOOLS.contains(toolName)) {
            HandlerUtils.sendResponse(exchange, 400,
                    "{\"status\":\"error\",\"message\":\"不允许的工具\"}");
            return;
        }

        Object argsRaw = body.get("args");
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (argsRaw instanceof Map)
                ? (Map<String, Object>) argsRaw
                : Map.of();

        try {
            String result = toolExecutor.dispatch(toolName, args);
            String json = objectMapper.writeValueAsString(
                    Map.of("status", "success", "result", result));
            HandlerUtils.sendResponse(exchange, 200, json);
        } catch (Exception e) {
            String json = objectMapper.writeValueAsString(
                    Map.of("status", "error", "message", String.valueOf(e.getMessage())));
            HandlerUtils.sendResponse(exchange, 500, json);
        }
    }
}