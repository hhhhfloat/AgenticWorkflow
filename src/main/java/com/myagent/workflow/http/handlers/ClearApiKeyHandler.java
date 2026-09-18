package com.myagent.workflow.http.handlers;

import com.myagent.workflow.http.HttpServerMain;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * POST /clear-api-key
 * <p>
 * 语义：标记 API Key 已被清除，退出进程并返回退出码 42。
 * 在退出前的这段时间里，所有 /run 请求都会被 RunHandler 拒绝。
 * <p>
 * 说明：环境变量无法从 Java 进程中真正删除，所以实际的做法是：
 * - 标记 apiKeyClearedByUser = true
 * - 退出进程，由外部启动脚本决定是否重新拉起
 */
public class ClearApiKeyHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // 先标记，让后续请求立即被拒绝
        HttpServerMain.markApiKeyCleared();

        String response = "{\"status\":\"success\", \"message\":\"API Key 已清除，程序即将重启...\"}";
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
            System.out.println("🔑 API Key 已清除，正在重启...");
            System.exit(HttpServerMain.EXIT_CODE_CLEAR_API_KEY);
        }, "ClearApiKeyThread").start();
    }
}