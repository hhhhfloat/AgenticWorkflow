// @anchor: switchToLocalHandler_tot_desc
// 切回本地模式：写标记文件并触发服务重启（退出码 43）
package com.myagent.workflow.http.handlers;

import com.myagent.workflow.http.HttpServerMain;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

// @anchor: switchToLocalHandler_class
// 写入下次启动时使用的标记文件，然后触发重启
public class SwitchToLocalHandler implements HttpHandler {

    private static final String MARKER = ".next-launch-local";

    // @anchor: switchToLocalHandler_handle
    // 写标记 → 回 200 → 异步触发重启
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        Files.writeString(Paths.get(MARKER), "1", StandardCharsets.UTF_8);

        byte[] body = "{\"status\":\"ok\",\"message\":\"即将重启并切回本地模式\"}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }

        // 异步触发重启，让响应先发出去
        new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            System.exit(HttpServerMain.EXIT_CODE_RESTART);
        }).start();
    }
}