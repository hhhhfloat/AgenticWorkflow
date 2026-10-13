// @anchor: compactHandler_intro
// 上下文压缩端点：POST /session/compact，用 LLM 摘要替换基础区历史
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
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

// @anchor: compactHandler_class
// 压缩处理器：校验会话 → 拼压缩请求 → 调 API → 替换基础区 → 落盘
public class CompactHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(CompactHandler.class);

    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    // 摘要输出上限：防止压缩后基础区仍然过大，正常情况下摘要远小于此值
    private static final int COMPACT_MAX_OUTPUT_TOKENS = 10000;
    // @anchor: compactHandler_constructor
    // 构造：注入会话管理器与配置，创建独立 HTTP 客户端
    public CompactHandler() {
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .build();
    }

    // @anchor: compactHandler_handle
    // 入口：解析请求 → 校验 → 压缩 → 落盘 → 响应
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            HandlerUtils.sendResponse(exchange, 405, error("method-not-allowed", "仅支持 POST"));
            return;
        }

        // 静态单例在 handle 里取，避免构造时未初始化
        SessionManager sessionManager = HttpServerMain.getSessionManager();

        Map<String, Object> body;
        try {
            body = HandlerUtils.parseJsonBody(exchange);
        } catch (IOException e) {
            HandlerUtils.sendResponse(exchange, 400, error("bad-body", "请求体解析失败: " + e.getMessage()));
            return;
        }

        String sessionId = asString(body.get("sessionId"));
        if (sessionId == null || sessionId.isBlank()) {
            HandlerUtils.sendResponse(exchange, 400, error("missing-param", "缺少 sessionId"));
            return;
        }
        int keepRecentPairs = asInt(body.get("keepRecentPairs"), 2);
        if (keepRecentPairs < 0) keepRecentPairs = 0;

        AgentConfig globalConfig = HttpServerMain.getGlobalConfig();
        Session session = sessionManager.get(sessionId, globalConfig);
        if (session == null) {
            HandlerUtils.sendResponse(exchange, 404, error("not-found", "会话不存在: " + sessionId));
            return;
        }
        if (session.isRunning()) {
            HandlerUtils.sendResponse(exchange, 409, error("running", "会话正在运行，无法压缩"));
            return;
        }

        AgentConfig config = session.getConfig();
        ContextManager cm = session.getContextManager();
        if (cm == null) {
            HandlerUtils.sendResponse(exchange, 500, error("no-context", "会话上下文未初始化"));
            return;
        }
        List<Map<String, Object>> base = cm.getImmutableBaseSnapshot();
        int total = base.size();
        int keepCount = Math.min(keepRecentPairs * 2, total - 1);
        int compressEnd = total - keepCount;   // 压缩范围：[1, compressEnd)，跳过 SystemPrompt 与最近 keep 对
        if (compressEnd <= 1) {
            HandlerUtils.sendResponse(exchange, 400,
                    error("too-short", "除 SystemPrompt 与最近 " + keepRecentPairs + " 对外无历史可压"));
            return;
        }
        int beforeCount = total;

        // 归档压缩前的基础区
        String archiveFile;
        try {
            archiveFile = sessionManager.archiveBase(sessionId, base);
        } catch (IOException e) {
            HandlerUtils.sendResponse(exchange, 500,
                    error("archive-failed", "归档失败，已取消压缩: " + e.getMessage()));
            return;
        }

        // 只发要压缩的部分（跳过 SystemPrompt，跳过最近 keep 对），加一条 user 指令
        List<Map<String, Object>> reqMessages = new ArrayList<>(base.subList(1, compressEnd));
        reqMessages.add(Map.of("role", "user", "content", cm.getCompactInstruction()));

        String summary;
        long promptTokens;
        long completionTokens;
        double cost;
        boolean truncated = false;
        try {
            JsonNode root = callApi(reqMessages, config);
            JsonNode choices = root.get("choices");
            if (choices == null || choices.isEmpty()) {
                HandlerUtils.sendResponse(exchange, 502, error("api-bad-response", "API 返回无 choices"));
                return;
            }
            JsonNode finish = choices.get(0).get("finish_reason");
            truncated = finish != null && "length".equals(finish.asText());
            if (truncated) {
                logger.warn("压缩摘要被 max_tokens 截断，sessionId={}", sessionId);
            }
            JsonNode messageNode = choices.get(0).get("message");
            summary = (messageNode != null && messageNode.has("content"))
                    ? messageNode.get("content").asText()
                    : null;
            if (summary == null || summary.isBlank()) {
                HandlerUtils.sendResponse(exchange, 502, error("api-empty-summary", "API 返回空摘要"));
                return;
            }
            JsonNode usage = root.get("usage");
            promptTokens = (usage != null && usage.has("prompt_tokens"))
                    ? usage.get("prompt_tokens").asLong(0) : 0;
            completionTokens = (usage != null && usage.has("completion_tokens"))
                    ? usage.get("completion_tokens").asLong(0) : 0;
            cost = cm.recordUsage(config.model(), promptTokens, 0, completionTokens);
        } catch (IOException e) {
            logger.warn("压缩 API 调用失败: {}", e.getMessage());
            HandlerUtils.sendResponse(exchange, 502, error("api-error", "API 调用失败: " + e.getMessage()));
            return;
        }

        cm.applyCompaction(summary, keepRecentPairs);
        sessionManager.save(session);

        int afterCount = cm.getBaseMessageCount();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("beforeCount", beforeCount);
        resp.put("afterCount", afterCount);
        resp.put("summary", summary);
        resp.put("archiveFile", archiveFile);
        resp.put("truncated", truncated);
        Map<String, Object> usageOut = new LinkedHashMap<>();
        usageOut.put("promptTokens", promptTokens);
        usageOut.put("completionTokens", completionTokens);
        usageOut.put("cost", cost);
        resp.put("usage", usageOut);

        HandlerUtils.sendResponse(exchange, 200, objectMapper.writeValueAsString(resp));
    }

    // @anchor: compactHandler_callApi
    // 发一次非流式 API 调用，返回解析后的 JSON
    private JsonNode callApi(List<Map<String, Object>> messages, AgentConfig config) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("messages", messages);
        body.put("max_tokens", COMPACT_MAX_OUTPUT_TOKENS);
        // 不传 tools：压缩只需要文本输出
        String jsonBody = objectMapper.writeValueAsString(body);

        Request request = new Request.Builder()
                .url(AgentConfig.getApiUrl())
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errBody = response.body() != null ? response.body().string() : "(null)";
                throw new IOException("HTTP " + response.code() + " | " + errBody);
            }
            ResponseBody rb = response.body();
            if (rb == null) throw new IOException("响应体为空");
            return objectMapper.readTree(rb.string());
        }
    }

    // @anchor: compactHandler_helpers
    // 参数提取与错误响应工具
    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static int asInt(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return def; }
    }

    private String error(String code, String message) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("code", code);
        m.put("message", message);
        return objectMapper.writeValueAsString(m);
    }
}