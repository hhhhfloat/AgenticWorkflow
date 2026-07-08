package com.myagent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public class HttpServerMain {
    private static final int PORT = 8080;

    private static final long HEARTBEAT_TIMEOUT_MS = 40000; // 15 秒未收到心跳则判定断开
    private static final long HEARTBEAT_CHECK_INTERVAL_MS = 5000; // 每 5 秒检查一次
    private static volatile long lastHeartbeatTime = System.currentTimeMillis();
    private static volatile boolean isHeartbeatMonitorRunning = false;

    // 存储当前正在运行的 Agent（只支持单任务，简单场景）
    private static volatile Main currentAgent = null;
    private static final Object lock = new Object();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/run", new RunHandler());
        server.createContext("/stop", new StopHandler());
        server.createContext("/heartbeat", new HeartbeatHandler()); // ← 新增心跳端点
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("🚀 Agent 服务已启动，请双击打开 index.html（本机 " + PORT + " 端口）");
        System.out.println("按 Enter 停止服务...");
        startHeartbeatMonitor();
        System.in.read();
        server.stop(0);
    }

    static class RunHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // 处理 OPTIONS 预检请求（解决跨域）
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

            // 读取请求体（JSON）
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String userRequest;
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(body);
                userRequest = root.get("prompt").asText();
            } catch (Exception e) {
                String err = "请求格式错误: " + e.getMessage();
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(400, err.getBytes().length);
                exchange.getResponseBody().write(err.getBytes());
                exchange.close();
                return;
            }

            // 设置 SSE 响应头
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);

            OutputStream out = exchange.getResponseBody();

            // 获取 API Key
            String apiKey = System.getenv("DEEPSEEK_API_KEY");
            if (apiKey == null || apiKey.isEmpty()) {
                sendEvent(out, "[错误] 请设置环境变量 DEEPSEEK_API_KEY");
                out.close();
                exchange.close();
                return;
            }

            Main agent = new Main(apiKey);
            synchronized (lock) {
                // 如果已有 Agent 在运行，先停止它（可选）
                if (currentAgent != null) {
                    currentAgent.stop();
                }
                currentAgent = agent;
                lastHeartbeatTime = System.currentTimeMillis();
            }
            // 设置日志回调 → 每条日志发送 SSE 事件
            agent.setLogConsumer(msg -> {
                try {
                    sendEvent(out, msg);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });

            // 异步执行 Agent，避免阻塞 HTTP 响应
            new Thread(() -> {
                try {
                    String result = agent.run(userRequest);
                    sendEvent(out, "[完成] " + result);
                    sendEvent(out, "[结束]");
                    out.close();
                } catch (Exception e) {
                    try {
                        sendEvent(out, "[错误] " + e.getMessage());
                        out.close();
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    }
                } finally {
                    synchronized (lock) {
                        if (currentAgent == agent) {
                            currentAgent = null;
                        }
                    }
                }
            }).start();
        }

        private void sendEvent(OutputStream out, String data) throws IOException {
            // SSE 格式：data: 内容\n\n
            String event = "data: " + data.replace("\n", "\\n") + "\n\n";
            out.write(event.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    static class StopHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Content-Type", "application/json");

            String response;
            synchronized (lock) {
                if (currentAgent != null) {
                    currentAgent.stop();
                    currentAgent = null;
                    lastHeartbeatTime = System.currentTimeMillis();
                    response = "{\"status\":\"stopped\", \"message\":\"已发送停止信号\"}";
                } else {
                    response = "{\"status\":\"idle\", \"message\":\"没有正在运行的任务\"}";
                }
            }

            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }

    // 启动心跳监控线程（单例）
    private static void startHeartbeatMonitor() {
        if (isHeartbeatMonitorRunning) return;
        isHeartbeatMonitorRunning = true;
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(HEARTBEAT_CHECK_INTERVAL_MS);
                    long now = System.currentTimeMillis();
                    synchronized (lock) {
                        if (currentAgent != null) {
                            long elapsed = now - lastHeartbeatTime;
                            if (elapsed > HEARTBEAT_TIMEOUT_MS) {
                                System.out.println("⚠️ 心跳超时，前端已断开，正在停止 Agent...");
                                currentAgent.stop();
                                currentAgent = null;
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "HeartbeatMonitor").start();
    }

    // 心跳处理端点
    static class HeartbeatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            // 更新心跳时间
            synchronized (lock) {
                lastHeartbeatTime = System.currentTimeMillis();
            }
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        }
    }
}