// @anchor: openFolderHandler_tot_desc
// 打开文件夹处理器：GET /openFolder，白名单内调用系统资源管理器
package com.myagent.workflow.http.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.myagent.workflow.http.utils.HandlerUtils;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

// @anchor: openFolderHandler_class
// 打开文件夹处理器：仅允许打开 sandbox/TestProjects/HistoryOutput
public class OpenFolderHandler implements HttpHandler {
    // @anchor: openFolderHandler_handle
    // 处理打开请求：白名单校验与存在性检测后调用桌面 API 打开目录
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // 解析 path 参数
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = HandlerUtils.parseQuery(query);
        String target = params.getOrDefault("path", "sandbox");

        // 安全白名单：只允许打开这三个目录
        Set<String> allowed = Set.of("sandbox", "TestProjects", "HistoryOutput");
        if (!allowed.contains(target)) {
            HandlerUtils.sendResponse(exchange, 400, "{\"status\":\"error\", \"message\":\"不支持的目录: " + target + "\"}");
            return;
        }

        Path dirPath = Paths.get("./" + target).toAbsolutePath().normalize();
        File dir = dirPath.toFile();

        if (!dir.exists() || !dir.isDirectory()) {
            HandlerUtils.sendResponse(exchange, 404, "{\"status\":\"error\", \"message\":\"" + target + " 目录不存在\"}");
            return;
        }

        if (!Desktop.isDesktopSupported()) {
            HandlerUtils.sendResponse(exchange, 500, "{\"status\":\"error\", \"message\":\"当前系统不支持 Desktop API\"}");
            return;
        }

        try {
            Desktop.getDesktop().open(dir);
            HandlerUtils.sendResponse(exchange, 200, "{\"status\":\"success\", \"message\":\"已打开 " + target + " 文件夹\"}");
        } catch (IOException e) {
            HandlerUtils.sendResponse(exchange, 500, "{\"status\":\"error\", \"message\":\"打开失败: " + e.getMessage() + "\"}");
        }
    }

}

