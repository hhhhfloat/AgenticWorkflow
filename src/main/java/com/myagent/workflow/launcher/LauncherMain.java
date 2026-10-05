// @anchor: launcherMain_tot_desc
// 守护启动器：常驻 8081，按需拉起/停止同 jar 内的主服务
package com.myagent.workflow.launcher;

import com.myagent.workflow.core.config.BindAddressResolver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// @anchor: launcherMain_class
// 启动器主类：管理主服务子进程，暴露 /start /stop /status 三个 HTTP 接口
public class LauncherMain {

    // @anchor: launcherMain_constants
    // 端口、绑定地址与主服务就绪探测参数
    public static final int LAUNCHER_PORT = 8081;
    public static final int MAIN_PORT = 8080;
    public static final String BIND_ENV_KEY = BindAddressResolver.BIND_ENV_KEY;
    public static final String DEFAULT_BIND = BindAddressResolver.DEFAULT_BIND;
    private static final long READY_TIMEOUT_MS = 30_000;
    private static final long READY_POLL_MS = 500;

    // @anchor: launcherMain_state
    // 子进程引用与最近一次错误信息
    private static Process mainProcess = null;
    private static String lastError = null;
    private static String launcherBindAddress = null;

    // @anchor: launcherMain_page
    // 按钮页内容：启动时从 classpath 读入，避免把 HTML 塞进 Java 源码
    private static final String LAUNCHER_PAGE = loadLauncherPage();

    // @anchor: launcherMain_loadLauncherPage
    // 从 resources/launcher-page.html 读取，缺失时回退到极简提示页
    private static String loadLauncherPage() {
        try (InputStream is = LauncherMain.class.getResourceAsStream("/launcher-page.html")) {
            if (is == null) {
                return "<h1>launcher-page.html 缺失</h1>";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<h1>launcher-page.html 加载失败: " + e.getMessage() + "</h1>";
        }
    }

    // @anchor: launcherMain_main
    // 启动守护服务，绑定地址并注册路由
    public static void main(String[] args) throws IOException {
        launcherBindAddress = BindAddressResolver.resolve();
        String bindAddr = launcherBindAddress;
        HttpServer server = HttpServer.create(new InetSocketAddress(bindAddr, LAUNCHER_PORT), 0);

        server.createContext("/start", LauncherMain::handleStart);
        server.createContext("/stop", LauncherMain::handleStop);
        server.createContext("/status", LauncherMain::handleStatus);

        // 首页返回按钮页，其余路径 404
        server.createContext("/", exchange -> {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path) || "/index.html".equals(path)) {
                byte[] body = LAUNCHER_PAGE.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
                return;
            }
            writeJson(exchange, 404, "{\"status\":\"error\",\"message\":\"not found\"}");
        });

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (mainProcess != null && mainProcess.isAlive()) {
                try { mainProcess.destroyForcibly(); } catch (Exception ignored) {}
            }
        }));

        System.out.println("🚀 Launcher 已启动: http://" + bindAddr + ":" + LAUNCHER_PORT);
        System.out.println("   主服务目标: http://" + bindAddr + ":" + MAIN_PORT);
    }

    // @anchor: launcherMain_handleStart
    // 拉起主服务：已在跑则直接返回，否则 ProcessBuilder 启动并轮询就绪
    private static void handleStart(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        // 只接受 POST，防止 <img src> 之类的跨站 GET 触发启动
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        if (isMainRunning()) {
            writeJson(exchange, 200, "{\"status\":\"already_running\"}");
            return;
        }

        // 端口已被别的实例占用（比如用户手动 start.bat）
        if (probeMainPort()) {
            writeJson(exchange, 200,
                    "{\"status\":\"port_in_use\",\"message\":\"8080 已被其他实例占用\"}");
            return;
        }

        try {
            startMainProcess();
        } catch (Exception e) {
            lastError = e.getMessage();
            writeJson(exchange, 500,
                    "{\"status\":\"error\",\"message\":" + jsonString(e.getMessage()) + "}");
            return;
        }

        boolean ready = waitForMainReady();
        if (ready) {
            writeJson(exchange, 200, "{\"status\":\"started\"}");
        } else {
            writeJson(exchange, 500,
                    "{\"status\":\"timeout\",\"message\":" + jsonString(lastError) + "}");
        }
    }

    // @anchor: launcherMain_handleStop
    // 停止主服务：销毁子进程并等待退出
    private static void handleStop(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        if (mainProcess == null || !mainProcess.isAlive()) {
            mainProcess = null;
            writeJson(exchange, 200, "{\"status\":\"not_running\"}");
            return;
        }
        killProcessTree(mainProcess);
        mainProcess = null;
        writeJson(exchange, 200, "{\"status\":\"stopped\"}");
    }

    // @anchor: launcherMain_handleStatus
    // 返回主服务运行状态与端口就绪标志
    private static void handleStatus(HttpExchange exchange) throws IOException {
        boolean alive = isMainRunning();
        boolean ready = alive && probeMainPort();
        String body = "{\"running\":" + alive + ",\"ready\":" + ready + "}";
        writeJson(exchange, 200, body);
    }

    // ==================== 子进程管理 ====================

    // @anchor: launcherMain_startMainProcess
    // 用同 jar 内的 HttpServerMain 作为子进程启动主服务
    private static void startMainProcess() throws IOException {
        String javaExe = resolveJavaExe();
        String jarPath = resolveJarPath();

        // 主服务的工作目录跟随 launcher 的 CWD（项目根），
        // 而不是 jar 所在目录，否则 ./sessions、./sandbox 会落到 target/ 下
        File workDir = new File(System.getProperty("user.dir"));
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);

        // 复用 launcher 自己的 JVM 参数（--add-modules、--add-exports 等）
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (arg.startsWith("-agentlib") || arg.startsWith("-javaagent")) continue;
            cmd.add(arg);
        }

        cmd.add("-jar");
        cmd.add(jarPath);
        cmd.add("--daemon");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(workDir, "launcher-main.log")));

        // 注意：不要调用 pb.redirectInput(...)，stdin 默认是 pipe，
        // 子进程通过读到 EOF 感知父进程退出（详见 HttpServerMain --daemon 分支）

        Map<String, String> env = pb.environment();
        env.put("AGENT_MANAGED_BY_LAUNCHER", "1");
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            env.put("DEEPSEEK_API_KEY", apiKey);
        }

        // 主服务跟随 launcher 的绑定地址（已由 BindAddressResolver 统一解析）
        // 下发后主服务不再重复探测，保证两边一致走 Tailscale
        if (launcherBindAddress != null && !launcherBindAddress.isBlank()) {
            env.put(BindAddressResolver.BIND_ENV_KEY, launcherBindAddress);
        }
        mainProcess = pb.start();
        lastError = null;
        System.out.println("▶ 主服务已启动 PID=" + mainProcess.pid());
    }

    private static boolean isMainRunning() {
        return mainProcess != null && mainProcess.isAlive();
    }

    // @anchor: launcherMain_waitForMainReady
    // 轮询 8080 直到主服务响应或超时
    private static boolean waitForMainReady() {
        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!isMainRunning()) {
                lastError = "主服务进程已退出（启动失败，查看 launcher-main.log）";
                return false;
            }
            if (probeMainPort()) return true;
            try {
                Thread.sleep(READY_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        lastError = "主服务启动超时";
        return false;
    }

    // @anchor: launcherMain_probeMainPort
    // 探测 8080 是否已可响应
    private static boolean probeMainPort() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            // 主服务绑 Tailscale IP，探测地址必须跟随 launcher 的绑定地址
            String host = (launcherBindAddress != null && !launcherBindAddress.isBlank()) ? launcherBindAddress : "127.0.0.1";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + host + ":" + MAIN_PORT + "/status"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 路径解析 ====================

    private static String resolveJavaExe() {
        String home = System.getProperty("java.home");
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        return home + File.separator + "bin" + File.separator + (win ? "java.exe" : "java");
    }

    private static String resolveJarPath() throws IOException {
        try {
            URI uri = LauncherMain.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path p = Paths.get(uri);
            if (Files.isRegularFile(p)) return p.toAbsolutePath().toString();
        } catch (Exception ignored) {
        }
        // 兜底：当前目录下的 jar
        Path fallback = Paths.get("agentic-workflow-1.0-SNAPSHOT-jar-with-dependencies.jar")
                .toAbsolutePath();
        if (Files.exists(fallback)) return fallback.toString();
        throw new IOException("无法定位 jar 文件，请确认 launcher 以 -jar 方式启动");
    }

    // ==================== 工具 ====================

    private static void writeJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // @anchor: launcherMain_killProcessTree
// 递归终止进程及其全部后代：先温和 destroy，超时后强制 destroyForcibly
    private static void killProcessTree(Process p) {
        if (p == null || !p.isAlive()) return;

        // 先取 descendants 快照（进程死后拿不到）
        List<ProcessHandle> descendants;
        try {
            descendants = p.descendants().toList();
        } catch (Exception e) {
            descendants = List.of();
        }

        // 温和终止
        try { p.destroy(); } catch (Exception ignored) {}
        for (ProcessHandle ph : descendants) {
            try { ph.destroy(); } catch (Exception ignored) {}
        }

        // 等待一段时间
        try {
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 存活的后代强制终止
        for (ProcessHandle ph : descendants) {
            if (ph.isAlive()) {
                try { ph.destroyForcibly(); } catch (Exception ignored) {}
            }
        }

        // 主进程仍存活则强制终止
        if (p.isAlive()) {
            try { p.destroyForcibly(); } catch (Exception ignored) {}
            try { p.waitFor(3, TimeUnit.SECONDS); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
