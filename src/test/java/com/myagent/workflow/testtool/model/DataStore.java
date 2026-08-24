package com.myagent.workflow.testtool.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 数据存储管理 —— 管理测试历史记录和归档
 * 属于 Model 层
 */
public class DataStore {
    private final List<TestResult> history = new ArrayList<>();
    private final String storageDir;

    public DataStore() {
        this("token-tests");
    }

    public DataStore(String storageDir) {
        this.storageDir = storageDir;
        ensureStorageDir();
    }

    // ===== 目录管理 =====
    private void ensureStorageDir() {
        try {
            Path dir = Paths.get(storageDir);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            System.err.println("⚠️ 创建存储目录失败: " + e.getMessage());
        }
    }

    // ===== 数据管理 =====
    public void addResult(TestResult result) {
        if (result != null && result.isValid()) {
            history.add(result);
        }
    }

    public List<TestResult> getAllResults() {
        return Collections.unmodifiableList(history);
    }

    public void clearHistory() {
        history.clear();
    }

    public int getHistorySize() {
        return history.size();
    }

    // ===== 归档管理 =====
    public Path archiveFolder(String sourcePath, String label) {
        try {
            Path source = Paths.get(sourcePath);
            if (!Files.exists(source)) {
                return null;
            }

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path targetDir = Paths.get(storageDir);
            if (!Files.exists(targetDir)) {
                Files.createDirectories(targetDir);
            }

            Path target = targetDir.resolve(label + "_" + timestamp);
            Files.move(source, target);
            return target;
        } catch (IOException e) {
            System.err.println("归档失败: " + e.getMessage());
            return null;
        }
    }

    // ===== 导出功能 =====
    public void exportCSV(String filename) throws IOException {
        Path file = Paths.get(storageDir, filename);
        StringBuilder sb = new StringBuilder();
        sb.append("测试名称,迭代轮次,输入Token,输出Token,总成本,命中率,压缩次数\n");
        for (TestResult r : history) {
            sb.append(String.format("%s,%d,%d,%d,%.6f,%.2f,%d\n",
                    r.testName(),
                    r.totalIterations(),
                    r.totalPromptTokens(),
                    r.totalCompletionTokens(),
                    r.totalCost(),
                    r.cacheHitRate(),
                    r.compressionCount()
            ));
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }
}