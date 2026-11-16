// @anchor: updateLogActionHandler_intro
// POST /update-log/action 处理所有写操作
package com.myagent.workflow.http.handlers.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.http.HttpServerMain;
import com.myagent.workflow.http.utils.HandlerUtils;
import com.myagent.workflow.session.SessionManager;
import com.myagent.workflow.tools.archive.UpdateArchive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;

// @anchor: updateLogActionHandler_class
public class UpdateLogActionHandler implements HttpHandler {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        try {
            JsonNode body = mapper.readTree(exchange.getRequestBody().readAllBytes());
            String project = body.path("project").asText(null);
            String action = body.path("action").asText(null);

            if (project == null || project.isBlank()) {
                HandlerUtils.sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"缺少 project\"}");
                return;
            }
            if (action == null) {
                HandlerUtils.sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"缺少 action\"}");
                return;
            }

            // Agent 正在跑同一项目时，拒绝用户的写操作（避免丢失 Agent 刚追加的记录）
            SessionManager sm = HttpServerMain.getSessionManager();
            boolean projectLocked = sm != null && sm.listActive().stream()
                    .anyMatch(s -> s.isRunning() && project.equals(s.getWorkProject()));
            if (projectLocked) {
                HandlerUtils.sendResponse(exchange, 409,
                        "{\"status\":\"error\",\"message\":\"Agent 正在该项目上运行，请等待完成后再操作\"}");
                return;
            }

            UpdateArchive archive = new UpdateArchive(mapper, project);
            String result;

            switch (action) {
                case "append":
                    result = archive.appendLog(
                            body.path("threadId").asText(),
                            body.path("title").asText(null),
                            body.path("summary").asText(),
                            body.path("details").asText(null),
                            body.path("files").asText(null),
                            body.path("status").asText(null), // 补上 status
                            body.path("timestamp").asText(null) // 补上 timestamp
                    );
                    break;
                case "delete":
                    result = archive.deleteUpdate(
                            body.path("threadId").asText(),
                            body.path("updateIndex").asInt());
                    break;
                case "move":
                    result = archive.moveUpdate(
                            body.path("fromThread").asText(),
                            body.path("toThread").asText(),
                            body.path("updateIndex").asInt());
                    break;
                case "merge":
                    result = archive.mergeThread(
                            body.path("fromThread").asText(),
                            body.path("toThread").asText(),
                            body.path("newTitle").asText(null));
                    break;
                case "status":
                    result = archive.setStatus(
                            body.path("threadId").asText(),
                            body.path("status").asText());
                    break;
                default:
                    result = "❌ 未知的 action: " + action;
            }

            boolean success = result.startsWith("✅");
            HandlerUtils.sendResponse(exchange, success ? 200 : 400,
                    mapper.writeValueAsString(Map.of("status", success ? "success" : "error", "message", result)));
        } catch (Exception e) {
            HandlerUtils.sendResponse(exchange, 500, "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
        }
    }
}