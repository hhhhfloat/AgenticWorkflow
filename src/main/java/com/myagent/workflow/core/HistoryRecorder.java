// @anchor: historyRecorder_tot_desc
// 会话历史落盘器：原始 API 日志周期缓存后刷盘，run 结束压缩归档为 .gz
package com.myagent.workflow.core;

import com.fasterxml.jackson.databind.ObjectMapper;

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

    /** 本次会话的文件前缀，用于 raw 文件命名 */
    private final String fileBase;
    private final Path tempDir;

    // 当前周期的原始日志缓存（覆盖写，只保留最新一份）
    private String pendingRawRequest = null;
    private String pendingRawResponse = null;

    // @anchor: historyRecorder_constructor
    // 创建实例并准备 temp 目录与文件前缀
    public HistoryRecorder(ObjectMapper objectMapper, Consumer<String> logConsumer) {
        this.objectMapper = objectMapper;
        this.logConsumer = logConsumer;

        String timestamp = LocalDateTime.now().format(TIME_FMT);
        String uuid = UUID.randomUUID().toString().substring(0, 6);
        this.fileBase = timestamp + "_" + uuid;
        this.tempDir = Paths.get("./temp");

        try {
            if (!Files.exists(tempDir)) {
                Files.createDirectories(tempDir);
            }
        } catch (IOException e) {
            System.err.println("⚠️ temp 目录创建失败: " + e.getMessage());
        }
    }

    // @anchor: historyRecorder_cacheRawLog
    // 缓存本周期最新的 request 或 response 原文（覆盖写，暂不落盘）
    public void cacheRawLog(String type, String jsonContent) {
        if ("request".equals(type)) {
            pendingRawRequest = jsonContent;
        } else if ("response".equals(type)) {
            pendingRawResponse = jsonContent;
        }
    }

    // @anchor: historyRecorder_flushRawLog
    // 把缓存中的 request/response 追加写入 raw.jsonl 并清空缓存
    public void flushRawLog() {
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
        Path rawFile = rawFilePath();
        if (!Files.exists(rawFile)) return;

        try {
            String ts = LocalDateTime.now().format(TIME_FMT);
            String rawName = rawFile.getFileName().toString();
            String gzName = rawName.endsWith(".jsonl")
                    ? rawName.substring(0, rawName.length() - ".jsonl".length()) + "_" + ts + ".jsonl.gz"
                    : rawName + "_" + ts + ".gz";
            Path gzFile = rawFile.getParent().resolve(gzName);

            try (InputStream in = Files.newInputStream(rawFile);
                 OutputStream out = new GZIPOutputStream(Files.newOutputStream(gzFile))) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
            }
            Files.delete(rawFile);
            log("📦 [系统] 原始日志已压缩: " + gzFile.getFileName());
        } catch (IOException e) {
            System.err.println("⚠️ 压缩原始日志失败: " + e.getMessage());
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

    public void setLogConsumer(Consumer<String> consumer) {
        this.logConsumer = consumer;
    }
}