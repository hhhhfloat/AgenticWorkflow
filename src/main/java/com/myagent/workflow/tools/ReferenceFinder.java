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
// 符号引用查找实现
/**
 * 引用查找模块：负责 findReferences / findCallers / extractContext 三个工具的逻辑。
 * 文件收集 / 排除目录 / 扩展名过滤复用 SearchFileFilter 基础设施。
 */
public class ReferenceFinder {
    private static final Logger logger = LoggerFactory.getLogger(ReferenceFinder.class);

    private static final List<String> REF_CODE_EXTENSIONS = Arrays.asList(
            ".java", ".js", ".jsx", ".ts", ".tsx", ".css", ".html", ".htm",
            ".py", ".go", ".rs", ".c", ".cpp", ".h", ".hpp", ".php", ".rb",
            ".swift", ".kt", ".scala", ".groovy", ".vue", ".svelte");

    private static final List<String> CALLER_CODE_EXTENSIONS = Arrays.asList(
            ".java", ".js", ".jsx", ".ts", ".tsx", ".py", ".go", ".rs",
            ".c", ".cpp", ".h", ".php", ".rb", ".kt", ".vue");

    // @anchor: referenceFinder_stripStringsAndComments
// 剥离行内字符串与注释，用等长空格占位以保留列号。
// inBlockComment[0] 作为跨行块注释状态，传入传出。
    private static String stripStringsAndComments(String line, boolean[] inBlockComment) {
        StringBuilder sb = new StringBuilder(line.length());
        boolean inBlock = inBlockComment[0];
        char quote = 0;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);

            if (inBlock) {
                if (c == '*' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                    inBlock = false;
                    sb.append("  ");
                    i += 2;
                    continue;
                }
                sb.append(' ');
                i++;
                continue;
            }

            if (quote != 0) {
                if (c == '\\' && i + 1 < line.length()) {
                    sb.append("  ");
                    i += 2;
                    continue;
                }
                if (c == quote) quote = 0;
                sb.append(' ');
                i++;
                continue;
            }

            // 块注释开始
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                inBlock = true;
                sb.append("  ");
                i += 2;
                continue;
            }
            // // 行注释
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                while (i < line.length()) { sb.append(' '); i++; }
                break;
            }
            // # 行注释（Python/Shell）：行首或前导空白后
            if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                while (i < line.length()) { sb.append(' '); i++; }
                break;
            }
            // 字符串开始
            if (c == '"' || c == '\'' || c == '`') {
                quote = c;
                sb.append(' ');
                i++;
                continue;
            }

            sb.append(c);
            i++;
        }
        inBlockComment[0] = inBlock;
        return sb.toString();
    }

    // @anchor: referenceFinder_isControlKeyword
// 排除控制流关键字，避免在 extractContext 中被误判为函数名
    private static boolean isControlKeyword(String name) {
        switch (name) {
            case "if": case "else": case "for": case "while": case "switch":
            case "case": case "catch": case "try": case "do": case "return":
            case "new": case "typeof": case "instanceof": case "await":
            case "yield": case "throw": case "super": case "this":
                return true;
            default:
                return false;
        }
    }

    // @anchor: referenceFinder_findReferences
// 查找某符号在项目内的所有引用位置
    String findReferences(String symbol, String path, String filePattern) {
        try {
            Path startPath = PathUtils.safeResolve(path != null ? path : ".");
            if (!Files.exists(startPath) || !Files.isDirectory(startPath)) {
                return "路径不存在或不是目录: " + path;
            }

            // 构建匹配模式：精确匹配符号，支持常见边界
            Pattern pattern = Pattern.compile(
                    "(?<![\\w$])" + Pattern.quote(symbol) + "(?![\\w$])",
                    Pattern.UNICODE_CHARACTER_CLASS
            );

            // @anchor: referenceFinder_defPattern
            // 明确的定义形式（JS 风格）；用于跳过定义行，其余一律视为引用
            Pattern defPattern = Pattern.compile(
                    "(^|\\s)function\\s+" + Pattern.quote(symbol) + "\\s*\\(" +
                            "|" + Pattern.quote(symbol) + "\\s*[=:]\\s*function\\s*\\(" +
                            "|" + Pattern.quote(symbol) + "\\s*=\\s*\\("
            );

            List<String> patterns = SearchFileFilter.parseFilePatterns(filePattern);

            // 存储结果
            Map<String, List<Map<String, Object>>> results = new LinkedHashMap<>();
            AtomicInteger totalMatches = new AtomicInteger(0);

            List<Path> files = SearchFileFilter.collectFiles(startPath, SearchFileFilter.DEFAULT_EXCLUDED_DIRS, SearchFileFilter.DEFAULT_EXCLUDED_FILES);
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
                        // 只跳过明确像定义的行（JS 风格：function foo / foo = function / foo: function / foo = (）
                        if(defPattern.matcher(line).find()){
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
// 查找某函数的调用点及上下文
    String findCallers(String functionName, String path, String filePattern) {
        try {
            Path startPath = PathUtils.safeResolve(path != null ? path : ".");
            if (!Files.exists(startPath) || !Files.isDirectory(startPath)) {
                return "路径不存在或不是目录: " + path;
            }

            String quoted = Pattern.quote(functionName);

            // 调用形态：
            //   NAME(      —— 直接调用
            //   NAME?.(    —— 可选链调用
            //   NAME.call( / NAME.apply( —— 显式调用
            // 优先匹配最长形态，避免 .call 被 NAME( 抢走
            Pattern callerPattern = Pattern.compile(
                    "(?<![\\w$])" + quoted + "\\s*\\.\\s*(?:call|apply)\\s*\\(" +
                            "|(?<![\\w$])" + quoted + "\\s*\\?\\.\\s*\\(" +
                            "|(?<![\\w$])" + quoted + "\\s*\\(",
                    Pattern.UNICODE_CHARACTER_CLASS
            );

            // 定义形态（保守收窄）：
            //   function NAME(
            //   NAME = function( / NAME: function(
            //   NAME = (
            //   def NAME(          —— Python
            //   行首（可选修饰符）后的 NAME(...) { —— ES6 简写方法 / 类方法 / Java 方法签名
            //   修饰符列表后的 NAME(  —— Java 常见写法
            Pattern defPattern = Pattern.compile(
                    "(^|[\\s;(])function\\s+" + quoted + "\\s*\\(" +
                            "|" + quoted + "\\s*[=:]\\s*function\\s*\\(" +
                            "|" + quoted + "\\s*=\\s*\\(" +
                            "|\\bdef\\s+" + quoted + "\\s*\\(" +
                            "|^\\s*(?:[\\w@<>\\[\\],\\s]+\\s)?" + quoted
                            + "\\s*\\([^)]*\\)\\s*(?:throws\\s+[\\w\\s,]+\\s*)?\\{" +
                            "|^\\s*(?:public|private|protected|static|final|abstract|"
                            + "synchronized)\\b[^;{}]*\\b" + quoted + "\\s*\\(",
                    Pattern.UNICODE_CHARACTER_CLASS
            );

            List<String> patterns = SearchFileFilter.parseFilePatterns(filePattern);

            Map<String, List<Map<String, Object>>> results = new LinkedHashMap<>();
            AtomicInteger totalCallers = new AtomicInteger(0);

            List<Path> files = SearchFileFilter.collectFiles(
                    startPath,
                    SearchFileFilter.DEFAULT_EXCLUDED_DIRS,
                    SearchFileFilter.DEFAULT_EXCLUDED_FILES);

            for (Path file : files) {
                try {
                    String fileName = file.getFileName().toString();
                    if (!SearchFileFilter.matchesExtension(fileName, patterns, CALLER_CODE_EXTENSIONS)) continue;

                    String relPathStr = startPath.relativize(file).toString().replace('\\', '/');
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    List<Map<String, Object>> fileCallers = new ArrayList<>();
                    boolean[] inBlockComment = new boolean[]{false};

                    for (int i = 0; i < lines.size(); i++) {
                        String raw = lines.get(i);
                        String stripped = stripStringsAndComments(raw, inBlockComment);

                        // 整行全是注释/字符串/空白 → 跳过
                        if (stripped.trim().isEmpty()) continue;

                        String trimmed = stripped.trim();
                        // 跳过 import / require
                        if (trimmed.startsWith("import ") || trimmed.startsWith("require(")) continue;
                        // 跳过定义行
                        if (defPattern.matcher(stripped).find()) continue;

                        // 匹配调用（同一行可能有多处）
                        java.util.regex.Matcher m = callerPattern.matcher(stripped);
                        String context = null;
                        while (m.find()) {
                            if (context == null) {
                                context = extractContext(lines, i);
                            }
                            Map<String, Object> caller = new LinkedHashMap<>();
                            caller.put("line", i + 1);
                            String preview = raw.trim();
                            if (preview.length() > 120) preview = preview.substring(0, 120) + "...";
                            // 标注本行第几处
                            caller.put("preview", "（第 " + (fileCallers.size() + 1) + " 处）" + preview);
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

            StringBuilder sb = new StringBuilder();
            int total = totalCallers.get();
            int fileCount = results.size();
            sb.append("🔍 找到 ").append(total).append(" 个调用点，分布在 ")
                    .append(fileCount).append(" 个文件中：\n\n");

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
// 向上查找最近的函数定义行，提取函数名作为上下文
    private static final Pattern CONTEXT_DEF_LINE = Pattern.compile(
            "\\bfunction\\s+(\\w+)\\s*\\(" +
                    "|(\\w+)\\s*[=:]\\s*function\\s*\\(" +
                    "|\\bdef\\s+(\\w+)\\s*\\(" +
                    "|(\\w+)\\s*\\([^)]*\\)\\s*\\{",
            Pattern.UNICODE_CHARACTER_CLASS
    );

    private String extractContext(List<String> lines, int lineIndex) {
        int searchLimit = Math.max(0, lineIndex - 30);
        for (int i = lineIndex - 1; i >= searchLimit; i--) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("*")) continue;
            java.util.regex.Matcher m = CONTEXT_DEF_LINE.matcher(line);
            if (m.find()) {
                for (int g = 1; g <= m.groupCount(); g++) {
                    String name = m.group(g);
                    if (name != null && !isControlKeyword(name)) {
                        return name;
                    }
                }
            }
        }
        return "全局";
    }
}
