// @anchor: staticHandler_intro
// 静态资源处理器：把 classpath 下 /static 资源映射到根路径并提供访问
package com.myagent.workflow.http.handlers.staticres;

import com.myagent.workflow.http.HttpServerMain;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;

// @anchor: staticHandler_class
// 静态资源处理器：从 jar 内 /static 读取前端文件并按扩展名设置类型
public class StaticHandler implements HttpHandler {

    // @anchor: staticHandler_allowedExtensions
    // 允许直出的静态资源扩展名白名单（A4：防止读 /static 之外的非前端资源）
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "html", "htm", "css", "js", "mjs", "map",
            "md", "txt", "png", "jpg", "jpeg", "gif", "svg", "ico",
            "woff", "woff2", "ttf", "otf"
    );

    // @anchor: staticHandler_handle
    // 处理静态请求：根路径回退 index.html；拒绝 ".." 与非白名单扩展名
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path == null || path.isEmpty()) path = "/";

        // getPath() 已解码 %2e 等编码形式，这里统一分隔符后再做段级检查
        String normalized = path.replace('\\', '/');
        if ("/".equals(normalized) || normalized.endsWith("/")) normalized += "index.html";

        System.out.println("[StaticHandler] path=" + path + " -> normalized=" + normalized);

        // 拒绝任何 ".." 段
        for (String seg : normalized.split("/")) {
            if ("..".equals(seg)) {
                notFound(exchange);
                return;
            }
        }

        // 扩展名白名单：不在白名单的路径直接 404，避免 getResourceAsStream 读到
        // /static 之外的资源（exploded classpath 下的 agent-config.properties 等）
        String ext = extensionOf(normalized);
        if (ext == null || !ALLOWED_EXTENSIONS.contains(ext)) {
            notFound(exchange);
            return;
        }

        InputStream is = HttpServerMain.class.getResourceAsStream("/static" + normalized);
        if (is == null) {
            notFound(exchange);
            return;
        }

        String contentType = switch (ext) {
            case "css" -> "text/css; charset=utf-8";
            case "js", "mjs" -> "application/javascript; charset=utf-8";
            case "json", "map" -> "application/json; charset=utf-8";
            case "md" -> "text/markdown; charset=utf-8";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "svg" -> "image/svg+xml";
            case "ico" -> "image/x-icon";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            case "ttf" -> "font/ttf";
            case "otf" -> "font/otf";
            default -> "text/html; charset=utf-8";
        };

        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream os = exchange.getResponseBody()) {
            is.transferTo(os);
        }
        exchange.close();
    }

    // @anchor: staticHandler_notFound
    private static void notFound(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }

    // @anchor: staticHandler_extensionOf
    private static String extensionOf(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash) return null;
        return path.substring(dot + 1).toLowerCase();
    }
}