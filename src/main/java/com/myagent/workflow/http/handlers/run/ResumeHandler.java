// @anchor: resumeHandler_intro
// 恢复端点：POST /session/resume，从 recovery 文件恢复 volatileWorking 并续跑
package com.myagent.workflow.http.handlers.run;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.ContextManager;
import com.myagent.workflow.core.HistoryRecorder;
import com.myagent.workflow.core.config.AgentConfig;
import com.myagent.workflow.core.config.ConfigEditor;
import com.myagent.workflow.http.HttpServerMain;
import com.myagent.workflow.http.LogFileWriter;
import com.myagent.workflow.session.Session;
import com.myagent.workflow.session.SessionManager;
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
                if (cm == null) {
                        writeJsonError(exchange, 400, "会话上下文未初始化");
                        return;
                    }
                HistoryRecorder.RecoverySnapshot snapshot = cm.readRecovery();
                if (snapshot == null) {
                        writeJsonError(exchange, 400, "没有可恢复的进度");
                        return;
                    }
                // C7：指纹校验，基础区自 recovery 生成以来变过就拒绝
                        String currentFingerprint = cm.getBaseFingerprint();
                if (!currentFingerprint.equals(snapshot.fingerprint())) {
                        writeJsonError(exchange, 409,
                                        "上下文已变化，无法续跑（recovery 指纹 " + snapshot.fingerprint()
                                                        + "，当前 " + currentFingerprint + "）。请重新发起任务。");
                        return;
                    }

        // ===== 全局许可 =====
        if (!sessionManager.acquireRunningPermit()) {
            writeJsonError(exchange, 409, "已有其他会话正在运行");
            return;
        }
        boolean permitTransferredToRunner = false;

        SseTaskSession sse = null;
        LogFileWriter logWriter = null;

        try {
            // ===== 恢复 volatileWorking（必须在 SSE 建立之前）=====
            List<Map<String, Object>> restored = readVolatileWorkingFromRecovery(
                    snapshot, cm.getBaseMessageCount());
            if (restored.isEmpty()) {
                writeJsonError(exchange, 400, "recovery 文件里没有可恢复的工作区");
                return;   // finally 会释放许可
            }
            cm.restoreVolatileWorking(restored);

            // 准备日志文件
            try {
                logWriter = new LogFileWriter(session.getSessionId(), "[恢复任务]");
            } catch (IOException e) {
                System.err.println("⚠️ 无法创建日志文件: " + e.getMessage());
            }

            // 建立 SSE + 首条 session 事件
            sse = new SseTaskSession(exchange, session, logWriter, "Resume");
            sse.sendSessionEvent(false);

            // 启动 Agent（runResume，无 iteration listener）
            sse.start(
                    (agent, maxIter) -> agent.runResume(maxIter),
                    null,
                    maxIterations
            );

        } catch (Throwable t) {
            System.err.println("⚠️ /session/resume 建立 SSE 失败: " + t.getMessage());
            if (sse != null) {
                // 3-1 保留原行为：Resume 不发送 error 事件
                sse.abort(false, t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            } else if (logWriter != null) {
                try { logWriter.close(); } catch (IOException ignored) {}
            }
        } finally {
            if (sse == null || !sse.isPermitTransferred()) {
                sessionManager.releaseRunningPermit();
            }
        }
    }

    // @anchor: resumeHandler_readVolatileWorking
// 从 recovery 快照里切出 volatileWorking（跳过 immutableBase 部分）
    private List<Map<String, Object>> readVolatileWorkingFromRecovery(
            HistoryRecorder.RecoverySnapshot snapshot, int immutableBaseSize) {
        List<Map<String, Object>> messages = snapshot.messages();
        if (messages == null || messages.size() <= immutableBaseSize) return List.of();
        return new ArrayList<>(messages.subList(immutableBaseSize, messages.size()));
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