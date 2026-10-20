// @anchor: runHandler_intro
// 运行处理器：POST /run，以 SSE 流式执行 Agent 任务并回传日志与用量
package com.myagent.workflow.http.handlers.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.config.AgentConfig;
import com.myagent.workflow.core.config.ConfigEditor;
import com.myagent.workflow.core.Main;
import com.myagent.workflow.http.HttpServerMain;
import com.myagent.workflow.http.LogFileWriter;
import com.myagent.workflow.session.Session;
import com.myagent.workflow.session.SessionManager;
import com.myagent.workflow.session.SessionUsage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

// @anchor: runHandler_class
// 运行处理器：解析请求、获取/创建会话、并发控制并以 SSE 推送执行日志
/**
 * POST /run
 * <p>
 * 请求体：
 * {
 *   "prompt": "用户需求",
 *   "sessionId": "可选，为空则创建新会话",
 *   "maxIterations": 可选
 * }
 * <p>
 * 响应：SSE 流
 * - 第一条事件：{"type":"session","sessionId":"xxx"}（告知前端本次会话 ID）
 * - 后续事件：日志（data: xxx\n\n）
 * - 控制事件：单行 JSON data: {"type":"..."}，由前端单独识别
 + - {"type":"context","tokens":N,"modelWindow":N,"iteration":N}  每轮迭代
 + - {"type":"usage",...}                                          用量统计
 + - {"type":"done","summary":"..."} / {"type":"error","message":"..."}  结束
 + - {"type":"end"}                                                流结束
 * <p>
 * 并发模型：
 * - 同一会话不允许并发运行（session.isRunning 检查）
 * - 全局最多允许 N 个任务同时运行（SessionManager.acquireRunningPermit）
 */
public class RunHandler implements HttpHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    // @anchor: runHandler_handle
    // 处理运行请求：鉴权/预检、解析参数、会话并发控制、建立 SSE 并异步执行
    @Override
    public void handle(HttpExchange exchange) throws IOException {

        // ===== 0. API Key 已被清除 =====
        if (HttpServerMain.isApiKeyCleared()) {
            writeJsonError(exchange, 403, "API Key 已被清除，请设置环境变量后重启程序");
            return;
        }

        // ===== 1. CORS 预检 =====
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

        // ===== 2. 解析请求体 =====
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String userRequest;
        String sessionId;
        int maxIterations;
        JsonNode root;

        try {
            root = mapper.readTree(body);
            userRequest = root.path("prompt").asText(null);
            sessionId = root.path("sessionId").asText(null);
            maxIterations = root.path("maxIterations").asInt(AgentConfig.getDefaultMaxIterations());
            if (maxIterations < 3 || maxIterations > HttpServerMain.MAX_ITERATIONS) {
                maxIterations = AgentConfig.getDefaultMaxIterations();
            }
        } catch (Exception e) {
            writeJsonError(exchange, 400, "请求格式错误: " + e.getMessage());
            return;
        }

        if (userRequest == null || userRequest.isBlank()) {
            writeJsonError(exchange, 400, "缺少 prompt 参数");
            return;
        }

        // ===== 3. 获取或创建会话 =====
        SessionManager sessionManager = HttpServerMain.getSessionManager();
        AgentConfig config = ConfigEditor.buildFromRequest(root);
        Session session;
        boolean isNewSession = false;

        if (sessionId == null || sessionId.isEmpty()) {
            session = sessionManager.create(config);
            isNewSession = true;
        } else {
            session = sessionManager.get(sessionId, config);
            if (session == null) {
                writeJsonError(exchange, 404, "会话不存在: " + sessionId);
                return;
            }
            session.updateConfig(config);
        }

        // ===== 4. 会话级并发检查 =====
        if (session.isRunning()) {
            writeJsonError(exchange, 409, "该会话已有任务在运行");
            return;
        }

        // ===== 5. 获取全局运行许可 =====
        if (!sessionManager.acquireRunningPermit()) {
            writeJsonError(exchange, 409, "已有其他会话正在运行，请稍后再试");
            return;
        }
        // 从这一步开始，许可已持有：所有失败路径都必须释放它

        // 用于把“许可释放责任”从本方法转交给 AgentRunner 线程
        boolean permitTransferredToRunner = false;

        // ===== 6. 建立 SSE 并执行 =====
        SseTaskSession sse = null;
        LogFileWriter logWriter = null;

        try {
            // 准备日志文件
            try {
                logWriter = new LogFileWriter(session.getSessionId(), userRequest);
            } catch (IOException e) {
                System.err.println("⚠️ 无法创建日志文件: " + e.getMessage());
            }

            // 追加历史记录
            appendHistory(userRequest);

            // 建立 SSE + 首条 session 事件
            sse = new SseTaskSession(exchange, session, logWriter, "Run");
            sse.sendSessionEvent(isNewSession);

            // 启动 Agent
            final String sid = session.getSessionId();
            final long modelWindow = AgentConfig.getContextWindow();
            final String finalUserRequest = userRequest;
            final SseTaskSession finalSse = sse;

            sse.start(
                    (agent, maxIter) -> agent.run(finalUserRequest, maxIter),
                    (agent) -> agent.setIterationListener((iteration, promptTokens, cachedTokens, completionTokens, cost) -> {
                        String contextJson = String.format(
                                "{\"type\":\"context\",\"sessionId\":\"%s\",\"tokens\":%d,\"modelWindow\":%d,\"iteration\":%d}",
                                sid, promptTokens, modelWindow, iteration);
                        finalSse.sendControl(contextJson);
                    }),
                    maxIterations
            );

        } catch (Throwable t) {
            System.err.println("⚠️ /run 建立 SSE 失败: " + t.getMessage());
            t.printStackTrace();   // 新增：打到 stderr，配合 logback 输出
            if (sse != null) {
                sse.abort(true, t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            } else if (logWriter != null) {
                try { logWriter.close(); } catch (IOException ignored) {}
            }
        } finally {
            if (sse == null || !sse.isPermitTransferred()) {
                sessionManager.releaseRunningPermit();
            }
        }
    }

    // ==================== 辅助方法 ====================

    // @anchor: runHandler_appendHistory
    // 把用户请求追加到全局历史文件 history.jsonl
    /**
     * 追加用户请求到全局历史文件（会话级历史由 SessionStorage 单独保存）。
     */
    private void appendHistory(String userRequest) {
        try {
            Path historyFile = Paths.get("./HistoryOutput/history.jsonl");
            if (!Files.exists(historyFile.getParent())) {
                Files.createDirectories(historyFile.getParent());
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("timestamp", LocalDateTime.now().toString());
            entry.put("prompt", userRequest);
            String line = mapper.writeValueAsString(entry) + System.lineSeparator();
            Files.writeString(historyFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("⚠️ 写入历史记录失败: " + e.getMessage());
        }
    }

    // @anchor: runHandler_writeJsonError
    // 未建立 SSE 前以 JSON 返回错误响应
    /**
     * 以 JSON 形式返回错误（未建立 SSE 连接时使用）。
     */
    private void writeJsonError(HttpExchange exchange, int code, String message) throws IOException {
        String json = mapper.writeValueAsString(Map.of("status", "error", "message", message));
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
