// @anchor: contextHandler_intro
// 上下文大小查询端点：GET /session/context，返回当前上下文的字节数与精确 token 数
package com.myagent.workflow.http.handlers.session;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

// @anchor: contextHandler_class
// 上下文查询处理器：未运行时读 jsonl 字节数，运行中读 lastPromptTokens
public class ContextHandler implements HttpHandler {

    private static final Path SESSIONS_DIR = Paths.get(AgentConfig.getSessionsDir());
    private static final String BASE_FILE = "immutable_base.jsonl";

    private final ObjectMapper objectMapper = new ObjectMapper();

    // @anchor: contextHandler_handle
    // 入口：解析 sessionId → 取会话 → 返回上下文大小信息
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
        AgentConfig globalConfig = HttpServerMain.getGlobalConfig();

        // 优先内存；未命中则从磁盘加载
        Session session = sessionManager.get(sessionId, globalConfig);
        if (session == null) {
            HandlerUtils.sendResponse(exchange, 404, error("not-found", "会话不存在: " + sessionId));
            return;
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sessionId", sessionId);
        resp.put("modelWindow", AgentConfig.getContextWindow());

        if (session.isRunning()) {
            // 运行中：tokens 从 ContextManager 取，bytes 暂不可靠（磁盘未更新）
            ContextManager cm = session.getContextManager();
            long tokens = cm != null ? cm.getLastPromptTokens() : 0;
            resp.put("running", true);
            resp.put("tokens", tokens > 0 ? tokens : null);
            resp.put("bytes", null);
        } else {
            // 未运行：读 immutable_base.jsonl 的字节数
            Path baseFile = SESSIONS_DIR.resolve(sessionId).resolve(BASE_FILE);
            Long bytes = null;
            if (Files.exists(baseFile)) {
                try {
                    bytes = Files.size(baseFile);
                } catch (IOException e) {
                    // 读文件失败，保持 null
                }
            }
            resp.put("running", false);
            resp.put("tokens", null);
            resp.put("bytes", bytes);
        }

        HandlerUtils.sendResponse(exchange, 200, objectMapper.writeValueAsString(resp));
    }

    // @anchor: contextHandler_error
    // 统一的错误响应
    private String error(String code, String message) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("code", code);
        m.put("message", message);
        return objectMapper.writeValueAsString(m);
    }
}