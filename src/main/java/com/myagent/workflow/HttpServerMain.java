package com.myagent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;

/**
 * @anchor: httpserver_class
 * HTTP 服务入口 —— 提供 Web 界面并托管 Agent 的 REST API。
 * 处理器类（ProjectsHandler / StaticHandler / ExternalFileHandler）已提取至 Handlers.java。
 */
public class HttpServerMain {
    // @anchor: httpserver_config
    private static final int PORT = 8080;
    private static final long HEARTBEAT_TIMEOUT_MS = 120000;
    private static final long HEARTBEAT_CHECK_INTERVAL_MS = 5000;

    // @anchor: httpserver_state
    private static volatile long lastHeartbeatTime = System.currentTimeMillis();
    private static volatile boolean isHeartbeatMonitorRunning = false;
    private static volatile Main currentAgent = null;
    private static final Object lock = new Object();

    // @anchor: httpserver_entry
    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);

        // 业务接口
        server.createContext("/run", new RunHandler());
        server.createContext("/stop", new StopHandler());
        server.createContext("/heartbeat", new HeartbeatHandler());
        server.createContext("/projects", new Handlers.ProjectsHandler());

        // 静态资源
        server.createContext("/", new Handlers.StaticHandler());

        // 外部项目目录挂载
        Path testProjectsDir = Paths.get("./TestProjects");
        if (Files.exists(testProjectsDir)) {
            server.createContext("/TestProjects", new Handlers.ExternalFileHandler(testProjectsDir));
            System.out.println("📁 已挂载外部目录: ./TestProjects -> http://localhost:" + PORT + "/TestProjects");
        } else {
            System.out.println("⚠️ 未找到 ./TestProjects 目录，项目入口功能将不可用（可手动创建）");
        }

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        // 自动打开浏览器
        try {
            String url = "http://localhost:" + PORT;
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
                System.out.println("🌐 已自动打开浏览器: " + url);
            } else {
                System.out.println("请手动打开浏览器访问: " + url);
            }
        } catch (Exception e) {
            System.out.println("⚠️ 自动打开浏览器失败，请手动访问 http://localhost:" + PORT);
        }

        System.out.println("🚀 Agent 服务已启动（按 Enter 停止...）");
        startHeartbeatMonitor();
        System.in.read();
        server.stop(0);
    }

    // ===================== 内部 Handler 类 =====================

    // @anchor: httpserver_runHandler
    static class RunHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
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

            // SSE 响应头
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);

            OutputStream out = exchange.getResponseBody();

            String apiKey = System.getenv("DEEPSEEK_API_KEY");
            if (apiKey == null || apiKey.isEmpty()) {
                sendEvent(out, "[错误] 请设置环境变量 DEEPSEEK_API_KEY");
                out.close();
                exchange.close();
                return;
            }

            Main agent = new Main(apiKey);
            synchronized (lock) {
                if (currentAgent != null) {
                    currentAgent.stop();
                }
                currentAgent = agent;
                lastHeartbeatTime = System.currentTimeMillis();
            }

            agent.setLogConsumer(msg -> {
                try {
                    sendEvent(out, msg);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });

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
            String event = "data: " + data.replace("\n", "\\n") + "\n\n";
            out.write(event.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    // @anchor: httpserver_stopHandler
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

    // @anchor: httpserver_heartbeatHandler
    static class HeartbeatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            synchronized (lock) {
                lastHeartbeatTime = System.currentTimeMillis();
            }
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        }
    }

    // ===================== 心跳监控 =====================

    // @anchor: httpserver_heartbeatMonitor
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
}
