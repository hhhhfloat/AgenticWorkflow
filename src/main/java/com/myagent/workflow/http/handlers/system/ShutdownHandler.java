// @anchor: shutdownHandler_intro
// 退出处理器：POST /shutdown，先回 200 再异步退出主服务进程
package com.myagent.workflow.http.handlers.system;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

// @anchor: shutdownHandler_class
// 退出处理器：仅接受 POST，异步触发主服务 System.exit(0)
public class ShutdownHandler implements HttpHandler {

    // @anchor: shutdownHandler_handle
    // 处理退出请求：先发响应，再延迟退出保证响应送达
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

        // 只允许退出由 launcher 管理的主服务，避免误杀手动启动的实例
        if (!"1".equals(System.getenv("AGENT_MANAGED_BY_LAUNCHER"))) {
            byte[] deny = "{\"status\":\"error\",\"message\":\"该实例不由 launcher 管理，拒绝远程退出\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(403, deny.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(deny);
            }
            return;
        }

        byte[] body = "{\"status\":\"ok\",\"message\":\"主服务即将退出\"}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }

        // 让响应先送达，再退出
        new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            System.exit(0);
        }, "ShutdownTrigger").start();
    }
}