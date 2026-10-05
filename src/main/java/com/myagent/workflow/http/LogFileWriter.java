// @anchor: logFileWriter_intro
// 运行日志写入器：在 HistoryOutput/{sessionId}/ 下按时间戳建日志文件并逐行落盘
package com.myagent.workflow.http;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

// @anchor: logFileWriter_class
// 日志写入器：管理单个日志文件的生命周期（创建/追加/关闭/清理过期）
public class LogFileWriter implements AutoCloseable {

    // @anchor: logFileWriter_constants
    // 日志根目录、文件名时间戳格式与保留天数
    private static final Path ROOT_DIR = Paths.get("./HistoryOutput");
    private static final DateTimeFormatter FILE_TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    // 开发期，手动清理即可，因此保留很久
    private static final int RETENTION_DAYS = 3000;

    private final Path logFile;
    private final BufferedWriter writer;

    // @anchor: logFileWriter_constructor
    // 在 HistoryOutput/{sessionId}/ 下创建带时间戳的日志文件，写入需求与开始标记
    public LogFileWriter(String sessionId, String prompt) throws IOException {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IOException("sessionId 不能为空");
        }
        // 会话目录：HistoryOutput/{sessionId}/
        Path sessionDir = ROOT_DIR.resolve(sessionId);
        if (!Files.exists(sessionDir)) {
            Files.createDirectories(sessionDir);
        }

        cleanOldLogs();

        String timestamp = LocalDateTime.now().format(FILE_TS_FMT);
        logFile = sessionDir.resolve(timestamp + ".log");
        writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8);

        writer.write("📝 本次需求: " + prompt);
        writer.newLine();
        writer.write("--- 开始执行 ---");
        writer.newLine();
        writer.flush();
    }

    // @anchor: logFileWriter_cleanOldLogs
    // 遍历 HistoryOutput 下的会话子目录，按文件名日期清理超过保留期的旧日志
    private static void cleanOldLogs() throws IOException {
        if (!Files.exists(ROOT_DIR)) return;

        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        try (Stream<Path> sessionDirs = Files.list(ROOT_DIR)) {
            sessionDirs.filter(Files::isDirectory)
                    .forEach(dir -> cleanOneDir(dir, cutoff));
        }
    }

    private static void cleanOneDir(Path dir, LocalDateTime cutoff) {
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".log")).forEach(p -> {
                try {
                    String name = p.getFileName().toString();
                    // 文件名格式：2026-10-02_14-30-25.log，前 10 位是日期
                    String datePart = name.substring(0, 10);
                    LocalDateTime fileDate = LocalDateTime.parse(datePart + "T00:00:00");
                    if (fileDate.isBefore(cutoff)) {
                        Files.delete(p);
                        System.out.println("🗑️ 已删除旧日志: " + p);
                    }
                } catch (Exception ignored) {}
            });
        } catch (IOException ignored) {}
    }

    // @anchor: logFileWriter_write
    // 追加一行日志并立即刷新
    public void write(String message) throws IOException {
        writer.write(message);
        writer.newLine();
        writer.flush();
    }

    // @anchor: logFileWriter_close
    // 关闭底层写入流
    @Override
    public void close() throws IOException {
        if (writer != null) {
            writer.close();
        }
    }

    // @anchor: logFileWriter_getLogFilePath
    // 返回当前日志文件路径
    public Path getLogFilePath() {
        return logFile;
    }
}