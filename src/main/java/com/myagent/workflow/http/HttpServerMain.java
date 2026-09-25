// @anchor: httpServerMain_tot_desc
// HTTP 服务入口：校验 API Key、初始化配置与会话、注册路由并启动服务
package com.myagent.workflow.http;

import com.myagent.workflow.core.AgentConfig;
import com.myagent.workflow.core.ConfigEditor;
import com.myagent.workflow.core.Main;
import com.myagent.workflow.http.handlers.*;
import com.myagent.workflow.session.SessionManager;
import com.sun.net.httpserver.HttpHandler;
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

    // @anchor: httpServerMain_mobileLock
    /** 手机独占锁：一旦手机发起请求即置位，服务重启才清除 */
    private static volatile boolean mobileLocked = false;

    public static void lockForMobile() { mobileLocked = true; }
    public static boolean isMobileLocked() { return mobileLocked; }

    // @anchor: httpServerMain_reg
    /**
     * 注册路由并包装设备保护。
     * protect=true：手机锁定期间，来自桌面端的请求返回 403。
     */
    private static void reg(HttpServer server, String path, HttpHandler handler, boolean protect) {
        server.createContext(path, new GuardedHandler(handler, protect));
    }

    /** 默认绑定：仅本机可访问 */
    public static final String DEFAULT_BIND = "127.0.0.1";
    /** 环境变量 key：设置后覆盖默认绑定地址（例如 Tailscale IP） */
    public static final String BIND_ENV_KEY = "AGENT_BIND";

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

    // @anchor: httpServerMain_resolveBindAddress
    /**
     * 解析 HTTP 服务绑定地址：
     * - 环境变量 AGENT_BIND 优先（如 Tailscale 的 100.x.x.x）
     * - 未设置时默认 127.0.0.1（仅本机可访问）
     */
    private static String resolveBindAddress() {
        String v = System.getenv(BIND_ENV_KEY);
        if (v == null || v.isBlank()) return DEFAULT_BIND;
        return v.trim();
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
            String bindAddr = resolveBindAddress();
            server = HttpServer.create(new InetSocketAddress(bindAddr, PORT), 0);
            registerRoutes(server);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();

            openBrowser(bindAddr);
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
        // ── 会话管理（写：保护） ──
        reg(server, "/session/create", new SessionCreateHandler(), true);
        reg(server, "/session/close",  new SessionCloseHandler(), true);
        reg(server, "/session/rename", new SessionRenameHandler(), true);
        // ── 会话管理（读：放行） ──
        reg(server, "/session/list",    new SessionListHandler(), false);
        reg(server, "/session/history", new SessionHistoryHandler(), false);

        // ── 任务执行 ──
        reg(server, "/run",  new RunHandler(), true);
        reg(server, "/stop", new StopHandler(), true);

        // ── 心跳 / 状态（放行） ──
        reg(server, "/heartbeat", new HeartbeatHandler(), false);
        reg(server, "/status",    new StatusHandler(), false);
        reg(server, "/lock-status", new LockStatusHandler(), false);   // 新增

        // ── 项目操作 ──
        reg(server, "/runProject",   new RunProjectHandler(), true);
        reg(server, "/project-meta", new ProjectMetaHandler(), false);

        // ── 系统操作（保护） ──
        reg(server, "/restart",       new RestartHandler(), true);
        reg(server, "/clear-api-key", new ClearApiKeyHandler(), true);
        reg(server, "/config",        new ConfigHandler(), true);
        reg(server, "/switch-to-local", new SwitchToLocalHandler(), false);

        // ── 项目/文件管理（保护） ──
        reg(server, "/projects",      new ProjectsHandler(), false);
        reg(server, "/browse",        new BrowseHandler(), false);
        reg(server, "/archive",       new ArchiveHandler(), true);
        reg(server, "/upload",        new UploadHandler(), true);
        reg(server, "/createProject", new CreateProjectHandler(), true);
        reg(server, "/openFolder",    new OpenFolderHandler(), true);

        // ── 静态资源（放行） ──
        reg(server, "/", new StaticHandler(), false);
        reg(server, "/TestProjects",
                new ExternalFileHandler(Paths.get("./TestProjects"), "/TestProjects"), false);
        reg(server, "/sandbox",
                new ExternalFileHandler(Paths.get("./sandbox"), "/sandbox"), false);
    }

    // @anchor: httpServerMain_openBrowser
    // 启动后尝试用系统默认浏览器打开首页，失败则提示手动访问
    private static void openBrowser(String bindAddr) {
        try {
            String path = "127.0.0.1".equals(bindAddr) ? "/" : "/qr.html";
            String url = "http://" + bindAddr + ":" + PORT + path;
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
                System.out.println("🌐 已自动打开浏览器: " + url);
            }
        } catch (Exception e) {
            System.out.println("⚠️ 自动打开浏览器失败，请手动访问 http://" + bindAddr + ":" + PORT);
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
