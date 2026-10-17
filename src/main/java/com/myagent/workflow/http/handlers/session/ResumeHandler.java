// @anchor: resumeHandler_intro
// 恢复端点：POST /session/resume，从 recovery 文件恢复 volatileWorking 并续跑
package com.myagent.workflow.http.handlers.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.ContextManager;
import com.myagent.workflow.core.Main;
import com.myagent.workflow.core.config.AgentConfig;
import com.myagent.workflow.core.config.ConfigEditor;
import com.myagent.workflow.http.HttpServerMain;
import com.myagent.workflow.http.LogFileWriter;
import com.myagent.workflow.http.handlers.run.SseLogSink;
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
import java.util.*;

// @anchor: resumeHandler_class
// 恢复处理器：读 recovery → 注入 volatileWorking → SSE 流式跑 runResume
public class ResumeHandler implements HttpHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    // @anchor: resumeHandler_handle
    // 入口：鉴权 → 解析 → 会话检查 → 恢复 volatileWorking → SSE 启动
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (HttpServerMain.isApiKeyCleared()) {
            writeJsonError(exchange, 403, "API Key 已被清除");
            return;
        }
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

        // ===== 解析请求体 =====
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String sessionId;
        int maxIterations;
        JsonNode root;
        try {
            root = mapper.readTree(body);
            sessionId = root.path("sessionId").asText(null);
            maxIterations = root.path("maxIterations").asInt(AgentConfig.getDefaultMaxIterations());
            if (maxIterations < 3 || maxIterations > HttpServerMain.MAX_ITERATIONS) {
                maxIterations = AgentConfig.getDefaultMaxIterations();
            }
        } catch (Exception e) {
            writeJsonError(exchange, 400, "请求格式错误: " + e.getMessage());
            return;
        }
        if (sessionId == null || sessionId.isEmpty()) {
            writeJsonError(exchange, 400, "缺少 sessionId");
            return;
        }

        // ===== 会话检查 =====
        SessionManager sessionManager = HttpServerMain.getSessionManager();
        AgentConfig config = ConfigEditor.buildFromRequest(root);
        Session session = sessionManager.get(sessionId, config);
        if (session == null) {
            writeJsonError(exchange, 404, "会话不存在: " + sessionId);
            return;
        }
        session.updateConfig(config);
        if (session.isRunning()) {
            writeJsonError(exchange, 409, "该会话已有任务在运行");
            return;
        }

        ContextManager cm = session.getContextManager();
        if (cm == null || !cm.hasRecovery()) {
            writeJsonError(exchange, 400, "没有可恢复的进度");
            return;
        }

        // ===== 全局许可 =====
        if (!sessionManager.acquireRunningPermit()) {
            writeJsonError(exchange, 409, "已有其他会话正在运行");
            return;
        }
        boolean permitTransferredToRunner = false;

        Thread pingThread = null;
        LogFileWriter logWriter = null;
        OutputStream out = null;
        SseLogSink logSink = null;
        final Object outLock = new Object();

        try {
            // ===== 恢复 volatileWorking =====
            List<Map<String, Object>> restored = readVolatileWorkingFromRecovery(
                    cm.getRecoveryPath(), cm.getBaseMessageCount());
            if (restored.isEmpty()) {
                writeJsonError(exchange, 400, "recovery 文件里没有可恢复的工作区");
                return;
            }
            cm.restoreVolatileWorking(restored);

            // ===== SSE 建立（以下与 RunHandler 逻辑相同） =====
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);
            out = exchange.getResponseBody();

            try {
                logWriter = new LogFileWriter(session.getSessionId(), "[恢复任务]");
            } catch (IOException e) {
                System.err.println("⚠️ 无法创建日志文件: " + e.getMessage());
            }

            final OutputStream finalOut = out;
            logSink = new SseLogSink(finalOut, outLock, session.getSessionId(), logWriter);
            final LogFileWriter finalLogWriter = logWriter;

            final OutputStream pingOut = out;
            Thread pThread = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(15_000);
                        synchronized (outLock) {
                            pingOut.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                            pingOut.flush();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {}
            }, "ResumePing-" + session.getSessionId());
            pThread.setDaemon(true);
            pingThread = pThread;

            synchronized (outLock) {
                sendEvent(out, "{\"type\":\"session\",\"sessionId\":\"" + session.getSessionId()
                        + "\",\"isNew\":false}");
            }
            session.addLogSink(logSink);

            Main agent = new Main(session);
            final int finalMaxIterations = maxIterations;

            pThread.start();

            SseLogSink finalLogSink = logSink;
            new Thread(() -> {
                String result = null;
                Throwable error = null;
                try {
                    result = agent.runResume(finalMaxIterations);
                } catch (Throwable t) {
                    error = t;
                }

                try {
                    Main.TaskUsage usage = agent.getLastTaskUsage();
                    if (usage != null) {
                        try {
                            SessionUsage delta = new SessionUsage(
                                    usage.promptTokens(), usage.cachedTokens(), usage.completionTokens(),
                                    usage.apiCalls(), usage.cost());
                            SessionUsage total = sessionManager.appendUsage(session.getSessionId(), delta);
                            String usageJson = String.format(
                                    "{\"type\":\"usage\",\"sessionId\":\"%s\",\"promptTokens\":%d,\"cachedTokens\":%d," +
                                            "\"completionTokens\":%d,\"apiCalls\":%d,\"cost\":%.6f}",
                                    session.getSessionId(),
                                    total.promptTokens(), total.cachedTokens(), total.completionTokens(),
                                    total.apiCalls(), total.cost());
                            finalLogSink.sendControl(usageJson);
                        } catch (Exception ignored) {}
                    }

                    if (error != null) {
                        String msg = error.getMessage() != null
                                ? error.getMessage() : error.getClass().getSimpleName();
                        finalLogSink.sendControl(jsonEvent("error",
                                Map.of("message", msg, "sessionId", session.getSessionId())));
                    } else {
                        finalLogSink.sendControl(jsonEvent("done", Map.of("summary", result == null ? "" : result)));
                    }
                    finalLogSink.sendControl("{\"type\":\"end\"}");
                } finally {
                    pThread.interrupt();
                    finalLogSink.flush(3000);
                    session.removeLogSink(finalLogSink);
                    finalLogSink.close();
                    if (finalLogWriter != null) {
                        try { finalLogWriter.close(); } catch (IOException ignored) {}
                    }
                    sessionManager.releaseRunningPermit();
                    sessionManager.save(session);
                    try { finalOut.close(); } catch (IOException ignored) {}
                }
            }, "ResumeRunner-" + session.getSessionId()).start();

            permitTransferredToRunner = true;

        } catch (Throwable t) {
            System.err.println("⚠️ /session/resume 建立 SSE 失败: " + t.getMessage());
            if (pingThread != null) pingThread.interrupt();
            if (logSink != null) {
                session.removeLogSink(logSink);
                logSink.close();
            }
            if (out != null) {
                try { out.close(); } catch (IOException ignored) {}
            }
        } finally {
            if (!permitTransferredToRunner) {
                sessionManager.releaseRunningPermit();
            }
        }
    }

    // @anchor: resumeHandler_readVolatileWorking
    // 从 recovery 文件最后一条 request 里切出 volatileWorking（跳过 immutableBase 部分）
    private List<Map<String, Object>> readVolatileWorkingFromRecovery(
            Path recoveryPath, int immutableBaseSize) throws IOException {
        String content = Files.readString(recoveryPath, StandardCharsets.UTF_8);
        String[] lines = content.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].isBlank()) continue;
            try {
                JsonNode node = mapper.readTree(lines[i]);
                if (!"request".equals(node.path("type").asText())) continue;
                JsonNode messagesNode = node.path("body").path("messages");
                if (!messagesNode.isArray()) continue;
                List<Map<String, Object>> messages = mapper.convertValue(
                        messagesNode, new TypeReference<List<Map<String, Object>>>() {});
                if (messages.size() <= immutableBaseSize) return List.of();
                return new ArrayList<>(messages.subList(immutableBaseSize, messages.size()));
            } catch (Exception ignored) {}
        }
        return List.of();
    }

    // @anchor: resumeHandler_sendEvent
    private void sendEvent(OutputStream out, String data) throws IOException {
        String normalized = data.replace("\r\n", "\n").replace("\r", "\n");
        StringBuilder sb = new StringBuilder();
        for (String line : normalized.split("\n", -1)) {
            sb.append("data: ").append(line).append('\n');
        }
        sb.append('\n');
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // @anchor: resumeHandler_jsonEvent
    private String jsonEvent(String type, Map<String, Object> fields) {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("type", type);
        if (fields != null) obj.putAll(fields);
        try {
            return mapper.writeValueAsString(obj);
        } catch (IOException e) {
            return "{\"type\":\"" + type + "\"}";
        }
    }

    // @anchor: resumeHandler_writeJsonError
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