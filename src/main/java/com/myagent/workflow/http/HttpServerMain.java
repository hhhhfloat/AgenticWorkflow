package com.myagent.workflow.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.AgentConfig;
import com.myagent.workflow.core.ConfigEditor;
import com.myagent.workflow.core.Main;
import com.myagent.workflow.tools.ToolExecutor;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
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

    // @anchor: httpserver_exitCodes


    /** 用户主动清除 API Key，需要删除环境变量并重启 */
    public static final int EXIT_CODE_NO_API = 10;

    public static final int EXIT_CODE_CLEAR_API_KEY = 42;

    /** 用户修改了配置，需要重启（不删除环境变量） */
    public static final int EXIT_CODE_RESTART = 43;

    // 检测到有其他进程
    public static final int EXIT_CODE_EXISTED_THREAD = 12;

    // 在 HttpServerMain 类中
    private static volatile boolean apiKeyClearedByUser = false;
    public static boolean isApiKeyCleared() {

        return apiKeyClearedByUser;
    }

    // @anchor: httpserver_entry
    public static void main(String[] args) throws IOException {
        // ===== 1. 校验 API Key =====
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("❌ 未设置 DEEPSEEK_API_KEY 环境变量");
            System.exit(EXIT_CODE_NO_API);
        }
        if (!Main.checkApiKey(apiKey)) {
            System.err.println("❌ API Key 无效，请检查是否正确");
            System.exit(EXIT_CODE_NO_API);
        }


        // 确保运行时目录存在
        String[] requiredDirs = {"./sandbox", "./TestProjects", "./HistoryOutput"};
        for (String dir : requiredDirs) {
            Path p = Paths.get(dir);
            if (!Files.exists(p)) {
                Files.createDirectories(p);
                System.out.println("📁 已自动创建: " + dir);
            }
        }

        HttpServer server;

        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);

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
            server.createContext("/clear-api-key", new ClearApiKeyHandler());
            server.createContext("/restart", new RestartHandler());
            // 在 main() 方法中，其他 server.createContext 后面添加
            server.createContext("/project-meta", new ProjectMetaHandler());
            server.createContext("/runProject", new RunProjectHandler());


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
            System.out.println("服务已停止，重新启动以重新启动");
        } catch (Exception e) {
            System.exit(EXIT_CODE_EXISTED_THREAD);
        }
    }

    // ===================== 内部 Handler 类 =====================

    // ===================== 项目执行处理器 =====================
    static class RunProjectHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root;
            String filename, mode;

            try {
                root = mapper.readTree(body);
                filename = root.get("filename").asText();
                mode = root.get("mode").asText();
            } catch (Exception e) {
                sendJsonResponse(exchange, 400, "{\"error\":\"请求格式错误: " + e.getMessage() + "\"}");
                return;
            }

            if (filename == null || filename.isEmpty() || mode == null || mode.isEmpty()) {
                sendJsonResponse(exchange, 400, "{\"error\":\"缺少必要参数: filename, mode\"}");
                return;
            }

            // 安全检查：禁止路径穿越
            if (filename.contains("..")) {
                sendJsonResponse(exchange, 403, "{\"error\":\"路径非法\"}");
                return;
            }

            try {
                AgentConfig config = ConfigEditor.buildFromRequest(root);
                ToolExecutor executor = new ToolExecutor(config, new ObjectMapper());

                // ✅ 直接使用注册表中的参数，原样调用 compile_and_run
                Map<String, Object> args = new HashMap<>();
                args.put("filename", filename);
                args.put("mode", mode);
                args.put("run", true);

                String result = executor.dispatch("compile_and_run", args);

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("status", "success");
                response.put("output", result);
                String json = mapper.writeValueAsString(response);
                sendJsonResponse(exchange, 200, json);

            } catch (Exception e) {
                Map<String, Object> errorResponse = new LinkedHashMap<>();
                errorResponse.put("status", "error");
                errorResponse.put("error", e.getMessage());
                String json = mapper.writeValueAsString(errorResponse);
                sendJsonResponse(exchange, 500, json);
            }
        }
    }
    // ===================== 项目注册表查询处理器 =====================
    static class ProjectMetaHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            // 解析查询参数
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQuery(query);
            String path = params.get("path");

            if (path == null || path.isEmpty()) {
                sendJsonResponse(exchange, 400, "{\"error\":\"缺少 path 参数\"}");
                return;
            }

            // 安全检查：只允许 sandbox/ 和 TestProjects/
            if (!path.startsWith("sandbox/") && !path.startsWith("TestProjects/")) {
                sendJsonResponse(exchange, 403, "{\"error\":\"路径非法\"}");
                return;
            }

            // 防止路径穿越
            if (path.contains("..")) {
                sendJsonResponse(exchange, 403, "{\"error\":\"路径非法\"}");
                return;
            }

            Path projectDir = Paths.get(path).normalize();
            Path metaFile = projectDir.resolve(".agent_entry.json");

            if (Files.exists(metaFile) && Files.isRegularFile(metaFile)) {
                try {
                    String content = Files.readString(metaFile, StandardCharsets.UTF_8);
                    // 验证是否是有效的 JSON
                    new ObjectMapper().readTree(content);
                    sendJsonResponse(exchange, 200, content);
                } catch (Exception e) {
                    sendJsonResponse(exchange, 500, "{\"error\":\"读取注册表失败: " + e.getMessage() + "\"}");
                }
            } else {
                sendJsonResponse(exchange, 200, "{\"exists\":false}");
            }
        }
    }

    static class RestartHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String response = "{\"status\":\"success\", \"message\":\"配置已保存，程序即将重启...\"}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            exchange.close();

            new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {}
                System.out.println("🔄 配置已更新，正在重启...");
                System.exit(HttpServerMain.EXIT_CODE_RESTART);
            }).start();
        }
    }

    static class ClearApiKeyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String response = "{\"status\":\"success\", \"message\":\"API Key 已清除，程序即将重启...\"}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            exchange.close();

            // 延迟退出，确保响应已发送
            new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {}
                System.out.println("🔑 API Key 已清除，正在重启...");
                System.exit(HttpServerMain.EXIT_CODE_CLEAR_API_KEY);
            }).start();
        }
    }
    // @anchor: httpserver_runHandler
    static class RunHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // ===== 0. 提前检查 API Key 是否已被清除 =====
            if (HttpServerMain.isApiKeyCleared()) {
                String error = "{\"status\":\"error\", \"message\":\"API Key 已被清除，请设置环境变量 DEEPSEEK_API_KEY 后重启程序\"}";
                byte[] bytes = error.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(403, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
                exchange.close();
                return;
            }

            // 处理 OPTIONS 请求（CORS）
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

            // ===== 1. 解析请求体（此时 API Key 未被清除，可以安全解析） =====
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String userRequest;
            AgentConfig runConfig;
            int maxIterations;

            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(body);
                userRequest = root.get("prompt").asText();

                runConfig = ConfigEditor.buildFromRequest(root);

                maxIterations = AgentConfig.getDefaultMaxIterations();
                if (root.has("maxIterations")) {
                    int raw = root.get("maxIterations").asInt();
                    if (raw >= 3 && raw <= 50) {
                        maxIterations = raw;
                    }
                }

                appendHistory(userRequest);

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

            // ===== 2. 校验 API Key（直接从环境变量读取） =====
            String apiKey = System.getenv("DEEPSEEK_API_KEY");
            if (apiKey == null || apiKey.isEmpty()) {
                sendEvent(out, "[错误] 请设置环境变量 DEEPSEEK_API_KEY");
                out.close();
                exchange.close();
                return;
            }

            // 日志文件写入器
            LogFileWriter logWriter = null;
            try {
                logWriter = new LogFileWriter(userRequest);
            } catch (IOException e) {
                System.err.println("⚠️ 无法创建日志文件: " + e.getMessage());
            }

            // 创建 Agent 实例，传入配置
            Main agent = new Main(runConfig);

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
                    sendEvent(out, msg);
                    if (finalLogWriter != null) {
                        try {
                            finalLogWriter.write(msg);
                        } catch (IOException e) {
                            System.err.println("⚠️ 写入日志失败: " + e.getMessage());
                        }
                    }
                } catch(ClosedByInterruptException e){
                    System.out.println("⏹ 任务已停止，停止发送日志");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });

            final int finalMaxIterations = maxIterations;
            new Thread(() -> {
                try {
                    String result = agent.run(userRequest, finalMaxIterations);
                    sendEvent(out, "[完成] " + result);
                    sendEvent(out, "[结束]");
                    out.close();
                } catch(ClosedByInterruptException e){
                    try {
                        sendEvent(out, "[结束]");
                        out.close();
                    }catch (IOException ignored){

                    }
                } catch (Exception e) {
                    try {
                        sendEvent(out, "[错误] " + e.getMessage());
                        out.close();
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    }
                } finally {
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

        // 在 RunHandler 类中添加
        private void appendHistory(String userRequest) {
            try {
                Path historyFile = Paths.get("./HistoryOutput/history.jsonl");
                // 确保目录存在
                if (!Files.exists(historyFile.getParent())) {
                    Files.createDirectories(historyFile.getParent());
                }

                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("timestamp", LocalDateTime.now().toString());
                entry.put("prompt", userRequest);

                String jsonLine = mapper.writeValueAsString(entry) + System.lineSeparator();
                Files.writeString(historyFile, jsonLine, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);

            } catch (IOException e) {
                // 历史记录写入失败不影响主流程
                System.err.println("⚠️ 写入历史记录失败: " + e.getMessage());
            }
        }

        private void sendEvent(OutputStream out, String data) throws IOException {
            if(Thread.interrupted()){
                return;
            }
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
                    // 关键：将心跳时间置为 0，让监控线程立即判定超时
                    lastHeartbeatTime = 0;
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
        // 存储上次的哈希值（静态，跨请求保持）
        private static String lastSandboxHash = null;
        private static String lastTestProjectsHash = null;
        private int heartBeatCount = 0;

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            // 更新心跳时间（保持原有功能）
            synchronized (lock) {
                lastHeartbeatTime = System.currentTimeMillis();
            }

            // 检查目录变化
            String sbHash = computeHash("sandbox");
            String tpHash = computeHash("TestProjects");
            boolean needRefresh = false;

            // 只有两个目录都扫描完成才比较（避免空值误判）
            if (heartBeatCount++ % 4 == 0) {
                if (sbHash != null && tpHash != null) {
                    if (!Objects.equals(sbHash, lastSandboxHash) || !Objects.equals(tpHash, lastTestProjectsHash)) {
                        needRefresh = true;
                        lastSandboxHash = sbHash;
                        lastTestProjectsHash = tpHash;
                    }
                } else {
                    // 首次运行，只记录哈希，不触发刷新
                    if (sbHash != null) lastSandboxHash = sbHash;
                    if (tpHash != null) lastTestProjectsHash = tpHash;
                }
            }
            if(heartBeatCount == 1000000)heartBeatCount = 0;

            // 返回 JSON 响应
            String response = "{\"refresh\":" + needRefresh + "}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        private String computeHash(String dir) {
            Path target = Paths.get("./" + dir);
            if (!Files.exists(target) || !Files.isDirectory(target)) {
                return null;
            }
            try (Stream<Path> stream = Files.list(target)) {
                StringBuilder sb = new StringBuilder();
                stream.sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .forEach(p -> {
                            try {
                                sb.append(p.getFileName().toString());
                                sb.append("|");
                                sb.append(Files.getLastModifiedTime(p).toMillis());
                                sb.append(";");
                            } catch (IOException ignored) {}
                        });
                if (sb.length() == 0) {
                    return "";
                }
                return Integer.toHexString(sb.toString().hashCode());
            } catch (IOException e) {
                return null;
            }
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

    // ===================== 公共辅助方法 =====================

    /**
     * 解析查询字符串为 Map
     */
    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                params.put(kv[0], kv[1]);
            }
        }
        return params;
    }

    /**
     * 发送 JSON 响应
     */
    private static void sendJsonResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
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
