package com.myagent.workflow.tools;

import com.myagent.workflow.core.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public class FileOperator {
    private static final Logger logger = LoggerFactory.getLogger(FileOperator.class);
    private Consumer<String> logConsumer;


    public FileOperator() {
    }


    // ===== 工具方法 =====
    public String writeFile(String filename, String code) {
        try {
            Path filePath = PathUtils.safeResolve(filename);
            Files.createDirectories(filePath.getParent());
            try (FileWriter writer = new FileWriter(filePath.toFile())) {
                writer.write(code);
            }
            return "✅ " + filename;
        } catch (IOException e) {
            logger.error("写入文件失败", e);
            return "写入文件失败: " + e.getMessage();
        }
    }


    public String readFile(String filename) {
        try {
            Path filePath = PathUtils.safeResolve(filename);
            if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
                return "文件不存在或是一个目录: " + filename;
            }
            String content = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
            final int MAX_LENGTH = 50000;
            if (content.length() > MAX_LENGTH) {
                content = content.substring(0, MAX_LENGTH) + "\n... [文件过长，已截断。如需完整内容请告知]";
            }
            return "📄 文件 " + filename + " 内容已阅读\n" + content;
        } catch (IOException e) {
            logger.error("读取文件失败", e);
            return "读取文件失败: " + e.getMessage();
        }
    }

    public String deleteFile(String filename) {
        try {
            Path filePath = PathUtils.safeResolve(filename);
            if (!Files.exists(filePath)) {
                return "文件不存在: " + filename;
            }
            if (Files.isDirectory(filePath)) {
                return "⚠️ 不能删除目录，请仅删除单个文件（如需删除目录，可手动操作）。";
            }
            Files.delete(filePath);
            return "✅ 已删除文件: " + filename;
        } catch (IOException e) {
            logger.error("删除文件失败", e);
            return "删除文件失败: " + e.getMessage();
        }
    }


    public String listDirectory(String path, boolean recursive) {
        if (path == null || path.trim().isEmpty()) {
            path = ".";
        }
        try {
            // 【唯一改动】将 Paths.get(sandboxDir, path) 替换为 safeResolve(path)
            Path dirPath = PathUtils.safeResolve(path);
            if (!Files.exists(dirPath) || !Files.isDirectory(dirPath)) {
                return "路径不存在或不是目录: " + path;
            }

            if (!recursive) {
                StringBuilder sb = new StringBuilder();
                sb.append("📁 目录 ").append(path).append(" 的内容：\n");
                Files.list(dirPath).forEach(p -> {
                    String name = p.getFileName().toString();
                    String type = Files.isDirectory(p) ? "📁" : "📄";
                    sb.append(type).append(" ").append(name).append("\n");
                });
                return sb.toString();
            }

            StringBuilder sb = new StringBuilder();
            sb.append("📁 ").append(path).append("/\n");
            buildTree(sb, dirPath, "", true);
            return sb.toString();

        } catch (IOException e) {
            logger.error("列出目录失败", e);
            return "列出目录失败: " + e.getMessage();
        }
    }

    // 辅助递归
    private void buildTree(StringBuilder sb, Path dir, String indent, boolean isLast) {
        try {
            List<Path> entries = Files.list(dir)
                    .sorted((a, b) -> {
                        boolean aDir = Files.isDirectory(a);
                        boolean bDir = Files.isDirectory(b);
                        if (aDir && !bDir) return -1;
                        if (!aDir && bDir) return 1;
                        return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
                    })
                    .toList();

            int count = entries.size();
            for (int i = 0; i < count; i++) {
                Path p = entries.get(i);
                String name = p.getFileName().toString();
                boolean isLastEntry = (i == count - 1);
                String prefix = (isLastEntry ? "└── " : "├── ");

                if (Files.isDirectory(p)) {
                    long subCount;
                    try (var stream = Files.list(p)) {
                        subCount = stream.count();
                    }
                    sb.append(indent).append(prefix).append("📁 ").append(name);
                    if (subCount > 0) {
                        sb.append(" (").append(subCount).append(" 项)");
                    }
                    sb.append("\n");
                    String nextIndent = indent + (isLastEntry ? "    " : "│   ");
                    buildTree(sb, p, nextIndent, isLastEntry);
                } else {
                    sb.append(indent).append(prefix).append("📄 ").append(name).append("\n");
                }
            }
        } catch (IOException ignored) {
            // 忽略无法读取的目录
        }
    }

}