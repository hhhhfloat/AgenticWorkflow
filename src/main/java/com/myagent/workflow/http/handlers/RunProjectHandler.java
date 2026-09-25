// @anchor: runProjectHandler_tot_desc
// 手动运行处理器：POST /runProject，一次性执行 compile_and_run 并返回结果
package com.myagent.workflow.http.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.AgentConfig;
import com.myagent.workflow.core.ConfigEditor;
import com.myagent.workflow.tools.ToolExecutor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

// @anchor: runProjectHandler_class
// 手动运行处理器：直接调用工具执行器编译运行，不进入 Agent 循环与会话
/**
 * POST /runProject
 * <p>
 * 请求体：{ "filename": "xxx", "mode": "html|java|...", ... 可附带 config 字段 }
 * 响应：{ "status":"success", "output":"编译运行结果" }
 * <p>
 * 语义：直接执行一次 compile_and_run，不进入 Agent 循环。
 * 用于前端"手动运行项目"按钮。
 * <p>
 * 说明：这里不绑定 Session，因为它是"一次性执行"，不属于任何会话历史。
 * 因此 ToolExecutor 的 contextManager 参数为 null。
 */
public class RunProjectHandler implements HttpHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    // @anchor: runProjectHandler_handle
    // 处理运行请求：解析参数、构建配置、分派 compile_and_run 工具并回传输出
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
        JsonNode root;
        String filename;
        String mode;

        try {
            root = mapper.readTree(body);
            filename = root.path("filename").asText(null);
            mode = root.path("mode").asText(null);
        } catch (Exception e) {
            writeJson(exchange, 400, error("请求格式错误: " + e.getMessage()));
            return;
        }

        if (filename == null || filename.isEmpty() || mode == null || mode.isEmpty()) {
            writeJson(exchange, 400, error("缺少必要参数: filename, mode"));
            return;
        }

        // 安全检查：禁止路径穿越
        if (filename.contains("..")) {
            writeJson(exchange, 403, error("路径非法"));
            return;
        }

        try {
            AgentConfig config = ConfigEditor.buildFromRequest(root);
            // contextManager 传 null：本接口是一次性执行，不需要上下文
            ToolExecutor executor = new ToolExecutor(config, mapper);

            Map<String, Object> args = new HashMap<>();
            args.put("filename", filename);
            args.put("mode", mode);
            args.put("run", true);

            String result = executor.dispatch("compile_and_run", args);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("output", result);

            writeJson(exchange, 200, mapper.writeValueAsString(response));
        } catch (Exception e) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "error");
            response.put("error", e.getMessage());
            writeJson(exchange, 500, mapper.writeValueAsString(response));
        }
    }

    // @anchor: runProjectHandler_error
    // 组装标准错误响应 JSON
    private String error(String msg) throws IOException {
        return mapper.writeValueAsString(Map.of("status", "error", "message", msg));
    }

    // @anchor: runProjectHandler_writeJson
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
