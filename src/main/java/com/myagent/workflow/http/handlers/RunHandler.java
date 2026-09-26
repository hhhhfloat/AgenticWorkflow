// @anchor: runHandler_tot_desc
// 运行处理器：POST /run，以 SSE 流式执行 Agent 任务并回传日志与用量
package com.myagent.workflow.http.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.AgentConfig;
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
 * - 结束事件：[结束]
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

        try {
            JsonNode root = mapper.readTree(body);
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
        AgentConfig config = HttpServerMain.getGlobalConfig();
        Session session;
        boolean isNewSession = false;

        if (sessionId == null || sessionId.isEmpty()) {
            // 创建新会话
            session = sessionManager.create(config);
            isNewSession = true;
        } else {
            session = sessionManager.get(sessionId, config);
            if (session == null) {
                writeJsonError(exchange, 404, "会话不存在: " + sessionId);
                return;
            }
        }

        // ===== 4. 并发检查 =====
        if (session.isRunning()) {
            writeJsonError(exchange, 409, "该会话已有任务在运行");
            return;
        }
        if (!sessionManager.acquireRunningPermit()) {
            writeJsonError(exchange, 409, "已有其他会话正在运行，请稍后再试");
            return;
        }

        // ===== 5. 建立 SSE 连接 =====
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        OutputStream out = exchange.getResponseBody();

        // ===== 6. 准备日志文件 =====
        LogFileWriter logWriter = null;
        try {
            logWriter = new LogFileWriter(userRequest);
        } catch (IOException e) {
            System.err.println("⚠️ 无法创建日志文件: " + e.getMessage());
        }

        // ===== 7. 追加历史记录（原有逻辑） =====
        appendHistory(userRequest);

        final LogFileWriter finalLogWriter = logWriter;

        try {
            // ===== 8. 首条事件：告知前端 sessionId =====
            sendEvent(out, "{\"type\":\"session\",\"sessionId\":\"" + session.getSessionId()
                    + "\",\"isNew\":" + isNewSession + "}");

            // ===== 9. 绑定日志消费者 =====
            session.setLogConsumer(msg -> {
                try {
                    sendEvent(out, msg);
                    if (finalLogWriter != null) {
                        finalLogWriter.write(msg);
                    }
                } catch (Exception e) {
                    // SSE 客户端可能已断开，静默
                }
            });

            // ===== 10. 创建 Agent 并启动线程 =====
            Main agent = new Main(session);
            final int finalMaxIterations = maxIterations;

            new Thread(() -> {
                try {
                    String result = agent.run(userRequest, finalMaxIterations);

                    // 发 usage 事件
                    Main.TaskUsage usage = agent.getLastTaskUsage();
                    if (usage != null) {
                        SessionUsage delta = new SessionUsage(usage.promptTokens(), usage.cachedTokens(), usage.completionTokens(),
                                usage.apiCalls(), usage.cost());
                        SessionUsage total = sessionManager.appendUsage(session.getSessionId(), delta);
                        String usageJson = String.format(
                                "{\"type\":\"usage\",\"sessionId\":\"%s\",\"promptTokens\":%d,\"cachedTokens\":%d,"
                                        + "\"completionTokens\":%d,\"apiCalls\":%d,\"cost\":%.6f}",
                                session.getSessionId(),
                                total.promptTokens(), total.cachedTokens(), total.completionTokens(),
                                total.apiCalls(), total.cost()
                        );
                        sendEvent(out, usageJson);
                    }

                    sendEvent(out, "[完成] " + result);
                    sendEvent(out, "[结束]");
                } catch (Exception e) {
                    try {
                        sendEvent(out, "[错误] " + e.getMessage());
                        sendEvent(out, "[结束]");
                    } catch (IOException ignored) {}
                } finally {
                    // 清理：解绑日志、释放许可、关闭流、关闭日志文件
                    session.setLogConsumer(null);
                    sessionManager.releaseRunningPermit();
                    sessionManager.save(session);

                    if (finalLogWriter != null) {
                        try {
                            finalLogWriter.close();
                        } catch (IOException ignored) {}
                    }
                    try {
                        out.close();
                    } catch (IOException ignored) {}
                }
            }, "AgentRunner-" + session.getSessionId()).start();

        } catch (Exception e) {
            // 建立 SSE 过程中出错，回滚
            session.setLogConsumer(null);
            sessionManager.releaseRunningPermit();
            try {
                sendEvent(out, "[错误] " + e.getMessage());
            } catch (IOException ignored) {}
            try {
                out.close();
            } catch (IOException ignored) {}
            if (finalLogWriter != null) {
                try {
                    finalLogWriter.close();
                } catch (IOException ignored) {}
            }
        }
    }

    // ==================== 辅助方法 ====================

    // @anchor: runHandler_sendEvent
    // 以 SSE 单行 data 形式发送一条事件（换行转义）
    /**
     * SSE 事件发送。所有换行转为 \n 转义，保证单行 data。
     */
    private void sendEvent(OutputStream out, String data) throws IOException {
        String event = "data: " + data.replace("\n", "\\n") + "\n\n";
        out.write(event.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

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
