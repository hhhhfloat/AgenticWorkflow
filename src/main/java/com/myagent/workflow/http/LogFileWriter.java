// @anchor: logFileWriter_tot_desc
// 运行日志写入器：在 HistoryOutput 下按时间戳建日志文件并逐行落盘
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
    private final Path logFile;
    private final BufferedWriter writer;

    // @anchor: logFileWriter_constructor
    // 创建日志目录并新建带时间戳的日志文件，写入需求与开始标记
    public LogFileWriter(String prompt) throws IOException {
        // 创建 HistoryOutput 目录
        Path logDir = Paths.get("./HistoryOutput");
        if (!Files.exists(logDir)) {
            Files.createDirectories(logDir);
        }

        cleanOldLogs();

        // 生成文件名：2026-07-11_14-23-45.log
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        logFile = logDir.resolve(timestamp + ".log");
        writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8);

        // 写入提示词作为第一行
        writer.write("📝 本次需求: " + prompt);
        writer.newLine();
        writer.write("--- 开始执行 ---");
        writer.newLine();
        writer.flush();
    }

    // @anchor: logFileWriter_cleanOldLogs
    // 按文件名日期清理超过保留期限的旧日志
    private static void cleanOldLogs() throws IOException {
        Path logDir = Paths.get("./HistoryOutput");
        if (!Files.exists(logDir)) return;

        LocalDateTime cutoff = LocalDateTime.now().minusDays(3000);
        try (Stream<Path> files = Files.list(logDir)) {
            files.filter(p -> p.toString().endsWith(".log"))
                    .forEach(p -> {
                        try {
                            // 从文件名解析日期（格式：2026-07-11_14-23-45.log）
                            String name = p.getFileName().toString();
                            String datePart = name.substring(0, 10); // "2026-07-11"
                            LocalDateTime fileDate = LocalDateTime.parse(datePart + "T00:00:00");
                            if (fileDate.isBefore(cutoff)) {
                                Files.delete(p);
                                System.out.println("🗑️ 已删除旧日志: " + p.getFileName());
                            }
                        } catch (Exception ignored) {}
                    });
        }
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
