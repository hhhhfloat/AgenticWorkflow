// @anchor: historyRecorder_tot_desc
// 会话历史落盘器：消息流水账实时追加，原始 API 日志周期缓存后刷盘并可压缩归档
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

/**
 * 历史记录器 —— 负责会话历史的落盘与原始日志管理。
 * <p>
 * 两个文件（都在 ./temp/ 下）：
 * - {timestamp}_{uuid}_history.jsonl：消息流水账，实时追加
 * - {timestamp}_{uuid}_raw.jsonl：原始 API 请求/响应，按周期缓存后刷盘，归档时压缩为 .gz
 * <p>
 * 由 ContextManager 持有。职责独立：只处理 I/O，不关心上下文语义。
 */
// @anchor: historyRecorder_class
// 历史记录器：管理 history.jsonl / raw.jsonl 的写入与压缩
public class HistoryRecorder {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final ObjectMapper objectMapper;
    private Consumer<String> logConsumer;

    private Path historyFile;
    private final List<Long> historyOffsets = new ArrayList<>();

    // 当前周期的原始日志缓存（覆盖写，只保留最新一份）
    private String pendingRawRequest = null;
    private String pendingRawResponse = null;

    // @anchor: historyRecorder_constructor
    // 创建实例并初始化本次会话的 history.jsonl 文件
    public HistoryRecorder(ObjectMapper objectMapper, Consumer<String> logConsumer) {
        this.objectMapper = objectMapper;
        this.logConsumer = logConsumer;
        initHistoryFile();
    }

    // @anchor: historyRecorder_initHistoryFile
    // 在 ./temp 下按「时间戳_uuid」命名创建空的 history.jsonl
    private void initHistoryFile() {
        try {
            String timestamp = LocalDateTime.now().format(TIME_FMT);
            String uuid = UUID.randomUUID().toString().substring(0, 6);
            String fileName = timestamp + "_" + uuid + "_history.jsonl";

            Path tempDir = Paths.get("./temp");
            if (!Files.exists(tempDir)) {
                Files.createDirectories(tempDir);
            }
            this.historyFile = tempDir.resolve(fileName);
            Files.writeString(historyFile, "", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("⚠️ 历史文件初始化失败: " + e.getMessage());
        }
    }

    // @anchor: historyRecorder_appendMessage
    // 追加一条消息到 history.jsonl 并记录其偏移量
    /**
     * 追加一条消息到 history.jsonl。
     */
    public void appendMessage(java.util.Map<String, Object> msg) {
        if (historyFile == null) return;
        try {
            long offset = Files.size(historyFile);
            String line = objectMapper.writeValueAsString(msg) + System.lineSeparator();
            Files.writeString(historyFile, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            historyOffsets.add(offset);
        } catch (IOException e) {
            System.err.println("⚠️ 历史记录写入失败: " + e.getMessage());
        }
    }

    // @anchor: historyRecorder_cacheRawLog
    // 缓存本周期最新的 request 或 response 原文（覆盖写，暂不落盘）
    /**
     * 缓存当前周期的 request 或 response（覆盖写）。
     */
    public void cacheRawLog(String type, String jsonContent) {
        if ("request".equals(type)) {
            pendingRawRequest = jsonContent;
        } else if ("response".equals(type)) {
            pendingRawResponse = jsonContent;
        }
    }

    // @anchor: historyRecorder_flushRawLog
    // 把缓存中的 request/response 追加写入 raw.jsonl 并清空缓存
    /**
     * 把当前周期缓存的 request/response 落盘，然后清空缓存。
     * 在压缩发生、任务结束、或异常退出时调用。
     */
    public void flushRawLog() {
        if (pendingRawRequest == null && pendingRawResponse == null) return;
        if (historyFile == null) return;

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
    // 把 raw.jsonl 压缩为 .gz 并删除原文件（会话归档时调用）
    /**
     * 把 raw.jsonl 压缩为 .gz 并删除原文件。会话归档时调用。
     */
    public void compress() {
        if (historyFile == null) return;

        Path rawFile = rawFilePath();
        if (!Files.exists(rawFile)) return;

        try {
            String ts = LocalDateTime.now().format(TIME_FMT);
            String rawName = rawFile.getFileName().toString();
            String gzName = rawName.endsWith(".jsonl")
                    ? rawName.substring(0,rawName.length() - ".jsonl".length()) + "_" + ts + ".jsonl.gz"
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
    // 由 history 文件名推导出配套的 raw.jsonl 路径
    private Path rawFilePath() {
        String historyFileName = historyFile.getFileName().toString();
        String basePrefix = historyFileName.replace("_history.jsonl", "");
        return historyFile.getParent().resolve(basePrefix + "_raw.jsonl");
    }

    private void log(String message) {
        Consumer<String> consumer = this.logConsumer;
        if (consumer != null) {
            consumer.accept(message);
        } else {
            System.out.println(message);
        }
    }

    // ==================== Getter / Setter ====================

    public Path getHistoryFile() {
        return historyFile;
    }

    public List<Long> getHistoryOffsets() {
        return historyOffsets;
    }

    public void setLogConsumer(Consumer<String> consumer) {
        this.logConsumer = consumer;
    }
}
