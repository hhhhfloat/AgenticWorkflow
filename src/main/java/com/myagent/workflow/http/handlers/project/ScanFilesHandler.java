// @anchor: scanFilesHandler_tot_desc
// 大文件扫描处理器：GET /scan-files，统计项目内文本文件字符数并按倒序返回
package com.myagent.workflow.http.handlers.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.myagent.workflow.http.utils.HandlerUtils;
import com.myagent.workflow.tools.anchor.AnchorScanner;
import com.myagent.workflow.tools.PathUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

// @anchor: scanFilesHandler_class
// 大文件扫描处理器：递归统计项目内文本文件字符数与行数
public class ScanFilesHandler implements HttpHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MAX_READ_BYTES = 5L * 1024 * 1024;  // 超过 5MB 跳过精确统计

    // @anchor: scanFilesHandler_excluded
    // 排除的元数据文件
    private static final Set<String> EXCLUDED_FILES = Set.of(
            ".anchors.json", ".project_index.json", ".agent_entry.json"
    );

    // @anchor: scanFilesHandler_handle
    // 处理扫描请求：遍历项目、统计字符数、按大小倒序返回
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        Map<String, String> params = HandlerUtils.parseQuery(exchange.getRequestURI().getQuery());
        String projectPath = params.getOrDefault("path", "");
        if (projectPath.isBlank()) {
            HandlerUtils.sendResponse(exchange, 400,
                    "{\"status\":\"error\",\"message\":\"缺少 path 参数\"}");
            return;
        }

        // 归一化：剥离 sandbox/ 前缀，与 PathUtils.safeResolve 的基准对齐
        String normalized = projectPath.replace('\\', '/');
        if (normalized.startsWith("sandbox/")) {
            normalized = normalized.substring("sandbox/".length());
        }

        if (normalized.isBlank()) {
            HandlerUtils.sendResponse(exchange, 400,
                    "{\"status\":\"error\",\"message\":\"path 参数无效\"}");
            return;
        }

        Path projectDir;
        try {
            projectDir = PathUtils.safeResolve(normalized);
        } catch (IOException e) {
            HandlerUtils.sendResponse(exchange, 400,
                    "{\"status\":\"error\",\"message\":\"路径不合法\"}");
            return;
        }

        if (!Files.isDirectory(projectDir)) {
            HandlerUtils.sendResponse(exchange, 404,
                    "{\"status\":\"error\",\"message\":\"项目不存在\"}");
            return;
        }

        List<Map<String, Object>> files = new ArrayList<>();

        try (var stream = Files.walk(projectDir)) {
            stream.filter(Files::isRegularFile).forEach(f -> {
                String name = f.getFileName().toString();
                if (name.startsWith(".")) return;
                if (EXCLUDED_FILES.contains(name)) return;

                String lower = name.toLowerCase();
                int dot = lower.lastIndexOf('.');
                if (dot < 0) return;
                String ext = lower.substring(dot);
                if (!AnchorScanner.TEXT_EXTENSIONS.contains(ext)) return;

                try {
                    long size = Files.size(f);
                    long chars;
                    int lines;
                    boolean approximate = false;

                    if (size > MAX_READ_BYTES) {
                        chars = -1;
                        lines = -1;
                        approximate = true;
                    } else {
                        String content = Files.readString(f, StandardCharsets.UTF_8);
                        chars = content.length();
                        lines = content.split("\n", -1).length;
                    }

                    String rel = projectDir.relativize(f).toString().replace('\\', '/');
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("path", rel);
                    entry.put("chars", chars);
                    entry.put("lines", lines);
                    entry.put("approximate", approximate);
                    files.add(entry);
                } catch (IOException ignored) {
                }
            });
        }

        files.sort((a, b) -> Long.compare((long) b.get("chars"), (long) a.get("chars")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("project", projectPath);
        result.put("files", files);
        result.put("total", files.size());

        HandlerUtils.sendResponse(exchange, 200, MAPPER.writeValueAsString(result));
    }
}