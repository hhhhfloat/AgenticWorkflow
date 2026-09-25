// @anchor: guardedHandler_tot_desc
// 设备保护包装器：手机锁定期间，桌面端的受保护请求返回 403
package com.myagent.workflow.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

// @anchor: guardedHandler_class
// 包装具体 handler，识别手机/桌面并做独占锁检查
public class GuardedHandler implements HttpHandler {

    private final HttpHandler delegate;
    private final boolean protect;

    public GuardedHandler(HttpHandler delegate, boolean protect) {
        this.delegate = delegate;
        this.protect = protect;
    }

    // @anchor: guardedHandler_handle
    // 入口：手机请求置锁；受保护路径在锁定时拒绝桌面请求
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        boolean isMobile = isMobile(exchange);

        // 任何手机请求 → 抢占锁
        if (isMobile && !HttpServerMain.isMobileLocked()) {
            HttpServerMain.lockForMobile();
            System.out.println("📱 手机已接管控制权（重启服务可释放）");
        }

        // 锁定时，桌面端受保护请求被拒
        if (protect && !isMobile && HttpServerMain.isMobileLocked()) {
            byte[] body = "{\"status\":\"error\",\"code\":\"mobile-locked\",\"message\":\"手机正在使用中，本设备操作已暂停\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(403, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
            return;
        }

        delegate.handle(exchange);
    }

    // @anchor: guardedHandler_isMobile
    // 基于 User-Agent 判断请求来自手机还是桌面
    private boolean isMobile(HttpExchange exchange) {
        String ua = exchange.getRequestHeaders().getFirst("User-Agent");
        if (ua == null) return false;
        ua = ua.toLowerCase();
        return ua.contains("mobile") || ua.contains("android")
                || ua.contains("iphone") || ua.contains("ipad");
    }
}