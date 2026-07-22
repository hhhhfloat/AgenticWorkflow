package com.myagent.workflow.security;

import com.myagent.workflow.security.filters.WhitelistFilter;
import com.myagent.workflow.security.parsers.*;
import com.myagent.workflow.security.rules.Rule;
import com.myagent.workflow.security.rules.RuleRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;

/**
 * 安全扫描器 —— 独立工具类，用于检测代码中的危险模式。
 * 使用单例模式，全局共享规则配置。
 */
public class SecurityScanner {
    private static final Logger logger = LoggerFactory.getLogger(SecurityScanner.class);
    private static final SecurityScanner INSTANCE = new SecurityScanner();

    private final RuleRegistry ruleRegistry = RuleRegistry.getInstance();
    private final WhitelistFilter whitelistFilter = new WhitelistFilter();

    private static final List<String> SKIP_DIR_NAMES = List.of(
            "node_modules", ".git", "target", "__pycache__",
            "build", "dist", ".idea", ".vscode", "out", "bin", "obj"
    );

    private final Map<Path, Long> scanCache = new ConcurrentHashMap<>();

    private SecurityScanner() {
        // 私有构造，防止外部实例化
        logger.info("🔒 SecurityScanner 初始化完成");
    }

    public static SecurityScanner getInstance() {
        return INSTANCE;
    }

    private boolean shouldSkipDirectory(Path dir) {
        String dirName = dir.getFileName().toString().toLowerCase();
        return SKIP_DIR_NAMES.contains(dirName);
    }

    /**
     * 对指定文件进行安全扫描
     * @param filePath 文件路径（需存在且可读）
     * @param language 语言标识（java/python/cpp/node/html 等）
     * @return 扫描结果
     */
    public ScanResult scan(Path filePath, String language) {
        // 1. 文件存在性检查
        if (!Files.exists(filePath) || !Files.isReadable(filePath)) {
            logger.warn("文件不存在或不可读: {}", filePath);
            return ScanResult.failed(
                    List.of(new Violation(filePath.toString(),0, "FILE_NOT_FOUND", "文件不存在或不可读", Severity.ERROR, "请确认文件路径")),
                    "安全扫描无法进行"
            );
        }

        // 2. 读取文件内容
        String source;
        try {
            source = Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("读取文件失败: {}", filePath, e);
            return ScanResult.failed(
                    List.of(new Violation(filePath.toString(),0, "IO_ERROR", "读取文件异常: " + e.getMessage(), Severity.ERROR, "请检查文件权限")),
                    "安全扫描异常"
            );
        }

        // 3. 根据语言选择解析器
        CodeParser parser = getParser(language);
        List<CodeLine> effectiveLines = parser.extractEffectiveLines(source);

        // 4. 逐行匹配规则
        List<Violation> violations = new ArrayList<>();
        for (CodeLine line : effectiveLines) {
            // 白名单过滤（如 import os / #include <cstdlib>）
            if (whitelistFilter.isWhitelisted(line, language)) {
                continue;
            }

            for (Rule rule : ruleRegistry.getRules()) {
                Matcher matcher = rule.getPattern().matcher(line.content());
                if (matcher.find()) {
                    String matched = matcher.group();
                    violations.add(new Violation(
                            filePath.toString(),
                            line.lineNumber(),
                            rule.getId(),
                            matched,
                            rule.getSeverity(),
                            rule.getSuggestion()
                    ));
                    // 一行只记录第一个匹配的规则，避免重复刷屏
                    break;
                }
            }
        }

        // 5. 返回结果
        if (violations.isEmpty()) {
            logger.info("✅ 安全扫描通过: {}", filePath.getFileName());
            return ScanResult.success();
        } else {
            logger.warn("❌ 安全扫描发现 {} 项违规: {}", violations.size(), filePath.getFileName());
            return ScanResult.failed(violations, "检测到潜在危险代码，请根据建议修改后重试。");
        }
    }

    /**
     * 根据语言选择对应的代码解析器
     */
    private CodeParser getParser(String language) {
        if (language == null) return new JavaParser();
        return switch (language.toLowerCase()) {
            case "java" -> new JavaParser();
            case "python" -> new PythonParser();
            case "cpp", "c++" -> new CppParser();
            case "node", "javascript", "js" -> new JavaParser(); // Node/JS 使用类似 Java 注释语法
            default -> new JavaParser();
        };
    }

    /**
     * 便捷方法：自动检测语言（根据文件扩展名）
     */
    public ScanResult scan(Path filePath) {
        String filename = filePath.getFileName().toString().toLowerCase();
        String language = detectLanguage(filename);
        return scan(filePath, language);
    }

    /**
     * 递归扫描目录下所有源代码文件
     * @param dirPath 目录路径（必须存在且可读）
     * @return 聚合的扫描结果
     */
    public ScanResult scanDirectory(Path dirPath) {
        if (!Files.isDirectory(dirPath)) {
            return scan(dirPath);
        }

        logger.info("📂 开始递归扫描目录: {}", dirPath);
        List<Violation> allViolations = new ArrayList<>();
        AtomicInteger fileCount = new AtomicInteger(0);

        try {
            scanDirectoryRecursive(dirPath, allViolations, fileCount);
        } catch (IOException e) {
            logger.error("递归扫描目录异常: {}", dirPath, e);
            return ScanResult.failed(
                    List.of(new Violation(
                            dirPath.toString(),
                            0,
                            "SCAN_ERROR",
                            "扫描目录异常: " + e.getMessage(),
                            Severity.ERROR,
                            "请检查目录权限"
                    )),
                    "安全扫描中断"
            );
        }

        if (allViolations.isEmpty()) {
            logger.info("✅ 目录扫描通过: {} 个文件均安全", fileCount.get());
            return ScanResult.success();
        } else {
            String summary = String.format("共扫描 %d 个文件，发现 %d 项违规。", fileCount.get(), allViolations.size());
            return ScanResult.failed(allViolations, summary);
        }
    }

    private void scanDirectoryRecursive(Path dir, List<Violation> allViolations, AtomicInteger fileCount) throws IOException {
        try (var stream = Files.list(dir)) {
            for (Path entry : stream.toList()) {
                if (Files.isDirectory(entry)) {
                    if (shouldSkipDirectory(entry)) {
                        logger.debug("⏩ 跳过目录: {}", entry.getFileName());
                        continue;
                    }
                    scanDirectoryRecursive(entry, allViolations, fileCount);
                } else if (Files.isRegularFile(entry)) {
                    String language = detectLanguage(entry.getFileName().toString());
                    if (language != null) {
                        // 🔥 新增：缓存命中检查
                        if (isFileUnchanged(entry)) {
                            logger.debug("⏩ 跳过未变更文件: {}", entry.getFileName());
                            fileCount.incrementAndGet(); // 仍然计入统计
                            continue;
                        }

                        fileCount.incrementAndGet();
                        ScanResult result = scan(entry, language);
                        if (!result.passed()) {
                            allViolations.addAll(result.violations());
                            logger.warn("  发现违规: {}", entry);
                            // ❌ 扫描未通过，不缓存
                        } else {
                            // ✅ 扫描通过，更新缓存
                            updateCache(entry);
                        }
                    }
                }
            }
        }
    }

    /**
     * 根据文件名检测语言类型
     */
    private String detectLanguage(String filename) {
        filename = filename.toLowerCase();
        if (filename.endsWith(".java")) return "java";
        if (filename.endsWith(".py")) return "python";
        if (filename.endsWith(".cpp") || filename.endsWith(".cc") || filename.endsWith(".cxx")) return "cpp";
        if (filename.endsWith(".js")) return "js";
        if (filename.endsWith(".html") || filename.endsWith(".htm")) return "html";
        return null;
    }
    /**
     * 检查文件是否自上次扫描后未发生变化
     * @return true 表示可以跳过扫描（视为安全）
     */
    private boolean isFileUnchanged(Path file) {
        Long cachedTime = scanCache.get(file);
        if (cachedTime == null) {
            return false;
        }
        try {
            long currentTime = Files.getLastModifiedTime(file).toMillis();
            return currentTime == cachedTime;
        } catch (IOException e) {
            // 文件可能已被删除，从缓存中移除
            scanCache.remove(file);
            return false;
        }
    }

    /**
     * 更新文件缓存（仅当扫描通过时调用）
     */
    private void updateCache(Path file) {
        try {
            long mtime = Files.getLastModifiedTime(file).toMillis();
            scanCache.put(file, mtime);
        } catch (IOException e) {
            // 忽略
        }
    }
}