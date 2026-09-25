package com.myagent.workflow.tools;

import com.myagent.workflow.core.AgentConfig;
import com.myagent.workflow.model.AnchorLocation;
import com.myagent.workflow.model.ClassDefinition;
import com.myagent.workflow.model.FileStructure;
import com.myagent.workflow.model.MethodDefinition;
import com.myagent.workflow.parser.StructureParser;
import com.myagent.workflow.parser.StructureParserRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// @anchor: callGraphAnalyzer_class
// 调用链分析器：解析函数的调用者/被调用者
/**
 * 调用图分析模块：负责 findCallees 工具的逻辑。
 * 函数定位走 StructureParser 索引（语言无关），锚点 ID 作为兜底入口。
 */
public class CallGraphAnalyzer {
    private final AnchorManager anchorMgr;
    private static final Logger logger = LoggerFactory.getLogger(CallGraphAnalyzer.class);

    /** 使用 StructureParser 解析的扩展名 */
    private static final List<String> CODE_EXTENSIONS = List.of(
            ".java", ".py", ".js", ".jsx", ".ts", ".tsx",
            ".cpp", ".cc", ".cxx", ".c", ".h", ".hpp");

    // @anchor: callGraphAnalyzer_keywords
// 语言关键字集合：用于过滤调用图噪声
    /** 各语言通用关键字，不计入调用图 */
    private static final Set<String> KEYWORDS = Set.of(
            // 控制流
            "if", "else", "for", "while", "do", "switch", "case", "return",
            "break", "continue", "try", "catch", "finally", "throw", "throws",
            // 声明与修饰符
            "new", "delete", "typeof", "instanceof", "void", "this", "super",
            "class", "extends", "implements", "interface", "enum", "record",
            "const", "let", "var", "function", "async", "await", "yield",
            "public", "private", "protected", "static", "final", "abstract",
            "synchronized", "volatile", "transient", "native", "strictfp",
            "package", "import", "export", "def", "lambda",
            // 常用内建
            "require", "module", "exports", "console", "window", "document",
            "localStorage", "JSON", "setTimeout", "setInterval",
            "clearTimeout", "clearInterval", "print", "len", "range"
    );

    /** 原生类型与常用内建类，不计入调用图 */
    private static final Set<String> PRIMITIVES = Set.of(
            "String", "Number", "Boolean", "Array", "Object", "Function",
            "Date", "RegExp", "Error", "Promise", "Map", "Set", "WeakMap",
            "WeakSet", "Symbol", "BigInt", "Integer", "Long", "Double",
            "Float", "Byte", "Short", "Character", "List", "ArrayList",
            "HashMap", "HashSet", "Optional", "Stream"
    );

    public CallGraphAnalyzer(AnchorManager anchorMgr) {
        this.anchorMgr = anchorMgr;
    }

    // @anchor: callGraphAnalyzer_findCallees
// 查找函数内部直接调用的其它函数
    String findCallees(String nameOrAnchor, String path, boolean recursive, Integer depth) {
        try {
            Path startPath = PathUtils.safeResolve(path != null ? path : ".");
            if (!Files.exists(startPath) || !Files.isDirectory(startPath)) {
                return "路径不存在或不是目录: " + path;
            }
            if (depth == null || depth < 1) depth = 1;

            // 1. 建函数索引（一次全项目扫描）
            Map<String, FunctionRef> functionIndex = buildFunctionIndex(startPath);

            // 2. 入口解析：函数名优先，锚点 ID 兜底
            FunctionRef entry = resolveEntry(nameOrAnchor, startPath, functionIndex);
            if (entry == null) {
                return "🔍 未找到函数或锚点 \"" + nameOrAnchor + "\" 的定义。\n" +
                        "提示：请传函数名（如 'placeStone'）或锚点 ID（如 'controller_placeStone'）。";
            }

            // 3. 递归分析
            Map<String, Set<String>> calleesMap = new LinkedHashMap<>();
            Set<String> processedFunctions = new HashSet<>();
            analyzeFunctionCalls(entry.name(), functionIndex, calleesMap, processedFunctions, depth, 0);

            if (calleesMap.isEmpty() || calleesMap.get(entry.name()) == null
                    || calleesMap.get(entry.name()).isEmpty()) {
                return "🔍 函数 \"" + entry.name() + "\" 没有调用其他函数。";
            }

            // 4. 输出
            StringBuilder sb = new StringBuilder();
            sb.append("🔍 函数 \"").append(entry.name()).append("\" 的调用依赖分析：\n\n");
            for (Map.Entry<String, Set<String>> e : calleesMap.entrySet()) {
                Set<String> callees = e.getValue();
                if (callees.isEmpty()) continue;
                sb.append("📦 ").append(e.getKey())
                        .append(" → 调用了 ").append(callees.size()).append(" 个函数：\n");
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

    // ===== 索引构建 =====

    private record FunctionRef(String name, Path file, int startLine, int endLine) {}

    private Map<String, FunctionRef> buildFunctionIndex(Path startPath) throws IOException {
        Map<String, FunctionRef> index = new HashMap<>();
        List<Path> files = SearchFileFilter.collectFiles(
                startPath,
                SearchFileFilter.DEFAULT_EXCLUDED_DIRS,
                SearchFileFilter.DEFAULT_EXCLUDED_FILES);

        StructureParserRegistry registry = StructureParserRegistry.getInstance();
        for (Path file : files) {
            String lower = file.getFileName().toString().toLowerCase();
            boolean supported = CODE_EXTENSIONS.stream().anyMatch(lower::endsWith);
            if (!supported) continue;

            StructureParser parser = registry.getParser(file);
            if (parser == null) continue;

            try {
                FileStructure structure = parser.parse(file);
                for (MethodDefinition m : structure.functions()) {
                    index.putIfAbsent(m.name(), new FunctionRef(m.name(), file, m.startLine(), m.endLine()));
                }
                for (ClassDefinition c : structure.classes()) {
                    for (MethodDefinition m : c.methods()) {
                        index.putIfAbsent(m.name(), new FunctionRef(m.name(), file, m.startLine(), m.endLine()));
                    }
                }
            } catch (Exception e) {
                // 单文件解析失败跳过
                logger.debug("解析 {} 失败: {}", file, e.getMessage());
            }
        }
        return index;
    }

    // ===== 入口解析 =====

    private FunctionRef resolveEntry(String nameOrAnchor, Path startPath, Map<String, FunctionRef> functionIndex) {
        // 1. 函数名优先
        FunctionRef ref = functionIndex.get(nameOrAnchor);
        if (ref != null) return ref;

        // 2. 锚点 ID 兜底
        AnchorLocation anchor = findAnchorInScope(startPath, nameOrAnchor);
        if (anchor == null) return null;

        Path anchorFile = Paths.get(anchor.filePath);
        if (!anchorFile.isAbsolute()) {
            anchorFile = startPath.resolve(anchor.filePath).normalize();
        }

        // 同文件中，函数定义起始行 >= 锚点行，距离最近的那个
        FunctionRef best = null;
        int bestDist = Integer.MAX_VALUE;
        for (FunctionRef r : functionIndex.values()) {
            if (!r.file().equals(anchorFile)) continue;
            if (r.startLine() < anchor.line) continue;
            int dist = r.startLine() - anchor.line;
            if (dist < bestDist) {
                bestDist = dist;
                best = r;
            }
        }
        return best;
    }

    // @anchor: callGraphAnalyzer_findAnchorInScope
// 在函数作用域内定位锚点位置
    private AnchorLocation findAnchorInScope(Path startPath, String anchorId) {
        Path sandboxRoot = Paths.get(AgentConfig.getSandboxDir()).toAbsolutePath().normalize();
        Path normalizedStart = startPath.toAbsolutePath().normalize();

        if (normalizedStart.equals(sandboxRoot)) {
            try (var stream = Files.list(sandboxRoot)) {
                for (Path projectDir : (Iterable<Path>) stream::iterator) {
                    if (!Files.isDirectory(projectDir)) continue;
                    String name = projectDir.getFileName().toString();
                    if (name.startsWith(".")) continue;
                    AnchorLocation loc = anchorMgr.findAnchor(name, anchorId);
                    if (loc != null) return loc;
                }
            } catch (IOException e) {
                logger.error("遍历沙箱项目失败", e);
            }
            return null;
        }

        if (normalizedStart.startsWith(sandboxRoot)) {
            Path rel = sandboxRoot.relativize(normalizedStart);
            if (rel.getNameCount() > 0) {
                return anchorMgr.findAnchor(rel.getName(0).toString(), anchorId);
            }
        }
        return null;
    }

    // ===== 递归分析 =====

    private void analyzeFunctionCalls(String functionName,
                                      Map<String, FunctionRef> functionIndex,
                                      Map<String, Set<String>> calleesMap,
                                      Set<String> processedFunctions,
                                      int maxDepth, int currentDepth) {
        if (processedFunctions.contains(functionName) || currentDepth >= maxDepth) {
            return;
        }
        processedFunctions.add(functionName);

        FunctionRef ref = functionIndex.get(functionName);
        if (ref == null) return;

        try {
            List<String> lines = Files.readAllLines(ref.file(), StandardCharsets.UTF_8);
            int startLine = Math.max(0, ref.startLine() - 1);
            int endLine = ref.endLine() > 0 ? ref.endLine() : findFunctionEnd(lines, startLine);

            Set<String> callees = new HashSet<>();
            Pattern callPattern = Pattern.compile("\\b(\\w+)\\s*\\(");

            for (int i = startLine; i < endLine && i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.trim().startsWith("//") || line.trim().startsWith("/*")) continue;
                Matcher m = callPattern.matcher(line);
                while (m.find()) {
                    String called = m.group(1);
                    if (!isKeyword(called) && !called.equals(functionName) && !isPrimitive(called)) {
                        callees.add(called);
                    }
                }
            }

            calleesMap.put(functionName, callees);

            if (currentDepth < maxDepth - 1) {
                for (String callee : callees) {
                    analyzeFunctionCalls(callee, functionIndex, calleesMap, processedFunctions,
                            maxDepth, currentDepth + 1);
                }
            }
        } catch (IOException e) {
            logger.error("分析函数调用失败: " + functionName, e);
        }
    }

    // @anchor: callGraphAnalyzer_findFunctionEnd
// 定位函数的结束位置
    private int findFunctionEnd(List<String> lines, int startLine) {
        int braceCount = 0;
        boolean started = false;
        for (int i = startLine; i < lines.size(); i++) {
            String line = lines.get(i);
            for (char c : line.toCharArray()) {
                if (c == '{') { braceCount++; started = true; }
                else if (c == '}') {
                    braceCount--;
                    if (started && braceCount == 0) return i + 1;
                }
            }
            if (!started && line.contains("{") && line.contains("}")) return i + 1;
        }
        return lines.size();
    }

    // ===== 关键字/原语过滤 =====

    // @anchor: callGraphAnalyzer_isKeyword
// 判断标识符是否为语言关键字
    private boolean isKeyword(String word) {
        return KEYWORDS.contains(word);
    }

    // @anchor: callGraphAnalyzer_isPrimitive
// 判断标识符是否为基本类型
    private boolean isPrimitive(String word) {
        return PRIMITIVES.contains(word);
    }
}
