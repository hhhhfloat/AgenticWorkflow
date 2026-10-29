// @anchor: openFolderHandler_intro
// 打开文件夹处理器：GET /openFolder，白名单内调用系统资源管理器
package com.myagent.workflow.http.handlers.project;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.myagent.workflow.http.utils.HandlerUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

// @anchor: openFolderHandler_class
// 打开文件夹处理器：仅允许打开 sandbox/TestProjects/HistoryOutput
public class OpenFolderHandler implements HttpHandler {
    // @anchor: openFolderHandler_handle
    // 处理打开请求：白名单校验与存在性检测后调用系统 API 打开目录
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
            openPath(targetPath.toString());
            HandlerUtils.sendResponse(exchange, 200, "{\"status\":\"success\", \"message\":\"已打开 " + target + " 文件夹\"}");
        } catch (Exception e) {
            HandlerUtils.sendResponse(exchange, 500, "{\"status\":\"error\", \"message\":\"打开失败: " + e.getMessage() + "\"}");
        }
    }

    // @anchor: openFolderHandler_openPath
    // 根据平台调用系统命令打开路径
    private void openPath(String path) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        ProcessBuilder pb;
        if (os.contains("win")) {
            pb = new ProcessBuilder("cmd", "/c", "start", "", path);
        } else if (os.contains("mac")) {
            pb = new ProcessBuilder("open", path);
        } else {
            pb = new ProcessBuilder("xdg-open", path);
        }
        // 继承错误流重定向，防止因控制台阻塞
        pb.redirectErrorStream(true);
        pb.start();
    }
}