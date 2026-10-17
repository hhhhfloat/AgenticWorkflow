// @anchor: hasRecoveryHandler_intro
// 恢复检测端点：GET /session/hasRecovery，返回是否有 recovery 文件与上次的 prompt
package com.myagent.workflow.http.handlers.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.ContextManager;
import com.myagent.workflow.core.config.AgentConfig;
import com.myagent.workflow.http.HttpServerMain;
import com.myagent.workflow.http.utils.HandlerUtils;
import com.myagent.workflow.session.Session;
import com.myagent.workflow.session.SessionManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

// @anchor: hasRecoveryHandler_class
// 检测 handler：读 recovery 文件，提取最后一条 user prompt
public class HasRecoveryHandler implements HttpHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    // @anchor: hasRecoveryHandler_handle
    // 入口：解析 sessionId → 检查 recovery → 返回 hasRecovery + lastPrompt
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            HandlerUtils.sendResponse(exchange, 405, error("method-not-allowed", "仅支持 GET"));
            return;
        }

        Map<String, String> query = HandlerUtils.parseQuery(exchange.getRequestURI().getQuery());
        String sessionId = query.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            HandlerUtils.sendResponse(exchange, 400, error("missing-param", "缺少 sessionId"));
            return;
        }

        SessionManager sessionManager = HttpServerMain.getSessionManager();
        Session session = sessionManager.get(sessionId, HttpServerMain.getGlobalConfig());
        if (session == null) {
            HandlerUtils.sendResponse(exchange, 404, error("not-found", "会话不存在"));
            return;
        }

        ContextManager cm = session.getContextManager();
        boolean has = cm != null && cm.hasRecovery();
        String lastPrompt = has ? extractLastUserPrompt(cm.getRecoveryPath()) : null;

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("hasRecovery", has);
        resp.put("lastPrompt", lastPrompt);
        HandlerUtils.sendResponse(exchange, 200, mapper.writeValueAsString(resp));
    }

    // @anchor: hasRecoveryHandler_extractLastUserPrompt
    // 从 recovery 文件里读最后一条 request 的 messages，从末尾往前找最后一条 user 消息
    private String extractLastUserPrompt(Path recoveryPath) {
        try {
            String content = Files.readString(recoveryPath, StandardCharsets.UTF_8);
            // 每行一条记录；从末尾往前找 type=request
            String[] lines = content.split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                if (lines[i].isBlank()) continue;
                try {
                    JsonNode node = mapper.readTree(lines[i]);
                    if (!"request".equals(node.path("type").asText())) continue;
                    JsonNode messages = node.path("body").path("messages");
                    if (!messages.isArray()) continue;
                    for (int j = messages.size() - 1; j >= 0; j--) {
                        JsonNode m = messages.get(j);
                        if ("user".equals(m.path("role").asText())) {
                            return m.path("content").asText(null);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    // @anchor: hasRecoveryHandler_error
    private String error(String code, String message) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("code", code);
        m.put("message", message);
        return mapper.writeValueAsString(m);
    }
}