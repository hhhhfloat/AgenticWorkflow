package com.myagent.workflow.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

// @anchor: referenceFinder_class
/**
 * 引用查找模块：负责 findReferences / findCallers / extractContext 三个工具的逻辑。
 * 文件收集 / 排除目录 / 扩展名过滤复用 SearchFileFilter 基础设施。
 */
public class ReferenceFinder {
    private static final Logger logger = LoggerFactory.getLogger(ReferenceFinder.class);

    private static final List<String> REF_EXCLUDED_DIRS =
            Arrays.asList("target", "build", ".git", ".idea", "node_modules",
                    "dist", "out", "bin", "logs");
    private static final List<String> REF_CODE_EXTENSIONS = Arrays.asList(
            ".java", ".js", ".jsx", ".ts", ".tsx", ".css", ".html", ".htm",
            ".py", ".go", ".rs", ".c", ".cpp", ".h", ".hpp", ".php", ".rb",
            ".swift", ".kt", ".scala", ".groovy", ".vue", ".svelte");

    private static final List<String> CALLER_EXCLUDED_DIRS =
            Arrays.asList("target", "build", ".git", ".idea", "node_modules",
                    "dist", "out", "bin");
    private static final List<String> CALLER_CODE_EXTENSIONS = Arrays.asList(
            ".java", ".js", ".jsx", ".ts", ".tsx", ".py", ".go", ".rs",
            ".c", ".cpp", ".h", ".php", ".rb", ".kt", ".vue");

    // @anchor: referenceFinder_findReferences
    String findReferences(String symbol, String path, String filePattern) {
        try {
            Path startPath = PathUtils.safeResolve(path != null ? path : ".");
            if (!Files.exists(startPath) || !Files.isDirectory(startPath)) {
                return "路径不存在或不是目录: " + path;
            }

            // 构建匹配模式：精确匹配符号，支持常见边界
            Pattern pattern = Pattern.compile(
                    "\\b" + Pattern.quote(symbol) + "\\b"
            );

            List<String> patterns = SearchFileFilter.parseFilePatterns(filePattern);

            // 存储结果
            Map<String, List<Map<String, Object>>> results = new LinkedHashMap<>();
            AtomicInteger totalMatches = new AtomicInteger(0);

            List<Path> files = SearchFileFilter.collectFiles(startPath, REF_EXCLUDED_DIRS);
            for (Path file : files) {
                try {
                    String fileName = file.getFileName().toString();
                    if (!SearchFileFilter.matchesExtension(fileName, patterns, REF_CODE_EXTENSIONS)) continue;

                    String relPathStr = startPath.relativize(file).toString().replace('\\', '/');
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    List<Map<String, Object>> fileMatches = new ArrayList<>();

                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        // 跳过注释和字符串字面量（简化版）
                        String trimmed = line.trim();
                        if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                            continue; // 跳过注释行
                        }
                        // 跳过 import/require 语句（这些不是“引用”）
                        if (trimmed.startsWith("import ") || trimmed.startsWith("require(")) {
                            continue;
                        }
                        // 跳过符号定义本身（如 function handleNumber() {）
                        if (trimmed.matches(".*\\b" + Pattern.quote(symbol) + "\\s*[=:(].*")) {
                            // 这可能是定义，跳过
                            continue;
                        }

                        if (pattern.matcher(line).find()) {
                            Map<String, Object> match = new LinkedHashMap<>();
                            match.put("line", i + 1);
                            String preview = line.trim();
                            if (preview.length() > 120) {
                                preview = preview.substring(0, 120) + "...";
                            }
                            match.put("preview", preview);
                            fileMatches.add(match);
                            totalMatches.incrementAndGet();
                        }
                    }

                    if (!fileMatches.isEmpty()) {
                        results.put(relPathStr, fileMatches);
                    }
                } catch (IOException ignored) {}
            }

            if (results.isEmpty()) {
                return "🔍 未找到符号 \"" + symbol + "\" 的引用。";
            }

            // 构建输出
            StringBuilder sb = new StringBuilder();
            int total = totalMatches.get();
            int fileCount = results.size();
            sb.append("🔍 找到 ").append(total).append(" 条引用，分布在 ").append(fileCount).append(" 个文件中：\n\n");

            for (Map.Entry<String, List<Map<String, Object>>> entry : results.entrySet()) {
                String filePath = entry.getKey();
                List<Map<String, Object>> matches = entry.getValue();
                sb.append("📄 ").append(filePath).append(" (").append(matches.size()).append(" 处)\n");
                for (Map<String, Object> match : matches) {
                    sb.append("  L").append(match.get("line")).append(": ")
                            .append(match.get("preview")).append("\n");
                }
                sb.append("\n");
            }

            return sb.toString();

        } catch (IOException e) {
            logger.error("查找引用失败", e);
            return "❌ 查找引用失败: " + e.getMessage();
        }
    }

    // @anchor: referenceFinder_findCallers
    String findCallers(String functionName, String path, String filePattern) {
        try {
            Path startPath = PathUtils.safeResolve(path != null ? path : ".");
            if (!Files.exists(startPath) || !Files.isDirectory(startPath)) {
                return "路径不存在或不是目录: " + path;
            }

            // 构建匹配模式：识别函数调用（包括链式调用、带参数等场景）
            // 匹配 foo()、foo(arg)、obj.foo()、this.foo()、foo?.()、foo() 等
            Pattern callerPattern = Pattern.compile(
                    "\\b" + Pattern.quote(functionName) +
                            "\\s*[\\(\\?]\\s*[^\\);]*\\)?"
            );

            // 排除定义模式：function foo()、foo = function()、foo: function()
            Pattern defPattern = Pattern.compile(
                    "(function\\s+" + Pattern.quote(functionName) + "\\s*\\()|" +
                            "(" + Pattern.quote(functionName) + "\\s*[=:]\\s*function\\s*\\()|" +
                            "(" + Pattern.quote(functionName) + "\\s*=\\s*\\()"
            );

            List<String> patterns = SearchFileFilter.parseFilePatterns(filePattern);

            // 结果存储：每个文件一个列表
            Map<String, List<Map<String, Object>>> results = new LinkedHashMap<>();
            AtomicInteger totalCallers = new AtomicInteger(0);

            List<Path> files = SearchFileFilter.collectFiles(startPath, CALLER_EXCLUDED_DIRS);
            for (Path file : files) {
                try {
                    String fileName = file.getFileName().toString();
                    if (!SearchFileFilter.matchesExtension(fileName, patterns, CALLER_CODE_EXTENSIONS)) continue;

                    String relPathStr = startPath.relativize(file).toString().replace('\\', '/');
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    List<Map<String, Object>> fileCallers = new ArrayList<>();

                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        String trimmed = line.trim();

                        // 跳过注释行
                        if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                            continue;
                        }
                        // 跳过 import/require
                        if (trimmed.startsWith("import ") || trimmed.startsWith("require(")) {
                            continue;
                        }
                        // 跳过定义行（function foo()、foo = function()、foo: function()）
                        if (defPattern.matcher(line).find()) {
                            continue;
                        }

                        // 匹配调用模式
                        java.util.regex.Matcher matcher = callerPattern.matcher(line);
                        if (matcher.find()) {
                            // 获取调用所在的函数上下文（分析调用发生的位置）
                            String context = extractContext(lines, i);

                            Map<String, Object> caller = new LinkedHashMap<>();
                            caller.put("line", i + 1);
                            String preview = line.trim();
                            if (preview.length() > 120) {
                                preview = preview.substring(0, 120) + "...";
                            }
                            caller.put("preview", preview);
                            caller.put("context", context);
                            fileCallers.add(caller);
                            totalCallers.incrementAndGet();
                        }
                    }

                    if (!fileCallers.isEmpty()) {
                        results.put(relPathStr, fileCallers);
                    }
                } catch (IOException ignored) {}
            }

            if (results.isEmpty()) {
                return "🔍 未找到函数 \"" + functionName + "\" 的调用点。";
            }

            // 构建输出
            StringBuilder sb = new StringBuilder();
            int total = totalCallers.get();
            int fileCount = results.size();
            sb.append("🔍 找到 ").append(total).append(" 个调用点，分布在 ").append(fileCount).append(" 个文件中：\n\n");

            for (Map.Entry<String, List<Map<String, Object>>> entry : results.entrySet()) {
                String filePath = entry.getKey();
                List<Map<String, Object>> callers = entry.getValue();
                sb.append("📄 ").append(filePath).append(" (").append(callers.size()).append(" 处调用)\n");
                for (Map<String, Object> caller : callers) {
                    sb.append("  L").append(caller.get("line")).append(": ")
                            .append(caller.get("preview"));
                    String context = (String) caller.get("context");
                    if (context != null && !context.isEmpty()) {
                        sb.append("  [在 ").append(context).append(" 中]");
                    }
                    sb.append("\n");
                }
                sb.append("\n");
            }

            return sb.toString();

        } catch (IOException e) {
            logger.error("查找调用者失败", e);
            return "❌ 查找调用者失败: " + e.getMessage();
        }
    }

    // ===== 内部辅助 =====

    // @anchor: referenceFinder_extractContext
    private String extractContext(List<String> lines, int lineIndex) {
        // 向上查找最近的函数定义
        int searchLimit = Math.max(0, lineIndex - 20);
        for (int i = lineIndex - 1; i >= searchLimit; i--) {
            String line = lines.get(i).trim();
            // 检测函数定义：function foo()、foo() {、foo = function()、foo: function()
            if (line.matches(".*\\bfunction\\s+\\w+\\s*\\(") ||
                    line.matches("\\w+\\s*[=:]\\s*function\\s*\\(") ||
                    line.matches("\\w+\\s*\\(\\s*[^)]*\\s*\\)\\s*\\{")) {
                // 提取函数名
                java.util.regex.Matcher m = Pattern.compile("\\b(\\w+)\\s*[=:(\\.]").matcher(line);
                if (m.find()) {
                    return m.group(1);
                }
                return line.trim();
            }
        }
        return "全局";
    }
}
