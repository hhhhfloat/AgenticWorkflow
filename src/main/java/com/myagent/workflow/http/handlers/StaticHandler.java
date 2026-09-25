// @anchor: staticHandler_tot_desc
// 静态资源处理器：把 classpath 下 /static 资源映射到根路径并提供访问
package com.myagent.workflow.http.handlers;

import com.myagent.workflow.http.HttpServerMain;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

// @anchor: staticHandler_class
// 静态资源处理器：从 jar 内 /static 读取前端文件并按扩展名设置类型
public class StaticHandler implements HttpHandler {
    // @anchor: staticHandler_handle
    // 处理静态请求：根路径回退 index.html，找到资源则流式返回否则 404
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

