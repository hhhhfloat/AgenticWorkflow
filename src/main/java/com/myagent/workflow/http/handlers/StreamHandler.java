// @anchor: streamHandler_tot_desc
// 日志流处理器：GET /stream?sessionId=xxx，只读订阅会话实时日志
package com.myagent.workflow.http.handlers;

import com.myagent.workflow.core.AgentConfig;
import com.myagent.workflow.http.HttpServerMain;
import com.myagent.workflow.http.utils.HandlerUtils;
import com.myagent.workflow.session.Session;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

// @anchor: streamHandler_class
// 日志流处理器：订阅会话实时日志，任务结束后自动关闭
public class StreamHandler implements HttpHandler {

    private static final long PING_INTERVAL_MS = 15_000;
    private static final long CHECK_INTERVAL_MS = 2_000;

    // @anchor: streamHandler_handle
    // 处理订阅请求：建立 SSE，转发实时日志，任务结束后关闭
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        Map<String, String> params = HandlerUtils.parseQuery(exchange.getRequestURI().getQuery());
        String sessionId = params.get("sessionId");
        if (sessionId == null || sessionId.isEmpty()) {
            writeJson(exchange, 400, "{\"status\":\"error\",\"message\":\"缺少 sessionId\"}");
            return;
        }

        AgentConfig config = HttpServerMain.getGlobalConfig();
        Session session = HttpServerMain.getSessionManager().get(sessionId, config);
        if (session == null) {
            writeJson(exchange, 404, "{\"status\":\"error\",\"message\":\"会话不存在\"}");
            return;
        }

        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        OutputStream out = exchange.getResponseBody();

        // 消费者：从任务线程调用，写入 SSE 需要串行化
        final Consumer<String> consumer = msg -> {
            try {
                synchronized (out) {
                    out.write(("data: " + msg.replace("\n", "\\n") + "\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException ignored) {
                // 客户端断开
            }
        };

        try {
            // 历史补发由前端通过 /session/logs 拉取，这里只订阅新日志
            session.addLogConsumer(consumer);
            // 保活 + 结束检测
            // 初始等待：避免"订阅时任务尚未 markRunning"导致的秒退
            if (!session.isRunning()) {
                Thread.sleep(5000);
                if (!session.isRunning()) {
                    synchronized (out) {
                        out.write("data: [stream-end]\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    return;
                }
            }

            // 保活 + 结束检测 + 最大生命周期（30 分钟）
            long startedAt = System.currentTimeMillis();
            long lastPing = System.currentTimeMillis();
            final long MAX_LIFETIME_MS = 30 * 60 * 1000L;

            while (true) {
                Thread.sleep(CHECK_INTERVAL_MS);
                if (!session.isRunning()) {
                    synchronized (out) {
                        out.write("data: [stream-end]\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    break;
                }
                long now = System.currentTimeMillis();
                if (now - startedAt >= MAX_LIFETIME_MS) {
                    synchronized (out) {
                        out.write("data: [stream-timeout]\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    break;
                }
                if (now - lastPing >= PING_INTERVAL_MS) {
                    synchronized (out) {
                        out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    lastPing = now;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // 客户端断开
        } finally {
            session.removeLogConsumer(consumer);
            try { out.close(); } catch (IOException ignored) {}
        }
    }

    // @anchor: streamHandler_writeJson
    // SSE 建立前以 JSON 返回错误
    private void writeJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}