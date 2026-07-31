package com.myagent.workflow.http.handlers;

import com.myagent.workflow.http.HttpServerMain;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class StaticHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/".equals(path)) path = "/index.html";

        InputStream is = HttpServerMain.class.getResourceAsStream("/static" + path);
        if (is == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        String contentType = "text/html";
        if (path.endsWith(".css")) contentType = "text/css";
        else if (path.endsWith(".js")) contentType = "application/javascript";
        else if (path.endsWith(".png")) contentType = "image/png";
        else if (path.endsWith(".json")) contentType = "application/json";

        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream os = exchange.getResponseBody()) {
            is.transferTo(os);
        }
        exchange.close();
    }
}

