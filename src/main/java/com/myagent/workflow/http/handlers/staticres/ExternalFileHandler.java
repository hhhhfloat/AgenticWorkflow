// @anchor: externalFileHandler_intro
// 外部目录文件处理器：映射 /TestProjects 与 /sandbox 前缀到本地文件
package com.myagent.workflow.http.handlers.staticres;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

// @anchor: externalFileHandler_class
// 外部文件处理器：把 URL 前缀映射到基准目录并安全地流式返回文件
public class ExternalFileHandler implements HttpHandler {
    private final Path basePath;
    private final String prefix; // 如 "/TestProjects" 或 "/sandbox"

    // @anchor: externalFileHandler_constructor
    // 记录基准目录（归一化为绝对路径）与 URL 前缀
    public ExternalFileHandler(Path basePath, String prefix) {
        this.basePath = basePath.toAbsolutePath().normalize();
        this.prefix = prefix;
    }

    // @anchor: externalFileHandler_handle
    // 处理文件请求：前缀剥离、越界校验、目录默认取 index.html 后返回内容
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String requestPath = exchange.getRequestURI().getPath();
        // 移除前缀，获取相对路径
        if (!requestPath.startsWith(prefix)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        String relative = requestPath.substring(prefix.length());
        if (relative.isEmpty() || relative.equals("/")) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        Path resolved = basePath.resolve(relative.substring(1)).normalize();
        if (!resolved.startsWith(basePath)) {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
            return;
        }

        File file = resolved.toFile();
        if (!file.exists()) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        if (file.isDirectory()) {
            file = new File(file, "index.html");
            if (!file.exists()) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
        }

        String name = file.getName().toLowerCase();
        String contentType;
        if (name.endsWith(".html") || name.endsWith(".htm")) contentType = "text/html; charset=utf-8";
        else if (name.endsWith(".css")) contentType = "text/css; charset=utf-8";
        else if (name.endsWith(".js")) contentType = "application/javascript; charset=utf-8";
        else if (name.endsWith(".png")) contentType = "image/png";
        else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) contentType = "image/jpeg";
        else if (name.endsWith(".gif")) contentType = "image/gif";
        else if (name.endsWith(".svg")) contentType = "image/svg+xml";
        else if (name.endsWith(".ico")) contentType = "image/x-icon";
        else if (name.endsWith(".md")) contentType = "text/markdown; charset=utf-8";
        else contentType = "text/plain; charset=utf-8";

        // HTML / SVG 是主动内容，同源直出会形成存储型 XSS，可转发写路由
        // - sandbox（unique origin）：隔离 localStorage / cookie / IndexedDB
        // - connect-src 'none'：禁止 fetch / XHR / WebSocket，阻断调 /run 等端点
        // - nosniff：禁止浏览器 MIME 嗅探
        // 静态 HTML 页面（html-calculator 等）不依赖同源存储和网络请求，不受影响
        if (contentType.startsWith("text/html") || contentType.startsWith("image/svg+xml")) {
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "sandbox allow-scripts allow-forms allow-popups allow-modals; connect-src 'none'");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        }

        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, file.length());
        try (FileInputStream fis = new FileInputStream(file);
             OutputStream os = exchange.getResponseBody()) {
            fis.transferTo(os);
        }
        exchange.close();
    }
}

