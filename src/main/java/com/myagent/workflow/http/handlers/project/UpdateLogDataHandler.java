// @anchor: updateLogDataHandler_intro
// GET /update-log/data?project=xxx 返回合并后的完整 JSON
package com.myagent.workflow.http.handlers.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myagent.workflow.http.utils.HandlerUtils;
import com.myagent.workflow.tools.PathUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

// @anchor: updateLogDataHandler_class
public class UpdateLogDataHandler implements HttpHandler {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        Map<String, String> params = HandlerUtils.parseQuery(exchange.getRequestURI().getQuery());
        String project = params.get("project");
        if (project == null || project.isBlank()) {
            HandlerUtils.sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"缺少 project 参数\"}");
            return;
        }

        try {
            Path updateDir = PathUtils.safeResolve(project, ".update");
            if (!Files.exists(updateDir)) {
                HandlerUtils.sendResponse(exchange, 200, "{\"threads\":[]}");
                return;
            }
            
            ObjectNode result = mapper.createObjectNode();
            ArrayNode detailedThreads = mapper.createArrayNode();
            
            // 直接遍历目录下的所有 thread 文件读取，省去读 index.json 再查一遍的麻烦
            try (var stream = Files.list(updateDir)) {
                var files = stream.filter(p -> p.getFileName().toString().startsWith("thread_") && p.toString().endsWith(".json")).toList();
                for (Path tf : files) {
                    detailedThreads.add(mapper.readTree(tf.toFile()));
                }
            }
            result.set("threads", detailedThreads);

            HandlerUtils.sendResponse(exchange, 200, mapper.writeValueAsString(result));
        } catch (Exception e) {
            HandlerUtils.sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
        }
    }
}