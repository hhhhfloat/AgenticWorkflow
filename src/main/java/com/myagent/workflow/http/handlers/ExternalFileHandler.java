package com.myagent.workflow.http.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

public class ExternalFileHandler implements HttpHandler {
    private final Path basePath;
    private final String prefix; // 如 "/TestProjects" 或 "/sandbox"

    public ExternalFileHandler(Path basePath, String prefix) {
        this.basePath = basePath.toAbsolutePath().normalize();
        this.prefix = prefix;
    }

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

        String name = file.getName();
        String contentType = "text/html";
        if (name.endsWith(".css")) contentType = "text/css";
        else if (name.endsWith(".js")) contentType = "application/javascript";
        else if (name.endsWith(".png")) contentType = "image/png";

        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, file.length());
        try (FileInputStream fis = new FileInputStream(file);
             OutputStream os = exchange.getResponseBody()) {
            fis.transferTo(os);
        }
        exchange.close();
    }
}

