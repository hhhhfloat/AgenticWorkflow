package com.myagent.workflow.http.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;



import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

import static com.myagent.workflow.http.utils.HandlerUtils.parseQuery;

public class BrowseHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // 获取查询参数 path
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = parseQuery(query);
        String path = params.getOrDefault("path", ".");

        // 安全检查：禁止路径穿越
        if (path.contains("..") || path.startsWith("/") || path.contains(":\\")) {
            exchange.sendResponseHeaders(403, -1);
            return;
        }

        // 只允许访问 sandbox 和 TestProjects
        Path root = Paths.get(".").toAbsolutePath().normalize();
        Path target = root.resolve(path).normalize();
        Path sandboxRoot = root.resolve("sandbox").normalize();
        Path testRoot = root.resolve("TestProjects").normalize();

        if (!target.startsWith(sandboxRoot) && !target.startsWith(testRoot)) {
            exchange.sendResponseHeaders(403, -1);
            return;
        }

        if (!Files.exists(target) || !Files.isDirectory(target)) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(target)) {
            stream.forEach(p -> {
                Map<String, Object> item = new LinkedHashMap<>();
                String name = p.getFileName().toString();
                boolean isDir = Files.isDirectory(p);
                item.put("name", name);
                item.put("type", isDir ? "dir" : "file");

                // 核心：检测该目录是否包含 index.html（用于 TestProjects 识别项目入口）
                if (isDir) {
                    boolean hasIndex = Files.exists(p.resolve("index.html"));
                    item.put("hasIndexHtml", hasIndex);
                }

                if (!isDir) {
                    try {
                        item.put("size", Files.size(p));
                    } catch (IOException ignored) {}
                }
                entries.add(item);
            });
        }

        // 排序：目录在前，文件在后，按名称字母顺序
        entries.sort((a, b) -> {
            boolean aDir = "dir".equals(a.get("type"));
            boolean bDir = "dir".equals(b.get("type"));
            if (aDir && !bDir) return -1;
            if (!aDir && bDir) return 1;
            return ((String) a.get("name")).compareToIgnoreCase((String) b.get("name"));
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path);
        result.put("entries", entries);

        String json = new ObjectMapper().writeValueAsString(result);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, json.getBytes().length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(json.getBytes());
        }
    }

}
