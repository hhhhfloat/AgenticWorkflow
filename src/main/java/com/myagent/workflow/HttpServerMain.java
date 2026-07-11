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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

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

        // 确保运行时目录存在
        String[] requiredDirs = {"./sandbox", "./TestProjects", "./HistoryOutput"};
        for (String dir : requiredDirs) {
            Path p = Paths.get(dir);
            if (!Files.exists(p)) {
                Files.createDirectories(p);
                System.out.println("📁 已自动创建: " + dir);
            }
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);

        // 业务接口
        server.createContext("/run", new RunHandler());
        server.createContext("/stop", new StopHandler());
        server.createContext("/heartbeat", new HeartbeatHandler());
        server.createContext("/projects", new Handlers.ProjectsHandler());
        server.createContext("/browse", new Handlers.BrowseHandler());
        server.createContext("/archive", new Handlers.ArchiveHandler());
        server.createContext("/upload", new Handlers.UploadHandler());
        server.createContext("/createProject", new Handlers.CreateProjectHandler());
        // 替换原来的 /openSandbox 为 /openFolder
        server.createContext("/openFolder", new Handlers.OpenFolderHandler());
        server.createContext("/config", new Handlers.ConfigHandler());

        // 静态资源
        server.createContext("/", new Handlers.StaticHandler());

        // 外部项目目录挂载
        Path testProjectsDir = Paths.get("./TestProjects");
        server.createContext("/TestProjects", new Handlers.ExternalFileHandler(testProjectsDir, "/TestProjects"));
        System.out.println("📁 已挂载外部目录: ./TestProjects -> http://localhost:" + PORT + "/TestProjects");

// 沙箱目录挂载
        Path sandboxDir = Paths.get("./sandbox");
        server.createContext("/sandbox", new Handlers.ExternalFileHandler(sandboxDir, "/sandbox"));
        System.out.println("📁 已挂载沙箱目录: ./sandbox -> http://localhost:" + PORT + "/sandbox");

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

            int maxIterations = AgentConfig.MAX_ITERATIONS;

            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(body);
                userRequest = root.get("prompt").asText();

                // 新增：读取 maxIterations，若无则使用默认值
                if (root.has("maxIterations")) {
                    maxIterations = root.get("maxIterations").asInt();
                    // 安全限制：1~50
                    if (maxIterations < 3) maxIterations = 3;
                    if (maxIterations > 50) maxIterations = 50;
                }

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

            // 新增：日志文件写入器
            LogFileWriter logWriter = null;
            try {
                logWriter = new LogFileWriter(userRequest);
            } catch (IOException e) {
                // 日志文件创建失败不影响主流程
                System.err.println("⚠️ 无法创建日志文件: " + e.getMessage());
            }

            Main agent = new Main(apiKey);
            synchronized (lock) {
                if (currentAgent != null) {
                    currentAgent.stop();
                }
                currentAgent = agent;
                lastHeartbeatTime = System.currentTimeMillis();
            }


            final LogFileWriter finalLogWriter = logWriter;
            agent.setLogConsumer(msg -> {
                try {
                    // 1. 发送到前端（原有逻辑）
                    sendEvent(out, msg);
                    // 2. 写入日志文件
                    if (finalLogWriter != null) {
                        try {
                            finalLogWriter.write(msg);
                        } catch (IOException e) {
                            System.err.println("⚠️ 写入日志失败: " + e.getMessage());
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });

            int finalMaxIterations = maxIterations;
            new Thread(() -> {
                try {
                    String result = agent.run(userRequest, finalMaxIterations);
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
                    // 关闭日志文件
                    if (finalLogWriter != null) {
                        try {
                            finalLogWriter.close();
                            System.out.println("📝 日志已保存: " + finalLogWriter.getLogFilePath());
                        } catch (IOException e) {
                            System.err.println("⚠️ 关闭日志文件失败: " + e.getMessage());
                        }
                    }

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

/**
 * 日志持久化工具：将 SSE 流写入本地文件
 */
class LogFileWriter implements AutoCloseable {
    private final Path logFile;
    private final BufferedWriter writer;

    public LogFileWriter(String prompt) throws IOException {
        // 创建 HistoryOutput 目录
        Path logDir = Paths.get("./HistoryOutput");
        if (!Files.exists(logDir)) {
            Files.createDirectories(logDir);
        }

        cleanOldLogs();

        // 生成文件名：2026-07-11_14-23-45.log
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        logFile = logDir.resolve(timestamp + ".log");
        writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8);

        // 写入提示词作为第一行
        writer.write("📝 本次需求: " + prompt);
        writer.newLine();
        writer.write("--- 开始执行 ---");
        writer.newLine();
        writer.flush();
    }

    private static void cleanOldLogs() throws IOException {
        Path logDir = Paths.get("./HistoryOutput");
        if (!Files.exists(logDir)) return;

        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        try (Stream<Path> files = Files.list(logDir)) {
            files.filter(p -> p.toString().endsWith(".log"))
                    .forEach(p -> {
                        try {
                            // 从文件名解析日期（格式：2026-07-11_14-23-45.log）
                            String name = p.getFileName().toString();
                            String datePart = name.substring(0, 10); // "2026-07-11"
                            LocalDateTime fileDate = LocalDateTime.parse(datePart + "T00:00:00");
                            if (fileDate.isBefore(cutoff)) {
                                Files.delete(p);
                                System.out.println("🗑️ 已删除旧日志: " + p.getFileName());
                            }
                        } catch (Exception ignored) {}
                    });
        }
    }

    public void write(String message) throws IOException {
        writer.write(message);
        writer.newLine();
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        if (writer != null) {
            writer.close();
        }
    }

    public Path getLogFilePath() {
        return logFile;
    }
}
