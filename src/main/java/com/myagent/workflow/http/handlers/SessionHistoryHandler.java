// @anchor: sessionHistoryHandler_tot_desc
// 会话历史处理器：GET /session/history，返回会话的持久消息与元信息
package com.myagent.workflow.http.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.AgentConfig;
import com.myagent.workflow.core.ContextManager;
import com.myagent.workflow.http.HttpServerMain;
import com.myagent.workflow.http.utils.HandlerUtils;
import com.myagent.workflow.session.Session;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// @anchor: sessionHistoryHandler_class
// 会话历史处理器：从内存或磁盘加载会话，返回其持久历史（immutableBase）
/**
 * GET /session/history?sessionId=xxx
 * <p>
 * 响应：
 * {
 *   "sessionId": "xxx",
 *   "title": "...",
 *   "state": "IDLE",
 *   "messageCount": 12,
 *   "messages": [
 *     { "role":"system", "content":"..." },
 *     { "role":"user", "content":"..." },
 *     ...
 *   ]
 * }
 * <p>
 * 用途：
 * - 用户点击侧边栏某个会话时，前端拉取完整历史以渲染对话
 * - 页面刷新后，前端根据本地保存的 sessionId 恢复当前会话
 * <p>
 * 注意：
 * - 只返回 immutableBase（会话级持久历史），不返回 volatileWorking
 *   （工作区是任务级临时内容，任务结束后已清空）
 * - 若会话不在内存，会从磁盘自动加载
 */
public class SessionHistoryHandler implements HttpHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    // @anchor: sessionHistoryHandler_handle
    // 处理历史请求：定位会话并回传其上下文快照与统计
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // 从 query 中提取 sessionId
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = HandlerUtils.parseQuery(query);
        String sessionId = params.get("sessionId");

        if (sessionId == null || sessionId.isEmpty()) {
            writeJson(exchange, 400, error("缺少 sessionId"));
            return;
        }

        AgentConfig config = HttpServerMain.getGlobalConfig();
        Session session = HttpServerMain.getSessionManager().get(sessionId, config);

        if (session == null) {
            writeJson(exchange, 404, error("会话不存在: " + sessionId));
            return;
        }

        ContextManager cm = session.getContextManager();
        List<Map<String, Object>> messages = cm != null
                ? cm.getImmutableBaseSnapshot()
                : new ArrayList<>();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionId", session.getSessionId());
        response.put("title", session.getMeta().title());
        response.put("state", session.getState().name());
        response.put("messageCount", session.getMeta().messageCount());
        response.put("messages", messages);

        writeJson(exchange, 200, mapper.writeValueAsString(response));
    }

    // @anchor: sessionHistoryHandler_error
    // 组装标准错误响应 JSON
    private String error(String msg) throws IOException {
        return mapper.writeValueAsString(Map.of("status", "error", "message", msg));
    }

    // @anchor: sessionHistoryHandler_writeJson
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
