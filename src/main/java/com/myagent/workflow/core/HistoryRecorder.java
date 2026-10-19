// @anchor: historyRecorder_intro
// 会话历史落盘器：原始 API 日志周期缓存后刷盘，run 结束压缩归档为 .gz
package com.myagent.workflow.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.config.AgentConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

/**
 * 历史记录器 —— 只负责原始 API 日志的缓存、刷盘与压缩。
 * <p>
 * 文件（在 ./temp/ 下）：
 * - {timestamp}_{uuid}_raw.jsonl：原始 API 请求/响应，按周期缓存后刷盘
 * - 归档时压缩为带时间戳的 .gz
 */
// @anchor: historyRecorder_class
// 历史记录器：管理 raw.jsonl 的写入与压缩
public class HistoryRecorder {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final ObjectMapper objectMapper;
    private Consumer<String> logConsumer;

    /**
     * 本次会话的文件前缀，用于 raw 文件命名
     */
    private final String fileBase;
    private final Path tempDir;
    // 会话级 recovery 文件（覆盖写，只保留最后一对 request/response）
    private final Path recoveryFile;
    // 全量归档目录（任务成功时压缩到此）
    private final Path historyOutputDir;

    // 当前周期的原始日志缓存（覆盖写，只保留最新一份）
    private String pendingRawRequest = null;
    private String pendingRawResponse = null;

    // @anchor: historyRecorder_constructor
    // 创建实例并准备 temp 目录与文件前缀
    public HistoryRecorder(ObjectMapper objectMapper, Consumer<String> logConsumer, String sessionId) {
        this.objectMapper = objectMapper;
        this.logConsumer = logConsumer;

        String timestamp = LocalDateTime.now().format(TIME_FMT);
        String uuid = UUID.randomUUID().toString().substring(0, 6);
        this.fileBase = timestamp + "_" + uuid;
        this.tempDir = Paths.get("./temp");
        this.recoveryFile = Paths.get(AgentConfig.getSessionsDir())
                .resolve(sessionId).resolve("recovery_raw.jsonl");
        this.historyOutputDir = Paths.get("./HistoryOutput").resolve(sessionId);

        try {
            if (!Files.exists(tempDir)) {
                Files.createDirectories(tempDir);
            }
            Files.createDirectories(recoveryFile.getParent());
        } catch (IOException e) {
            System.err.println("⚠️ temp 目录创建失败: " + e.getMessage());
        }
    }

    // @anchor: historyRecorder_cacheRawLog
    // 缓存本周期最新的 request 或 response 原文（覆盖写，暂不落盘）
    public synchronized void cacheRawLog(String type, String jsonContent) {
        if ("request".equals(type)) {
            pendingRawRequest = jsonContent;
        } else if ("response".equals(type)) {
            pendingRawResponse = jsonContent;
        }
    }

    // @anchor: historyRecorder_flushRawLog
    // 把缓存中的 request/response 追加写入 raw.jsonl 并清空缓存
    public synchronized void flushRawLog() {
        if (pendingRawRequest == null && pendingRawResponse == null) return;

        try {
            Path rawFile = rawFilePath();

            StringBuilder sb = new StringBuilder();
            String now = LocalDateTime.now().toString();

            if (pendingRawRequest != null) {
                sb.append(String.format(
                        "{\"type\":\"request\",\"timestamp\":\"%s\",\"body\":%s}\n",
                        now, pendingRawRequest
                ));
            }
            if (pendingRawResponse != null) {
                sb.append(String.format(
                        "{\"type\":\"response\",\"timestamp\":\"%s\",\"body\":%s}\n",
                        now, pendingRawResponse
                ));
            }
            Files.writeString(rawFile, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            pendingRawRequest = null;
            pendingRawResponse = null;
        } catch (IOException e) {
            System.err.println("⚠️ 原始日志落盘失败: " + e.getMessage());
        }
    }

    // @anchor: historyRecorder_compress
    // 把 raw.jsonl 压缩为带时间戳的 .gz 并删除原文件
    public void compress() {
        String compressedName = null;
        synchronized (this) {
            Path rawFile = rawFilePath();
            if (!Files.exists(rawFile)) return;

            try {
                String ts = LocalDateTime.now().format(TIME_FMT);
                String rawName = rawFile.getFileName().toString();
                String gzName = rawName.endsWith(".jsonl")
                        ? rawName.substring(0, rawName.length() - ".jsonl".length()) + "_" + ts + ".jsonl.gz"
                        : rawName + "_" + ts + ".gz";
                Files.createDirectories(historyOutputDir);
                Path gzFile = historyOutputDir.resolve(gzName);
                try (InputStream in = Files.newInputStream(rawFile);
                     OutputStream out = new GZIPOutputStream(Files.newOutputStream(gzFile))) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }
                }
                Files.delete(rawFile);
                compressedName = gzFile.getFileName().toString();
            } catch (IOException e) {
                System.err.println("⚠️ 压缩原始日志失败: " + e.getMessage());
            }
        }
        // log() 移到锁外：logConsumer 可能写 logger 或 SSE，避免长时间持锁
        if (compressedName != null) {
            log("📦 [系统] 原始日志已压缩: " + compressedName);
        }
    }

    // ==================== 辅助 ====================

    // @anchor: historyRecorder_rawFilePath
    // 由 fileBase 推导本次会话的 raw.jsonl 路径
    private Path rawFilePath() {
        return tempDir.resolve(fileBase + "_raw.jsonl");
    }

    private void log(String message) {
        Consumer<String> consumer = this.logConsumer;
        if (consumer != null) {
            consumer.accept(message);
        } else {
            System.out.println(message);
        }
    }

    // @anchor: historyRecorder_recovery
    // recovery 文件的存在检查、路径查询与清除
    public Path getRecoveryPath() {
        return recoveryFile;
    }

    public synchronized boolean hasRecovery() {
        return readRecovery() != null;
    }

    public synchronized void clearRecovery() {
        try {
            Files.deleteIfExists(recoveryFile);
        } catch (IOException e) {
            System.err.println("⚠️ 清除 recovery 文件失败: " + e.getMessage());
        }
    }

    public synchronized void setLogConsumer(Consumer<String> consumer) {
        this.logConsumer = consumer;
    }

    // @anchor: historyRecorder_recoverySnapshot
// recovery 快照：基础区指纹 + 完整消息列表
    public record RecoverySnapshot(String fingerprint, List<Map<String, Object>> messages) {}

    // @anchor: historyRecorder_writeRecovery
// 覆盖写 recovery 快照（只保留最新一份）
    public synchronized void writeRecovery(List<Map<String, Object>> messages, String fingerprint) {
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("type", "recovery");
            record.put("timestamp", LocalDateTime.now().toString());
            record.put("fingerprint", fingerprint);
            record.put("messages", messages);
            String json = objectMapper.writeValueAsString(record);
            Files.writeString(recoveryFile, json + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("⚠️ 写 recovery 失败: " + e.getMessage());
        }
    }

    // @anchor: historyRecorder_readRecovery
// 读 recovery 快照；旧格式或文件损坏时返回 null
    public synchronized RecoverySnapshot readRecovery() {
        if (!Files.exists(recoveryFile)) return null;
        try {
            String content = Files.readString(recoveryFile, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) return null;
            String[] lines = content.split("\n");
            String lastLine = null;
            for (int i = lines.length - 1; i >= 0; i--) {
                if (!lines[i].isBlank()) { lastLine = lines[i]; break; }
            }
            if (lastLine == null) return null;
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(lastLine);
            if (!"recovery".equals(node.path("type").asText())) return null;   // 旧格式不兼容
            String fingerprint = node.path("fingerprint").asText(null);
            if (fingerprint == null) return null;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = objectMapper.convertValue(
                    node.path("messages"), List.class);
            return new RecoverySnapshot(fingerprint, messages);
        } catch (Exception e) {
            System.err.println("⚠️ 读 recovery 失败: " + e.getMessage());
            return null;
        }
    }
}