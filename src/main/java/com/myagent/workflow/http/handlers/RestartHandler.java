package com.myagent.workflow.http.handlers;

import com.myagent.workflow.http.HttpServerMain;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * POST /restart
 * <p>
 * 语义：配置已保存，退出进程并返回退出码 43，由外部启动脚本重新拉起。
 * 说明：进程无法"自己重启"，只能退出，由父进程或用户手动重启。
 */
public class RestartHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String response = "{\"status\":\"success\", \"message\":\"配置已保存，程序即将重启...\"}";
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        exchange.close();

        new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {}
            System.out.println("🔄 配置已更新，正在重启...");
            System.exit(HttpServerMain.EXIT_CODE_RESTART);
        }, "RestartThread").start();
    }
}