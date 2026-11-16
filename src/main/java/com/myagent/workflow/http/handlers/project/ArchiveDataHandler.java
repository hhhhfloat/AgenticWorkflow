// @anchor: archiveDataHandler_intro
// GET /archive/data?project=xxx
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

public class ArchiveDataHandler implements HttpHandler {
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
            Path indexFile = updateDir.resolve("index.json");
            if (!Files.exists(indexFile)) {
                HandlerUtils.sendResponse(exchange, 200, "{\"threads\":[]}");
                return;
            }

            // 读 index，遍历 thread 文件，拼成一个返回对象
            ObjectNode index = (ObjectNode) mapper.readTree(indexFile.toFile());
            ArrayNode threads = (ArrayNode) index.get("threads");
            ObjectNode result = mapper.createObjectNode();
            ArrayNode detailedThreads = mapper.createArrayNode();

            for (int i = 0; i < threads.size(); i++) {
                String tid = threads.get(i).get("thread_id").asText();
                Path threadFile = updateDir.resolve("thread_" + tid + ".json");
                if (Files.exists(threadFile)) {
                    detailedThreads.add(mapper.readTree(threadFile.toFile()));
                }
            }
            result.set("threads", detailedThreads);

            HandlerUtils.sendResponse(exchange, 200, mapper.writeValueAsString(result));
        } catch (Exception e) {
            HandlerUtils.sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
        }
    }
}