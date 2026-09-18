package com.myagent.workflow.http.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.http.HttpServerMain;
import com.myagent.workflow.session.Session;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * POST /heartbeat
 * 请求体：{ "sessionId": "xxx" }
 * 更新指定会话的心跳时间，并检测沙箱目录变化。
 */
public class HeartbeatHandler implements HttpHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    // 目录哈希缓存（跨请求保持）
    private String lastSandboxHash = null;
    private String lastTestProjectsHash = null;
    private int heartBeatCount = 0;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String sessionId = null;
        try {
            JsonNode root = mapper.readTree(body);
            sessionId = root.path("sessionId").asText(null);
        } catch (Exception ignored) {
            // sessionId 可以缺省
        }

        // 更新会话心跳
        if (sessionId != null && !sessionId.isEmpty()) {
            Session session = HttpServerMain.getSessionManager().get(sessionId);
            if (session != null) {
                session.heartbeat();
            }
        }

        // 检测目录变化
        boolean needRefresh = detectDirectoryChange();

        String response = "{\"refresh\":" + needRefresh + "}";
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private boolean detectDirectoryChange() {
        String sbHash = computeHash("sandbox");
        String tpHash = computeHash("TestProjects");
        boolean needRefresh = false;

        if (heartBeatCount++ % 4 == 0) {
            if (sbHash != null && tpHash != null) {
                if (!Objects.equals(sbHash, lastSandboxHash)
                        || !Objects.equals(tpHash, lastTestProjectsHash)) {
                    needRefresh = true;
                    lastSandboxHash = sbHash;
                    lastTestProjectsHash = tpHash;
                }
            } else {
                if (sbHash != null) lastSandboxHash = sbHash;
                if (tpHash != null) lastTestProjectsHash = tpHash;
            }
        }
        if (heartBeatCount == 1_000_000) heartBeatCount = 0;
        return needRefresh;
    }

    private String computeHash(String dir) {
        Path target = Paths.get("./" + dir);
        if (!Files.exists(target) || !Files.isDirectory(target)) return null;
        try (Stream<Path> stream = Files.list(target)) {
            StringBuilder sb = new StringBuilder();
            stream.sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            sb.append(p.getFileName().toString()).append("|");
                            sb.append(Files.getLastModifiedTime(p).toMillis()).append(";");
                        } catch (IOException ignored) {}
                    });
            return sb.length() == 0 ? "" : Integer.toHexString(sb.toString().hashCode());
        } catch (IOException e) {
            return null;
        }
    }
}