// @anchor: sessionLogsHandler_intro
// 会话日志处理器：GET /session/logs，返回指定会话最近一轮的运行日志尾部
package com.myagent.workflow.http.handlers.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.http.utils.HandlerUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

// @anchor: sessionLogsHandler_class
// 读取 HistoryOutput/{sessionId}/ 下最新一轮的 .log 文件尾部 N 行
public class SessionLogsHandler implements HttpHandler {

    private static final Path ROOT_DIR = Paths.get("./HistoryOutput");
    private static final int DEFAULT_TAIL = 500;
    private static final int MAX_TAIL = 2000;

    private final ObjectMapper mapper = new ObjectMapper();

    // @anchor: sessionLogsHandler_handle
    // 处理日志请求：定位会话目录、读最新文件、返回尾部 N 行
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        Map<String, String> params = HandlerUtils.parseQuery(exchange.getRequestURI().getQuery());
        String sessionId = params.get("sessionId");
        if (sessionId == null || sessionId.isEmpty()) {
            writeJson(exchange, 400, "{\"status\":\"error\",\"message\":\"缺少 sessionId\"}");
            return;
        }
        // 路径穿越防护
        if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            writeJson(exchange, 400, "{\"status\":\"error\",\"message\":\"sessionId 非法\"}");
            return;
        }

        int tail = DEFAULT_TAIL;
        try {
            String t = params.get("tail");
            if (t != null && !t.isEmpty()) {
                tail = Integer.parseInt(t);
                if (tail < 1) tail = DEFAULT_TAIL;
                if (tail > MAX_TAIL) tail = MAX_TAIL;
            }
        } catch (NumberFormatException ignored) {}

        Path sessionDir = ROOT_DIR.resolve(sessionId);
        if (!Files.exists(sessionDir) || !Files.isDirectory(sessionDir)) {
            writeJson(exchange, 200, emptyResponse(sessionId));
            return;
        }

        // 找最新一个 .log（文件名时间戳，字典序 = 时间序）
        Path latest;
        try (Stream<Path> files = Files.list(sessionDir)) {
            latest = files
                    .filter(p -> p.toString().endsWith(".log"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        }
        if (latest == null) {
            writeJson(exchange, 200, emptyResponse(sessionId));
            return;
        }

        List<String> allLines = Files.readAllLines(latest, StandardCharsets.UTF_8);
        int total = allLines.size();
        int start = Math.max(0, total - tail);
        List<String> lines = allLines.subList(start, total);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionId", sessionId);
        response.put("file", latest.getFileName().toString());
        response.put("total", total);
        response.put("lines", lines);

        writeJson(exchange, 200, mapper.writeValueAsString(response));
    }

    // @anchor: sessionLogsHandler_emptyResponse
    // 无日志时返回统一空结构
    private String emptyResponse(String sessionId) throws IOException {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionId", sessionId);
        response.put("file", null);
        response.put("total", 0);
        response.put("lines", List.of());
        return mapper.writeValueAsString(response);
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