// @anchor: openTerminalHandler_intro
// 打开终端处理器：GET /openTerminal，白名单内唤起终端并定位到指定目录
package com.myagent.workflow.http.handlers.project;

import com.myagent.workflow.core.config.env.Platform;
import com.myagent.workflow.core.config.env.ShellPathResolver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.myagent.workflow.http.utils.HandlerUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

// @anchor: openTerminalHandler_class
// 打开终端处理器：仅允许打开 sandbox/TestProjects/HistoryOutput
public class OpenTerminalHandler implements HttpHandler {

    // @anchor: openTerminalHandler_handle
    // 处理打开请求：白名单目录校验后按平台唤起终端
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = HandlerUtils.parseQuery(query);
        String target = params.getOrDefault("path", "sandbox");

        Path targetPath = Paths.get("./" + target).toAbsolutePath().normalize();
        boolean allowed = targetPath.startsWith(Paths.get("./sandbox").toAbsolutePath().normalize())
                || targetPath.startsWith(Paths.get("./TestProjects").toAbsolutePath().normalize())
                || targetPath.startsWith(Paths.get("./HistoryOutput").toAbsolutePath().normalize());
        if (!allowed) {
            HandlerUtils.sendResponse(exchange, 400, "{\"status\":\"error\", \"message\":\"不支持的目录\"}");
            return;
        }
        if (!Files.isDirectory(targetPath)) {
            HandlerUtils.sendResponse(exchange, 404, "{\"status\":\"error\", \"message\":\"目录不存在\"}");
            return;
        }

        try {
            openTerminal(targetPath.toString());
            HandlerUtils.sendResponse(exchange, 200,
                    "{\"status\":\"success\", \"message\":\"已打开终端: " + target + "\"}");
        } catch (Exception e) {
            HandlerUtils.sendResponse(exchange, 500,
                    "{\"status\":\"error\", \"message\":\"打开终端失败: " + e.getMessage() + "\"}");
        }
    }

    // @anchor: openTerminalHandler_openTerminal
    // 平台分派：Windows / macOS / WSL2 / 原生 Linux
    private void openTerminal(String absPath) throws IOException {
        if (Platform.isWindows()) {
            ProcessBuilder pb = new ProcessBuilder(
                    "cmd", "/c", "start", "cmd.exe", "/k",
                    "cd /d \"" + absPath + "\"");
            pb.redirectErrorStream(true);
            pb.start();
        } else if (Platform.isMac()) {
            ProcessBuilder pb = new ProcessBuilder("open", "-a", "Terminal", absPath);
            pb.redirectErrorStream(true);
            pb.start();
        } else if (isWsl()) {
            openWslTerminal(absPath);
        } else {
            openNativeLinuxTerminal(absPath);
        }
    }

    // @anchor: openTerminalHandler_openWslTerminal
    // WSL2：优先 wt.exe 打开 WSL bash，退化 cmd.exe 拉起 wsl.exe --cd
    private void openWslTerminal(String absPath) throws IOException {
        ProcessBuilder pb;
        if (ShellPathResolver.which("wt.exe") != null) {
            // wt 打开一个新标签页，直接执行 wsl.exe --cd
            pb = new ProcessBuilder("wt.exe", "wsl.exe", "--cd", absPath);
        } else if (ShellPathResolver.which("cmd.exe") != null) {
            // 后备：cmd 拉起一个新 wsl 会话
            pb = new ProcessBuilder("cmd.exe", "/c", "start", "wsl.exe", "--cd", absPath);
        } else {
            throw new IOException("WSL 环境下未找到 wt.exe 或 cmd.exe，无法打开终端");
        }
        pb.redirectErrorStream(true);
        pb.start();
    }

    // @anchor: openTerminalHandler_openNativeLinuxTerminal
    // 原生 Linux 桌面：按候选顺序探测可用终端
    private void openNativeLinuxTerminal(String absPath) throws IOException {
        String[][] candidates = {
                {"gnome-terminal", "--working-directory=" + absPath},
                {"konsole", "--workdir", absPath},
                {"xfce4-terminal", "--working-directory=" + absPath},
                {"x-terminal-emulator", "-e", "cd '" + absPath + "' && exec $SHELL"},
        };
        for (String[] c : candidates) {
            if (ShellPathResolver.which(c[0]) != null) {
                ProcessBuilder pb = new ProcessBuilder(c);
                pb.redirectErrorStream(true);
                pb.start();
                return;
            }
        }
        throw new IOException("未找到可用的终端程序");
    }

    // @anchor: openTerminalHandler_isWsl
    // 探测是否运行在 WSL 内（读 /proc/version 关键字）

    private static boolean isWsl() {
        try {
            String v = Files.readString(Paths.get("/proc/version")).toLowerCase();
            return v.contains("microsoft") || v.contains("wsl");
        } catch (IOException e) {
            return false;
        }
    }
}
