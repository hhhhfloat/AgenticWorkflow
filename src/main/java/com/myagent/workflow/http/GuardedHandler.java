// @anchor: guardedHandler_intro
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
        String caller = detectCaller(exchange);
        String path = exchange.getRequestURI().getPath();

        // /control/* 永久放行，任何一端都能查询和切换控制权
        if (path.startsWith("/control/")) {
            delegate.handle(exchange);
            return;
        }

        // 读路由不参与控制权判定，直接放行（避免读请求抢占控制权）
        if (!protect) {
            delegate.handle(exchange);
            return;
        }

        // 受保护路由：首访者自动成为控制端
        String controller = HttpServerMain.getActiveController();
        if (controller == null) {
            HttpServerMain.setActiveController(caller);
            controller = caller;
        }

        // 非控制端访问受保护路由 → 403
        if (!caller.equals(controller)) {
            byte[] body = ("{\"status\":\"error\",\"code\":\"device-locked\","
                    + "\"controller\":\"" + controller + "\","
                    + "\"message\":\"另一端正在控制中\"}")
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

    // @anchor: guardedHandler_detectCaller
    // 判定请求来自哪一端：优先读显式声明 X-Client-Device，UA 作 fallback
    private String detectCaller(HttpExchange exchange) {
        String declared = exchange.getRequestHeaders().getFirst("X-Client-Device");
        if (declared != null) {
            String v = declared.trim().toLowerCase();
            if (v.equals("mobile")) return "MOBILE";
            if (v.equals("desktop")) return "DESKTOP";
        }
        return isMobileUA(exchange) ? "MOBILE" : "DESKTOP";
    }

    // @anchor: guardedHandler_isMobileUA
    // User-Agent 启发式判定，仅在没有显式声明时使用
    private boolean isMobileUA(HttpExchange exchange) {
        String ua = exchange.getRequestHeaders().getFirst("User-Agent");
        if (ua == null) return false;
        ua = ua.toLowerCase();
        return ua.contains("mobile") || ua.contains("android")
                || ua.contains("iphone") || ua.contains("ipad");
    }
}