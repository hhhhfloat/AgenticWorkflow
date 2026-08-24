package com.myagent.workflow.testtool.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * 日志控制器 —— 管理日志的收集、存储和分发
 * 属于 Controller 层
 */
public class LogController {

    private final List<String> logHistory = new ArrayList<>();
    private final StringBuilder logBuffer = new StringBuilder();
    private Consumer<String> logConsumer = null;
    private Path logFile = null;

    private static final int MAX_LOG_LINES = 5000;  // 内存中最多保留 5000 行

    public LogController() {
        // 默认无操作
    }

    /**
     * 设置日志消费者（由 View 层提供，用于更新 UI）
     */
    public void setLogConsumer(Consumer<String> consumer) {
        this.logConsumer = consumer;
    }

    /**
     * 追加一条日志
     */
    public void append(String message) {

        System.out.println("[LogController] " + message);  // ← 加这行

        if (message == null) return;

        // 写入内存
        logHistory.add(message);
        logBuffer.append(message).append("\n");

        // 限制内存大小，防止溢出
        if (logHistory.size() > MAX_LOG_LINES) {
            int excess = logHistory.size() - MAX_LOG_LINES;
            logHistory.subList(0, excess).clear();
            // 重建 buffer（保留最近 5000 行）
            rebuildBuffer();
        }

        // 通知 View 层
        if (logConsumer != null) {
            logConsumer.accept(message);
        }

        // 写入文件（如果已设置）
        if (logFile != null) {
            try {
                Files.writeString(logFile, message + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("⚠️ 写入日志文件失败: " + e.getMessage());
            }
        }
    }

    /**
     * 获取所有日志
     */
    public List<String> getLogHistory() {
        return Collections.unmodifiableList(logHistory);
    }

    /**
     * 获取完整日志文本
     */
    public String getFullLog() {
        return logBuffer.toString();
    }

    /**
     * 清空日志
     */
    public void clear() {
        logHistory.clear();
        logBuffer.setLength(0);
        if (logConsumer != null) {
            logConsumer.accept("[系统] 日志已清空");
        }
    }

    /**
     * 设置日志文件路径
     */
    public void setLogFile(String filePath) {
        try {
            this.logFile = Paths.get(filePath);
            if (!Files.exists(logFile.getParent())) {
                Files.createDirectories(logFile.getParent());
            }
            // 清空或创建文件
            Files.writeString(logFile, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            append("[系统] 日志文件: " + filePath);
        } catch (IOException e) {
            System.err.println("⚠️ 设置日志文件失败: " + e.getMessage());
        }
    }

    /**
     * 保存日志到文件
     */
    public void saveToFile(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path.getParent())) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, logBuffer.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * 生成带时间戳的日志文件名
     */
    public static String generateLogFileName(String prefix) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return prefix + "_" + timestamp + ".log";
    }

    /**
     * 重建日志缓冲区（用于截断后恢复）
     */
    private void rebuildBuffer() {
        logBuffer.setLength(0);
        for (String line : logHistory) {
            logBuffer.append(line).append("\n");
        }
    }
}