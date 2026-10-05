// @anchor: controlHandler_tot_desc
// 控制权处理器：GET /control/status 查询，POST /control/switch 切换控制端
package com.myagent.workflow.http.handlers.system;

import com.myagent.workflow.http.HttpServerMain;
import com.myagent.workflow.session.Session;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

// @anchor: controlHandler_class
// 控制权处理器：让两端动态切换当前操作端，无需重启服务
public class ControlHandler implements HttpHandler {

    // @anchor: controlHandler_handle
    // 分发 status / switch 两个动作
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        if (path.endsWith("/status")) {
            String c = HttpServerMain.getActiveController();
            String body = "{\"controller\":" + (c == null ? "null" : "\"" + c + "\"") + "}";
            writeJson(exchange, 200, body);
            return;
        }

        if (path.endsWith("/switch")) {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String caller = detectCaller(exchange);
            HttpServerMain.setActiveController(caller);
            System.out.println("⇄ 控制权已切换到 " + caller);

            // 附上正在运行的会话，供前端拉取后跳转 + 订阅日志
            Session running = null;
            for (Session s : HttpServerMain.getSessionManager().listActive()) {
                if (s.isRunning()) {
                    running = s;
                    break;
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append("{\"status\":\"ok\",\"controller\":\"").append(caller).append("\"");
            if (running != null) {
                sb.append(",\"runningSessionId\":\"").append(running.getSessionId()).append("\"");
                String title = running.getMeta().title();
                if (title != null) {
                    sb.append(",\"runningTitle\":\"")
                            .append(title.replace("\\", "\\\\").replace("\"", "\\\""))
                            .append("\"");
                }
            }
            sb.append("}");
            writeJson(exchange, 200, sb.toString());

            return;
        }

        exchange.sendResponseHeaders(404, -1);
    }

    // @anchor: controlHandler_detectCaller
    // 与 GuardedHandler 一致的判定：优先 X-Client-Device，UA 兜底
    private String detectCaller(HttpExchange exchange) {
        String declared = exchange.getRequestHeaders().getFirst("X-Client-Device");
        if (declared != null) {
            String v = declared.trim().toLowerCase();
            if (v.equals("mobile")) return "MOBILE";
            if (v.equals("desktop")) return "DESKTOP";
        }
        return isMobileUA(exchange) ? "MOBILE" : "DESKTOP";
    }

    private boolean isMobileUA(HttpExchange exchange) {
        String ua = exchange.getRequestHeaders().getFirst("User-Agent");
        if (ua == null) return false;
        ua = ua.toLowerCase();
        return ua.contains("mobile") || ua.contains("android")
                || ua.contains("iphone") || ua.contains("ipad");
    }

    private void writeJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}