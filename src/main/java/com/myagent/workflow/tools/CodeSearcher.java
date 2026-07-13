package com.myagent.workflow.tools;

import com.myagent.workflow.model.AnchorLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class CodeSearcher {
    private final AnchorManager anchorMgr;
    private static final Logger logger = LoggerFactory.getLogger(CodeSearcher.class);

    public CodeSearcher(AnchorManager anchorMgr) {
        this.anchorMgr = anchorMgr;
    }


    // ===== 工具方法 =====
    String searchText(String keyword, String filePattern, String path) {
        try {
            Path startPath = PathUtils.safeResolve(path);
            if (!Files.exists(startPath) || !Files.isDirectory(startPath)) {
                return "路径不存在或不是目录: " + path;
            }

            Pattern pattern;
            try {
                pattern = Pattern.compile(keyword);
            } catch (PatternSyntaxException e) {
                return "关键词正则表达式错误: " + e.getMessage() + "。如需普通文本搜索，请转义特殊字符。";
            }

            List<String> excludedDirs = Arrays.asList("target", "build", ".git", ".idea", "node_modules");
            List<String> textExtensions = Arrays.asList(".java", ".html", ".htm", ".css", ".js", ".jsx", ".ts", ".tsx",
                    ".txt", ".xml", ".json", ".md", ".properties", ".yml", ".yaml", ".sh", ".bat", ".gradle", ".sql");

            List<String> patterns = new ArrayList<>();
            if (filePattern != null && !filePattern.trim().isEmpty() && !".*".equals(filePattern)) {
                String[] parts = filePattern.split(",");
                for (String p : parts) {
                    p = p.trim();
                    if (p.startsWith("*.")) {
                        patterns.add(p.substring(1));
                    } else {
                        patterns.add(p);
                    }
                }
            }

            AtomicInteger matchCount = new AtomicInteger(0);
            AtomicInteger resultCount = new AtomicInteger(0);
            List<String> results = Collections.synchronizedList(new ArrayList<>());
            final int MAX_RESULTS = 30;

            Files.walk(startPath)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        if (resultCount.get() >= MAX_RESULTS) return;
                        try {
                            Path relativePath = startPath.relativize(file);
                            String relPathStr = relativePath.toString().replace('\\', '/');

                            for (String excluded : excludedDirs) {
                                if (relPathStr.startsWith(excluded + "/") || relPathStr.startsWith(excluded + "\\")) {
                                    return;
                                }
                            }

                            String fileName = file.getFileName().toString();
                            String ext = "";
                            int dotIdx = fileName.lastIndexOf('.');
                            if (dotIdx > 0) ext = fileName.substring(dotIdx).toLowerCase();

                            if (!patterns.isEmpty()) {
                                boolean matchedExt = false;
                                for (String pat : patterns) {
                                    if (pat.startsWith(".") && ext.equals(pat)) { matchedExt = true; break; }
                                    else if (ext.equals("." + pat)) { matchedExt = true; break; }
                                    else if (fileName.endsWith(pat)) { matchedExt = true; break; }
                                }
                                if (!matchedExt) return;
                            } else {
                                if (!textExtensions.contains(ext)) return;
                            }

                            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                            int lineNum = 0;
                            for (String line : lines) {
                                lineNum++;
                                if (resultCount.get() >= MAX_RESULTS) break;
                                if (pattern.matcher(line).find()) {
                                    String preview = line.trim();
                                    if (preview.length() > 80) preview = preview.substring(0, 80) + "...";
                                    results.add(relPathStr + ":" + lineNum + ":" + preview);
                                    matchCount.incrementAndGet();
                                    resultCount.incrementAndGet();
                                }
                            }
                        } catch (IOException ignored) {}
                    });

            if (results.isEmpty()) {
                return "🔍 未找到匹配 \"" + keyword + "\" 的内容。";
            }

            StringBuilder sb = new StringBuilder();
            int total = matchCount.get();
            int shown = results.size();
            sb.append("🔍 找到 ").append(total).append(" 条匹配结果");
            if (total > shown) sb.append("（仅显示前 ").append(MAX_RESULTS).append(" 条）");
            sb.append("：\n");
            for (String r : results) sb.append(r).append("\n");
            return sb.toString();

        } catch (IOException e) {
            logger.error("搜索失败", e);
            return "搜索失败: " + e.getMessage();
        }
    }

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

            // 排除目录
            List<String> excludedDirs = Arrays.asList(
                    "target", "build", ".git", ".idea", "node_modules",
                    "dist", "out", "bin", "logs"
            );

            // 代码文件扩展名
            List<String> codeExtensions = Arrays.asList(
                    ".java", ".js", ".jsx", ".ts", ".tsx", ".css", ".html", ".htm",
                    ".py", ".go", ".rs", ".c", ".cpp", ".h", ".hpp", ".php", ".rb",
                    ".swift", ".kt", ".scala", ".groovy", ".vue", ".svelte"
            );

            // 文件模式过滤
            List<String> patterns = new ArrayList<>();
            if (filePattern != null && !filePattern.trim().isEmpty()) {
                String[] parts = filePattern.split(",");
                for (String p : parts) {
                    p = p.trim();
                    if (p.startsWith("*.")) {
                        patterns.add(p.substring(1));
                    } else {
                        patterns.add(p);
                    }
                }
            }

            // 存储结果
            Map<String, List<Map<String, Object>>> results = new LinkedHashMap<>();
            AtomicInteger totalMatches = new AtomicInteger(0);

            Files.walk(startPath)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        try {
                            Path relativePath = startPath.relativize(file);
                            String relPathStr = relativePath.toString().replace('\\', '/');

                            // 排除目录
                            for (String excluded : excludedDirs) {
                                if (relPathStr.startsWith(excluded + "/") || relPathStr.startsWith(excluded + "\\")) {
                                    return;
                                }
                            }

                            // 扩展名过滤
                            String fileName = file.getFileName().toString();
                            String ext = "";
                            int dotIdx = fileName.lastIndexOf('.');
                            if (dotIdx > 0) ext = fileName.substring(dotIdx).toLowerCase();

                            if (!patterns.isEmpty()) {
                                boolean matchedExt = false;
                                for (String pat : patterns) {
                                    if (pat.startsWith(".") && ext.equals(pat)) { matchedExt = true; break; }
                                    else if (ext.equals("." + pat)) { matchedExt = true; break; }
                                    else if (fileName.endsWith(pat)) { matchedExt = true; break; }
                                }
                                if (!matchedExt) return;
                            } else {
                                if (!codeExtensions.contains(ext)) return;
                            }

                            // 读取文件内容
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
                    });

            if (results.isEmpty()) {
                return "🔍 未找到符号 \"" + symbol + "\" 的引用。";
            }

            // 构建输出
            StringBuilder sb = new StringBuilder();
            int total = totalMatches.get();
            int files = results.size();
            sb.append("🔍 找到 ").append(total).append(" 条引用，分布在 ").append(files).append(" 个文件中：\n\n");

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

            // 排除目录 + 扩展名
            List<String> excludedDirs = Arrays.asList("target", "build", ".git", ".idea", "node_modules", "dist", "out", "bin");
            List<String> codeExtensions = Arrays.asList(".java", ".js", ".jsx", ".ts", ".tsx", ".py", ".go", ".rs", ".c", ".cpp", ".h", ".php", ".rb", ".kt", ".vue");

            List<String> patterns = new ArrayList<>();
            if (filePattern != null && !filePattern.trim().isEmpty()) {
                for (String p : filePattern.split(",")) {
                    patterns.add(p.trim());
                }
            }

            // 结果存储：每个文件一个列表
            Map<String, List<Map<String, Object>>> results = new LinkedHashMap<>();
            AtomicInteger totalCallers = new AtomicInteger(0);

            Files.walk(startPath)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        try {
                            Path relativePath = startPath.relativize(file);
                            String relPathStr = relativePath.toString().replace('\\', '/');

                            // 排除目录
                            for (String excluded : excludedDirs) {
                                if (relPathStr.startsWith(excluded + "/") || relPathStr.startsWith(excluded + "\\")) {
                                    return;
                                }
                            }

                            // 扩展名过滤
                            String fileName = file.getFileName().toString();
                            String ext = "";
                            int dotIdx = fileName.lastIndexOf('.');
                            if (dotIdx > 0) ext = fileName.substring(dotIdx).toLowerCase();

                            if (!patterns.isEmpty()) {
                                boolean matchedExt = false;
                                for (String pat : patterns) {
                                    if (ext.equals(pat.startsWith(".") ? pat : "." + pat)) {
                                        matchedExt = true;
                                        break;
                                    }
                                }
                                if (!matchedExt) return;
                            } else if (!codeExtensions.contains(ext)) {
                                return;
                            }

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
                    });

            if (results.isEmpty()) {
                return "🔍 未找到函数 \"" + functionName + "\" 的调用点。";
            }

            // 构建输出
            StringBuilder sb = new StringBuilder();
            int total = totalCallers.get();
            int files = results.size();
            sb.append("🔍 找到 ").append(total).append(" 个调用点，分布在 ").append(files).append(" 个文件中：\n\n");

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
    String findCallees(String functionName, String path, boolean recursive, Integer depth) {
        try {
            Path startPath = PathUtils.safeResolve(path != null ? path : ".");
            if (!Files.exists(startPath) || !Files.isDirectory(startPath)) {
                return "路径不存在或不是目录: " + path;
            }

            // 默认值
            if (depth == null || depth < 1) depth = 1;

            // 存储结果：函数名 → 调用点列表
            Map<String, Set<String>> calleesMap = new LinkedHashMap<>();
            Set<String> processedFunctions = new HashSet<>();
            analyzeFunctionCalls(functionName, startPath, calleesMap, processedFunctions, depth, 0);

            if (calleesMap.isEmpty() || calleesMap.get(functionName) == null || calleesMap.get(functionName).isEmpty()) {
                return "🔍 函数 \"" + functionName + "\" 没有调用其他函数。";
            }

            // 构建输出
            StringBuilder sb = new StringBuilder();
            sb.append("🔍 函数 \"").append(functionName).append("\" 的调用依赖分析：\n\n");

            for (Map.Entry<String, Set<String>> entry : calleesMap.entrySet()) {
                String func = entry.getKey();
                Set<String> callees = entry.getValue();
                if (callees.isEmpty()) continue;

                sb.append("📦 ").append(func).append(" → 调用了 ").append(callees.size()).append(" 个函数：\n");
                for (String callee : callees) {
                    sb.append("    ├── ").append(callee).append("\n");
                }
                sb.append("\n");
            }

            return sb.toString();

        } catch (IOException e) {
            logger.error("分析函数调用失败", e);
            return "❌ 分析函数调用失败: " + e.getMessage();
        }
    }


    // ===== 内部辅助 =====
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
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(\\w+)\\s*[=:(\\.]").matcher(line);
                if (m.find()) {
                    return m.group(1);
                }
                return line.trim();
            }
        }
        return "全局";
    }

    private void analyzeFunctionCalls(String functionName, Path startPath,
                                      Map<String, Set<String>> calleesMap,
                                      Set<String> processedFunctions,
                                      int maxDepth, int currentDepth) {

        if (processedFunctions.contains(functionName) || currentDepth >= maxDepth) {
            return;
        }
        processedFunctions.add(functionName);

        AnchorLocation loc = findFunctionDefinition(functionName, startPath);
        if (loc == null) {
            return;
        }

        try {
            // 修正：直接使用 loc.filePath（绝对路径）
            Path filePath = Paths.get(loc.filePath);
            if (!filePath.isAbsolute()) {
                filePath = startPath.resolve(loc.filePath).normalize();
            }
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);

            int startLine = loc.line - 1;
            int endLine = findFunctionEnd(lines, startLine);
            if (endLine <= startLine) {
                return;
            }

            Set<String> callees = new HashSet<>();
            Pattern callPattern = Pattern.compile("\\b(\\w+)\\s*\\(");

            for (int i = startLine; i < endLine; i++) {
                String line = lines.get(i);
                if (line.trim().startsWith("//") || line.trim().startsWith("/*")) {
                    continue;
                }
                Matcher m = callPattern.matcher(line);
                while (m.find()) {
                    String calledFunction = m.group(1);
                    if (!isKeyword(calledFunction) && !calledFunction.equals(functionName) && !isPrimitive(calledFunction)) {
                        callees.add(calledFunction);
                    }
                }
            }

            calleesMap.put(functionName, callees);

            if (currentDepth < maxDepth - 1) {
                for (String callee : callees) {
                    analyzeFunctionCalls(callee, startPath, calleesMap, processedFunctions, maxDepth, currentDepth + 1);
                }
            }

        } catch (IOException e) {
            logger.error("分析函数调用失败: " + functionName, e);
        }
    }

    private AnchorLocation findFunctionDefinition(String functionName, Path startPath) {
        // 尝试在锚点中查找
        AtomicReference<AnchorLocation> loc = new AtomicReference<>(anchorMgr.findAnchor(functionName));
        if (loc.get() != null) {
            return loc.get();
        }

        // 如果锚点中没有，搜索定义模式
        try {
            Pattern defPattern = Pattern.compile(
                    "(function\\s+" + Pattern.quote(functionName) + "\\s*\\()|" +
                            "(" + Pattern.quote(functionName) + "\\s*[=:]\\s*function\\s*\\()|" +
                            "(" + Pattern.quote(functionName) + "\\s*=\\s*\\()"
            );

            // 搜索定义
            Files.walk(startPath)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        try {
                            List<String> lines = Files.readAllLines(file);
                            for (int i = 0; i < lines.size(); i++) {
                                if (defPattern.matcher(lines.get(i)).find()) {
                                    // 找到定义，构造 AnchorLocation
                                    AnchorLocation found = new AnchorLocation();
                                    found.projectPath = startPath.relativize(file).toString().replace('\\', '/');
                                    found.filePath = file.toString();
                                    found.line = i + 1;
                                    found.id = functionName;
                                    found.preview = lines.get(i).trim();
                                    loc.set(found);
                                    return;
                                }
                            }
                        } catch (IOException ignored) {}
                    });
        } catch (IOException e) {
            logger.error("查找函数定义失败: " + functionName, e);
        }

        return loc.get();
    }

    private int findFunctionEnd(List<String> lines, int startLine) {
        int braceCount = 0;
        boolean started = false;

        for (int i = startLine; i < lines.size(); i++) {
            String line = lines.get(i);

            // 计算大括号
            for (char c : line.toCharArray()) {
                if (c == '{') {
                    braceCount++;
                    started = true;
                } else if (c == '}') {
                    braceCount--;
                    if (started && braceCount == 0) {
                        return i + 1;
                    }
                }
            }

            // 如果在一行中检测到函数定义的开始（单行函数）
            if (!started && line.contains("{") && line.contains("}")) {
                return i + 1;
            }
        }
        return lines.size();
    }

    private boolean isKeyword(String word) {
        Set<String> keywords = new HashSet<>(Arrays.asList(
                "if", "else", "for", "while", "do", "switch", "case", "return", "break", "continue",
                "try", "catch", "finally", "throw", "new", "delete", "typeof", "instanceof",
                "void", "this", "super", "class", "extends", "implements", "interface", "enum",
                "const", "let", "var", "function", "async", "await", "yield", "import", "export",
                "require", "module", "exports", "console", "window", "document", "localStorage",
                "JSON", "setTimeout", "setInterval", "clearTimeout", "clearInterval"
        ));
        return keywords.contains(word);
    }

    private boolean isPrimitive(String word) {
        Set<String> primitives = new HashSet<>(Arrays.asList(
                "String", "Number", "Boolean", "Array", "Object", "Function", "Date", "RegExp",
                "Error", "Promise", "Map", "Set", "WeakMap", "WeakSet", "Symbol", "BigInt"
        ));
        return primitives.contains(word);
    }
}