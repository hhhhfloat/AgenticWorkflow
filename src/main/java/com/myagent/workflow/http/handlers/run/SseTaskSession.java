// @anchor: sseTaskSession_intro
// SSE 任务会话：封装建立 SSE、启动 ping、运行 Agent、收尾清理的完整生命周期
package com.myagent.workflow.http.handlers.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.Main;
import com.myagent.workflow.http.HttpServerMain;
import com.myagent.workflow.http.LogFileWriter;
import com.myagent.workflow.session.Session;
import com.myagent.workflow.session.SessionManager;
import com.myagent.workflow.session.SessionUsage;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

// @anchor: sseTaskSession_class
// SSE 任务会话：供 RunHandler 与 ResumeHandler 共用的 SSE 生命周期封装
public class SseTaskSession {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Session session;
    private final String sessionId;
    private final String tagPrefix;
    private final LogFileWriter logWriter;
    private final OutputStream out;
    private final Object outLock = new Object();
    private final SseLogSink logSink;
    private final Thread pingThread;

    private boolean started = false;
    private volatile boolean permitTransferred = false;

    // @anchor: sseTaskSession_constructor
    // 建立 SSE 响应：设置头、取输出流、创建 sink 与 ping 线程（未启动）
    public SseTaskSession(HttpExchange exchange, Session session,
                          LogFileWriter logWriter, String tagPrefix) throws IOException {
        this.session = session;
        this.sessionId = session.getSessionId();
        this.tagPrefix = tagPrefix;
        this.logWriter = logWriter;

        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);
        this.out = exchange.getResponseBody();

        this.logSink = new SseLogSink(out, outLock, sessionId, logWriter);

        this.pingThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(15_000);
                    synchronized (outLock) {
                        out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {}
        }, tagPrefix + "Ping-" + sessionId);
        this.pingThread.setDaemon(true);
    }

    // @anchor: sseTaskSession_sendSessionEvent
    // 首条事件：告知前端本次会话号与 isNew
    public void sendSessionEvent(boolean isNew) throws IOException {
        synchronized (outLock) {
            String json = "{\"type\":\"session\",\"sessionId\":\"" + sessionId
                    + "\",\"isNew\":" + isNew + "}";
            sendEvent(out, json);
        }
    }

    // @anchor: sseTaskSession_sendControl
    // 供外部（如 iteration listener）发送控制事件
    public void sendControl(String json) {
        logSink.sendControl(json);
    }

    // @anchor: sseTaskSession_start
    // 启动 Agent 执行线程：绑定 sink、创建 Main、启动 ping、异步 run
    public void start(AgentExecutor executor, Consumer<Main> agentSetup, int maxIterations) {
        if (started) throw new IllegalStateException("SseTaskSession already started");
        started = true;

        session.addLogSink(logSink);

        Main agent = new Main(session);
        if (agentSetup != null) {
            agentSetup.accept(agent);
        }

        pingThread.start();

        new Thread(() -> {
            String result = null;
            Throwable error = null;
            try {
                result = executor.run(agent, maxIterations);
            } catch (Throwable t) {
                error = t;
            }

            try {
                // usage 落库
                Main.TaskUsage usage = agent.getLastTaskUsage();
                if (usage != null) {
                    try {
                        SessionUsage delta = new SessionUsage(
                                usage.promptTokens(), usage.cachedTokens(), usage.completionTokens(),
                                usage.apiCalls(), usage.cost());
                        SessionUsage total = sessionManager().appendUsage(sessionId, delta);
                        String usageJson = String.format(
                                "{\"type\":\"usage\",\"sessionId\":\"%s\",\"promptTokens\":%d,\"cachedTokens\":%d," +
                                        "\"completionTokens\":%d,\"apiCalls\":%d,\"cost\":%.6f}",
                                sessionId,
                                total.promptTokens(), total.cachedTokens(), total.completionTokens(),
                                total.apiCalls(), total.cost());
                        logSink.sendControl(usageJson);
                    } catch (Exception ignored) {}
                }

                // 结束事件
                if (error != null) {
                    String msg = error.getMessage() != null
                            ? error.getMessage() : error.getClass().getSimpleName();
                    logSink.sendControl(jsonEvent("error",
                            Map.of("message", msg, "sessionId", sessionId)));
                } else {
                    logSink.sendControl(jsonEvent("done", Map.of("summary", result == null ? "" : result)));
                }
                logSink.sendControl("{\"type\":\"end\"}");
            } finally {
                pingThread.interrupt();
                logSink.flush(3000);
                session.removeLogSink(logSink);
                logSink.close();
                if (logWriter != null) {
                    try { logWriter.close(); } catch (IOException ignored) {}
                }
                sessionManager().releaseRunningPermit();
                sessionManager().save(session);
                try { out.close(); } catch (IOException ignored) {}
            }
        }, tagPrefix + "Runner-" + sessionId).start();

        permitTransferred = true;
    }

    // @anchor: sseTaskSession_abort
    // SSE 建立过程或启动失败后的清理：可选发送 error 事件
    public void abort(boolean sendError, String errorMessage) {
        if (permitTransferred) return;
        pingThread.interrupt();
        session.removeLogSink(logSink);
        if (sendError) {
            try {
                logSink.sendControl(jsonEvent("error",
                        Map.of("message", errorMessage != null ? errorMessage : "unknown")));
                logSink.flush(1000);
            } catch (Exception ignored) {}
        }
        logSink.close();
        if (logWriter != null) {
            try { logWriter.close(); } catch (IOException ignored) {}
        }
        try { out.close(); } catch (IOException ignored) {}
    }

    // @anchor: sseTaskSession_isPermitTransferred
    public boolean isPermitTransferred() {
        return permitTransferred;
    }

    // @anchor: sseTaskSession_sessionManager
    private SessionManager sessionManager() {
        return HttpServerMain.getSessionManager();
    }

    // @anchor: sseTaskSession_jsonEvent
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

    // @anchor: sseTaskSession_sendEvent
    private static void sendEvent(OutputStream out, String data) throws IOException {
        String normalized = data.replace("\r\n", "\n").replace("\r", "\n");
        StringBuilder sb = new StringBuilder();
        for (String line : normalized.split("\n", -1)) {
            sb.append("data: ").append(line).append('\n');
        }
        sb.append('\n');
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // @anchor: sseTaskSession_agentExecutor
    // 参数化 Agent 执行入口：run(...) 或 runResume(...)
    @FunctionalInterface
    public interface AgentExecutor {
        String run(Main agent, int maxIterations) throws Exception;
    }
}