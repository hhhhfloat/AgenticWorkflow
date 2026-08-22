package com.myagent.workflow.tools;

import com.myagent.workflow.core.AgentConfig;
import com.myagent.workflow.model.AnchorLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// @anchor: callGraphAnalyzer_class
/**
 * 调用图分析模块：负责 findCallees / analyzeFunctionCalls / findFunctionDefinition /
 * findAnchorInScope / findFunctionEnd / isKeyword / isPrimitive。
 * 函数定义定位通过 AnchorManager 锚点索引（新 API findAnchor(projectPath, anchorId)）完成。
 */
public class CallGraphAnalyzer {
    private final AnchorManager anchorMgr;
    private static final Logger logger = LoggerFactory.getLogger(CallGraphAnalyzer.class);

    public CallGraphAnalyzer(AnchorManager anchorMgr) {
        this.anchorMgr = anchorMgr;
    }

    // @anchor: callGraphAnalyzer_findCallees
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

    // @anchor: callGraphAnalyzer_analyzeCalls
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

    // @anchor: callGraphAnalyzer_findFunctionDef
    private AnchorLocation findFunctionDefinition(String functionName, Path startPath) {
        // 尝试在锚点索引中查找（使用新 API：findAnchor(projectPath, anchorId)）
        AtomicReference<AnchorLocation> loc = new AtomicReference<>(findAnchorInScope(startPath, functionName));
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

    // @anchor: callGraphAnalyzer_findAnchorInScope
    /**
     * 在搜索起始路径范围内查找锚点（替代已废弃的 findAnchor(String) 全局查找）
     * - 若 startPath 为沙箱根目录，则遍历所有项目目录查找
     * - 否则按 startPath 所在项目查找
     */
    private AnchorLocation findAnchorInScope(Path startPath, String anchorId) {
        Path sandboxRoot = Paths.get(AgentConfig.getSandboxDir()).toAbsolutePath().normalize();
        Path normalizedStart = startPath.toAbsolutePath().normalize();

        // 搜索范围是沙箱根目录：遍历所有项目
        if (normalizedStart.equals(sandboxRoot)) {
            try (var stream = Files.list(sandboxRoot)) {
                for (Path projectDir : (Iterable<Path>) stream::iterator) {
                    if (!Files.isDirectory(projectDir)) continue;
                    String projectName = projectDir.getFileName().toString();
                    if (projectName.startsWith(".")) continue;

                    AnchorLocation loc = anchorMgr.findAnchor(projectName, anchorId);
                    if (loc != null) return loc;
                }
            } catch (IOException e) {
                logger.error("遍历沙箱项目失败", e);
            }
            return null;
        }

        // 搜索范围是某个项目内：推导项目名
        if (normalizedStart.startsWith(sandboxRoot)) {
            Path rel = sandboxRoot.relativize(normalizedStart);
            if (rel.getNameCount() > 0) {
                String projectName = rel.getName(0).toString();
                return anchorMgr.findAnchor(projectName, anchorId);
            }
        }
        return null;
    }

    // @anchor: callGraphAnalyzer_findFunctionEnd
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

    // @anchor: callGraphAnalyzer_isKeyword
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

    // @anchor: callGraphAnalyzer_isPrimitive
    private boolean isPrimitive(String word) {
        Set<String> primitives = new HashSet<>(Arrays.asList(
                "String", "Number", "Boolean", "Array", "Object", "Function", "Date", "RegExp",
                "Error", "Promise", "Map", "Set", "WeakMap", "WeakSet", "Symbol", "BigInt"
        ));
        return primitives.contains(word);
    }
}
