// @anchor: hasRecoveryHandler_intro
// 恢复检测端点：GET /session/hasRecovery，返回是否有 recovery 文件与上次的 prompt
package com.myagent.workflow.http.handlers.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.ContextManager;
import com.myagent.workflow.core.HistoryRecorder;
import com.myagent.workflow.http.HttpServerMain;
import com.myagent.workflow.http.utils.HandlerUtils;
import com.myagent.workflow.session.Session;
import com.myagent.workflow.session.SessionManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// @anchor: hasRecoveryHandler_class
// 检测 handler：读 recovery 快照，指纹校验后提取最后一条 user prompt
public class HasRecoveryHandler implements HttpHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    // @anchor: hasRecoveryHandler_handle
    // 入口：解析 sessionId → 读 recovery → 指纹校验 → 返回 hasRecovery + lastPrompt
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

        // 只读一次文件：既判断 has，也供指纹校验和提取 prompt 使用
        HistoryRecorder.RecoverySnapshot snapshot = cm != null ? cm.readRecovery() : null;
        boolean has = snapshot != null;
        String lastPrompt = null;

        if (has) {
            // 指纹校验：基础区变了，文件虽然还在，但 resume 会被 409 拒绝。
            // 在 hasRecovery 就返回 false，前端不会显示"继续上次任务"按钮，
            // 避免用户点了才被告知"不能续跑"。
            String currentFingerprint = cm.getBaseFingerprint();
            if (!currentFingerprint.equals(snapshot.fingerprint())) {
                has = false;
            } else {
                lastPrompt = extractLastUserPrompt(snapshot);
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("hasRecovery", has);
        resp.put("lastPrompt", lastPrompt);
        HandlerUtils.sendResponse(exchange, 200, mapper.writeValueAsString(resp));
    }

    // @anchor: hasRecoveryHandler_extractLastUserPrompt
    // 从 recovery 快照里从末尾向前找最后一条 user 消息
    private String extractLastUserPrompt(HistoryRecorder.RecoverySnapshot snapshot) {
        if (snapshot == null || snapshot.messages() == null) return null;
        List<Map<String, Object>> messages = snapshot.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> m = messages.get(i);
            if ("user".equals(m.get("role"))) {
                Object content = m.get("content");
                return content != null ? content.toString() : null;
            }
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