// @anchor: httpServerMain_tot_desc
// HTTP 服务入口：校验 API Key、初始化配置与会话、注册路由并启动服务
package com.myagent.workflow.http;

import com.myagent.workflow.core.AgentConfig;
import com.myagent.workflow.core.ConfigEditor;
import com.myagent.workflow.core.Main;
import com.myagent.workflow.http.handlers.*;
import com.myagent.workflow.session.SessionManager;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;

// @anchor: httpServerMain_class
// HTTP 服务主类：维护会话管理器/全局配置单例，注册全部路由与心跳监控
public class HttpServerMain {

    // ==================== 端口与超时 ====================

    public static final int PORT = 8080;
    public static final long HEARTBEAT_TIMEOUT_MS = 120_000;
    public static final long HEARTBEAT_CHECK_INTERVAL_MS = 5_000;
    public static final int MAX_ITERATIONS = 100;

    // ==================== 退出码 ====================

    public static final int EXIT_CODE_NO_API = 10;
    public static final int EXIT_CODE_CLEAR_API_KEY = 42;
    public static final int EXIT_CODE_RESTART = 43;
    public static final int EXIT_CODE_EXISTED_THREAD = 12;

    // ==================== 全局单例 ====================

    // @anchor: httpServerMain_singletons
    // 全局单例与运行标志：会话管理器、配置、API Key 清除状态、心跳监控开关
    private static volatile SessionManager sessionManager;
    private static volatile AgentConfig globalConfig;
    private static volatile boolean apiKeyClearedByUser = false;
    private static volatile boolean heartbeatMonitorRunning = false;

    public static SessionManager getSessionManager() {
        return sessionManager;
    }

    public static AgentConfig getGlobalConfig() {
        return globalConfig;
    }

    public static boolean isApiKeyCleared() {
        return apiKeyClearedByUser;
    }

    public static void markApiKeyCleared() {
        apiKeyClearedByUser = true;
    }

    // ==================== 入口 ====================

    // @anchor: httpServerMain_main
    // 服务主流程：校验 API Key、建运行时目录、初始化会话并启动 HTTP 服务
    public static void main(String[] args) throws IOException {
        // 1. 校验 API Key
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("❌ 未设置 DEEPSEEK_API_KEY 环境变量");
            System.exit(EXIT_CODE_NO_API);
        }
        if (!Main.checkApiKey(apiKey)) {
            System.err.println("❌ API Key 无效，请检查是否正确");
            System.exit(EXIT_CODE_NO_API);
        }

        // 2. 初始化全局配置
        globalConfig = ConfigEditor.buildDefault();

        // 3. 确保运行时目录存在
        for (String dir : new String[]{"./sandbox", "./TestProjects", "./HistoryOutput", "./temp", "./sessions"}) {
            Path p = Paths.get(dir);
            if (!Files.exists(p)) {
                Files.createDirectories(p);
                System.out.println("📁 已自动创建: " + dir);
            }
        }

        // 4. 初始化 SessionManager
        sessionManager = new SessionManager();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (sessionManager != null) sessionManager.shutdown();
        }));

        // 5. 启动 HTTP 服务
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            registerRoutes(server);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();

            openBrowser();
            System.out.println("🚀 Agent 服务已启动（按 Enter 停止...）");
            startHeartbeatMonitor();
            System.in.read();
            server.stop(0);
            System.out.println("服务已停止。");
        } catch (Exception e) {
            System.err.println("服务启动失败: " + e.getMessage());
            System.exit(EXIT_CODE_EXISTED_THREAD);
        }
    }

    // ==================== 路由注册 ====================

    // @anchor: httpServerMain_registerRoutes
    // 注册全部 HTTP 路由：会话、任务、项目、系统、静态资源与外部目录
    private static void registerRoutes(HttpServer server) {
        // ── 会话管理 ──
        server.createContext("/session/create", new SessionCreateHandler());
        server.createContext("/session/list", new SessionListHandler());
        server.createContext("/session/close", new SessionCloseHandler());
        server.createContext("/session/history", new SessionHistoryHandler());
        server.createContext("/session/rename", new SessionRenameHandler());

        // ── 任务执行 ──
        server.createContext("/run", new RunHandler());
        server.createContext("/stop", new StopHandler());
        server.createContext("/heartbeat", new HeartbeatHandler());
        server.createContext("/status", new StatusHandler());

        // ── 项目操作（保留原有语义） ──
        server.createContext("/runProject", new RunProjectHandler());
        server.createContext("/project-meta", new ProjectMetaHandler());

        // ── 系统操作 ──
        server.createContext("/restart", new RestartHandler());
        server.createContext("/clear-api-key", new ClearApiKeyHandler());
        server.createContext("/config", new ConfigHandler());

        // ── 项目/文件管理（原有） ──
        server.createContext("/projects", new ProjectsHandler());
        server.createContext("/browse", new BrowseHandler());
        server.createContext("/archive", new ArchiveHandler());
        server.createContext("/upload", new UploadHandler());
        server.createContext("/createProject", new CreateProjectHandler());
        server.createContext("/openFolder", new OpenFolderHandler());

        // ── 静态资源 + 外部目录 ──
        server.createContext("/", new StaticHandler());
        server.createContext("/TestProjects",
                new ExternalFileHandler(Paths.get("./TestProjects"), "/TestProjects"));
        server.createContext("/sandbox",
                new ExternalFileHandler(Paths.get("./sandbox"), "/sandbox"));
    }

    // @anchor: httpServerMain_openBrowser
    // 启动后尝试用系统默认浏览器打开首页，失败则提示手动访问
    private static void openBrowser() {
        try {
            String url = "http://localhost:" + PORT;
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
                System.out.println("🌐 已自动打开浏览器: " + url);
            }
        } catch (Exception e) {
            System.out.println("⚠️ 自动打开浏览器失败，请手动访问 http://localhost:" + PORT);
        }
    }

    // ==================== 心跳监控 ====================

    // @anchor: httpServerMain_startHeartbeatMonitor
    // 启动心跳监控线程：超时会话自动停止任务
    private static void startHeartbeatMonitor() {
        if (heartbeatMonitorRunning) return;
        heartbeatMonitorRunning = true;

        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(HEARTBEAT_CHECK_INTERVAL_MS);
                    long now = System.currentTimeMillis();
                    for (var session : sessionManager.listActive()) {
                        if (!session.isRunning()) continue;
                        long elapsed = now - session.getLastHeartbeatTime();
                        if (elapsed > HEARTBEAT_TIMEOUT_MS) {
                            System.out.println("⚠️ 会话 " + session.getSessionId()
                                    + " 心跳超时，停止任务");
                            session.stopTask();
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
