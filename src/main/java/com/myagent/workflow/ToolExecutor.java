package com.myagent.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * @anchor: toolExecutor_class
 * 工具执行器 —— 实现所有 Agent 可调用工具的具体逻辑。
 * 从 Main.java 中独立出来，Main 仅保留工作流编排。
 */
public class ToolExecutor {
    private static final Logger logger = LoggerFactory.getLogger(ToolExecutor.class);

    private final String sandboxDir;
    private final String anchorIndexFile;
    private final ObjectMapper objectMapper;
    private Consumer<String> logConsumer;

    // @anchor: toolExecutor_constructor
    public ToolExecutor(String sandboxDir, String anchorIndexFile, ObjectMapper objectMapper) {
        this.sandboxDir = sandboxDir;
        this.anchorIndexFile = anchorIndexFile;
        this.objectMapper = objectMapper;
    }

    public void setLogConsumer(Consumer<String> consumer) {
        this.logConsumer = consumer;
    }

    // ==================== 工具调度入口 ====================

    /**
     * @anchor: toolExecutor_dispatch
     * 根据工具名和参数分发执行，返回结果字符串。
     */

    private Path safeResolve(String... parts) throws IOException {
        // 1. 获取沙箱根目录的绝对规范化路径
        Path root = Paths.get(this.sandboxDir).toAbsolutePath().normalize();

        // 2. 从根目录开始拼接用户传入的片段
        Path resolved = root;
        for (String part : parts) {
            if (part != null && !part.isEmpty()) {
                resolved = resolved.resolve(part);
            }
        }

        // 3. 规范化（消除 .. 和 .）
        resolved = resolved.normalize();

        // 4. 核心校验：确保最终路径仍然以沙箱根目录开头
        if (!resolved.startsWith(root)) {
            throw new IOException("安全拒绝：路径穿越检测 -> " + String.join("/", parts));
        }

        return resolved;
    }

    public String dispatch(String functionName, Map<String, Object> args) throws IOException {
        switch (functionName) {
            case "write_java_file":
                return writeJavaFile((String) args.get("filename"), (String) args.get("code"));
            case "compile_and_run":
                return compileAndRun((String) args.get("filename"));
            case "list_directory":
                return listDirectory(
                        (String) args.getOrDefault("path", "."),
                        args.containsKey("recursive") && (boolean) args.get("recursive"));
            case "read_file":
                return readFile((String) args.get("filename"));
            case "delete_file":
                return deleteFile((String) args.get("filename"));
            case "search_text":
                return searchText(
                        (String) args.get("keyword"),
                        (String) args.getOrDefault("file_pattern", ".*"),
                        (String) args.getOrDefault("path", "."));
            case "build_anchor_index":
                return buildAnchorIndex((String) args.get("project_path"));
            case "list_anchors":
                return listAnchors((String) args.get("project_path"));
            case "insert_at_anchor":
                return insertAtAnchor(
                        (String) args.get("anchor_id"),
                        (String) args.get("content"),
                        (String) args.getOrDefault("position", "after"));
            case "delete_between_anchors":
                return deleteBetweenAnchors(
                        (String) args.get("startAnchor"),
                        (String) args.get("endAnchor")
                );
            case "find_references":
                return findReferences(
                        (String) args.get("symbol"),
                        (String) args.getOrDefault("path", "."),
                        (String) args.getOrDefault("file_pattern", null)
                );
            case "find_callers":
                return findCallers(
                        (String) args.get("functionName"),
                        (String) args.getOrDefault("path", "."),
                        (String) args.getOrDefault("file_pattern", null)
                );
            case "find_callees":
                return findCallees(
                        (String) args.get("functionName"),
                        (String) args.getOrDefault("path", "."),
                        args.containsKey("recursive") && (boolean) args.get("recursive"),
                        args.containsKey("depth") ? (Integer) args.get("depth") : 1
                );
            default:
                return "未知工具: " + functionName;
        }
    }

    // ==================== 工具 1: write_java_file ====================

    private String writeJavaFile(String filename, String code) {
        try {
            Path filePath = safeResolve(filename);
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

    // ==================== 工具 2: compile_and_run ====================

    private String compileAndRun(String filename) {
        try {
            // 【唯一改动点】获取安全解析后的路径
            Path filePath = safeResolve(filename);

            if (filename.endsWith(".html") || filename.endsWith(".htm")) {
                File htmlFile = filePath.toFile();
                if (!htmlFile.exists()) {
                    return "HTML 文件不存在: " + filename;
                }

                // 构造访问 URL：统一加上 /sandbox/ 前缀
                String relativePath = filename.replace("\\", "/");
                // 如果已经以 sandbox/ 开头，去掉，避免重复
                if (relativePath.startsWith("sandbox/")) {
                    relativePath = relativePath.substring("sandbox/".length());
                }
                if (relativePath.startsWith("/sandbox/")) {
                    relativePath = relativePath.substring("/sandbox/".length());
                }
                String url = "http://localhost:8080/sandbox/" + relativePath;

                if (AgentConfig.AUTO_OPEN_BROWSER && Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(htmlFile.toURI());
                    return "✅ 已在浏览器中打开 " + filename + "\n🔗 访问地址: " + url;
                } else {
                    return "✅ 预览就绪，请手动访问: " + url;
                }
            }

            // 编译 Java 文件
            ProcessBuilder compilePb = new ProcessBuilder(
                    "javac", "-d", sandboxDir,
                    filePath.toString()); // 替换 Paths.get(sandboxDir, filename).toString()
            compilePb.redirectErrorStream(true);
            Process compileProc = compilePb.start();
            int compileExit = compileProc.waitFor();
            String compileOutput = new String(compileProc.getInputStream().readAllBytes());

            if (compileExit != 0) {
                return "编译失败 (退出码 " + compileExit + "):\n" + compileOutput;
            }

            String className = filename.replace(".java", "");
            ProcessBuilder runPb = new ProcessBuilder("java", "-cp", sandboxDir, className);
            runPb.directory(new File(sandboxDir));
            runPb.redirectErrorStream(true);
            Process runProc = runPb.start();
            boolean finished = runProc.waitFor(30, TimeUnit.SECONDS);
            String runOutput = new String(runProc.getInputStream().readAllBytes());

            if (!finished) {
                runProc.destroyForcibly();
                return "运行超时（超过30秒），已强制终止。\n输出:\n" + runOutput;
            }

            int runExit = runProc.exitValue();
            if (runExit != 0) {
                return "运行失败 (退出码 " + runExit + "):\n" + runOutput;
            }
            return "运行成功！\n输出:\n" + runOutput;

        } catch (IOException | InterruptedException e) {
            logger.error("编译/运行过程异常", e);
            return "编译/运行异常: " + e.getMessage();
        }
    }

    // ==================== 工具 3: list_directory ====================

    private String listDirectory(String path, boolean recursive) {
        if (path == null || path.trim().isEmpty()) {
            path = ".";
        }
        try {
            // 【唯一改动】将 Paths.get(sandboxDir, path) 替换为 safeResolve(path)
            Path dirPath = safeResolve(path);
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

    // ==================== 工具 4: read_file ====================

    private String readFile(String filename) {
        try {
            Path filePath = safeResolve(filename);
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

    // ==================== 工具 5: delete_file ====================

    private String deleteFile(String filename) {
        try {
            Path filePath = safeResolve(filename);
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

    // ==================== 工具 6: search_text ====================

    private String searchText(String keyword, String filePattern, String path) {
        try {
            Path startPath = safeResolve(path);
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

    // ==================== 工具 7: build_anchor_index ====================

    private String buildAnchorIndex(String projectPath) {
        try {
            Path projectDir = safeResolve(projectPath);
            if (!Files.exists(projectDir) || !Files.isDirectory(projectDir)) {
                return "项目目录不存在: " + projectPath;
            }

            Map<String, Map<String, List<Map<String, Object>>>> index = new HashMap<>();
            Path indexFile = Paths.get(anchorIndexFile);
            if (Files.exists(indexFile)) {
                String content = new String(Files.readAllBytes(indexFile), StandardCharsets.UTF_8);
                index = objectMapper.readValue(content, new TypeReference<>() {});
            }
            index.remove(projectPath);

            List<String> textExtensions = Arrays.asList(".java", ".html", ".htm", ".css", ".js", ".jsx", ".ts", ".tsx",
                    ".txt", ".xml", ".json", ".md", ".properties", ".yml", ".yaml", ".sh", ".bat", ".gradle", ".sql");
            Map<String, List<Map<String, Object>>> projectAnchors = new HashMap<>();

            Pattern anchorPattern = Pattern.compile(
                    "//\\s*@anchor:\\s*(\\w+)|/\\*\\s*@anchor:\\s*(\\w+)\\s*\\*/|<!--\\s*@anchor:\\s*(\\w+)\\s*-->");

            Files.walk(projectDir)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        String fileName = file.getFileName().toString();
                        String ext = "";
                        int dotIdx = fileName.lastIndexOf('.');
                        if (dotIdx > 0) ext = fileName.substring(dotIdx).toLowerCase();
                        if (!textExtensions.contains(ext)) return;

                        try {
                            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                            String relPath = projectDir.relativize(file).toString().replace('\\', '/');
                            List<Map<String, Object>> anchors = new ArrayList<>();

                            for (int i = 0; i < lines.size(); i++) {
                                java.util.regex.Matcher matcher = anchorPattern.matcher(lines.get(i));
                                if (matcher.find()) {
                                    String id = matcher.group(1);
                                    if (id == null) id = matcher.group(2);
                                    if (id == null) id = matcher.group(3);
                                    if (id != null) {
                                        Map<String, Object> anchor = new HashMap<>();
                                        anchor.put("id", id);
                                        anchor.put("line", i + 1);
                                        anchor.put("preview", lines.get(i).trim());
                                        anchors.add(anchor);
                                    }
                                }
                            }
                            if (!anchors.isEmpty()) {
                                projectAnchors.put(relPath, anchors);
                            }
                        } catch (IOException ignored) {}
                    });

            if (projectAnchors.isEmpty()) {
                index.remove(projectPath);
            } else {
                index.put(projectPath, projectAnchors);
            }

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(index);
            Files.write(indexFile, json.getBytes(StandardCharsets.UTF_8));

            int totalFiles = projectAnchors.size();
            int totalAnchors = projectAnchors.values().stream().mapToInt(List::size).sum();
            return "✅ 锚点索引已重建：" + projectPath + " 中找到 " + totalAnchors + " 个锚点，分布在 " + totalFiles + " 个文件中。";

        } catch (IOException e) {
            logger.error("重建锚点索引失败", e);
            return "重建锚点索引失败: " + e.getMessage();
        }
    }

    // ==================== 工具 8: list_anchors ====================

    private String listAnchors(String projectPath) {
        try {
            Path indexFile = Paths.get(anchorIndexFile);
            if (!Files.exists(indexFile)) {
                return "锚点索引文件不存在，请先运行 build_anchor_index。";
            }

            String content = new String(Files.readAllBytes(indexFile), StandardCharsets.UTF_8);
            Map<String, Map<String, List<Map<String, Object>>>> index =
                    objectMapper.readValue(content, new TypeReference<>() {});

            if (!index.containsKey(projectPath)) {
                return "项目 " + projectPath + " 没有锚点记录。";
            }

            Map<String, List<Map<String, Object>>> projectAnchors = index.get(projectPath);
            StringBuilder sb = new StringBuilder();
            sb.append("📌 ").append(projectPath).append(" 的锚点列表：\n");
            for (Map.Entry<String, List<Map<String, Object>>> entry : projectAnchors.entrySet()) {
                String file = entry.getKey();
                for (Map<String, Object> anchor : entry.getValue()) {
                    String id = (String) anchor.get("id");
                    int line = (int) anchor.get("line");
                    String preview = (String) anchor.get("preview");
                    sb.append("  - ").append(file).append(":").append(line)
                            .append(" [").append(id).append("] ").append(preview).append("\n");
                }
            }
            return sb.toString();
        } catch (IOException e) {
            logger.error("列出锚点失败", e);
            return "列出锚点失败: " + e.getMessage();
        }
    }

    // ==================== 锚点查找（内部使用） ====================

    private AnchorLocation findAnchor(String anchorId) {
        try {
            Path indexFile = Paths.get(anchorIndexFile);
            if (!Files.exists(indexFile)) return null;

            String content = new String(Files.readAllBytes(indexFile), StandardCharsets.UTF_8);
            Map<String, Map<String, List<Map<String, Object>>>> index =
                    objectMapper.readValue(content, new TypeReference<>() {});

            for (Map.Entry<String, Map<String, List<Map<String, Object>>>> project : index.entrySet()) {
                String projectPath = project.getKey();
                for (Map.Entry<String, List<Map<String, Object>>> file : project.getValue().entrySet()) {
                    String filePath = file.getKey();
                    for (Map<String, Object> anchor : file.getValue()) {
                        String id = (String) anchor.get("id");
                        if (anchorId.equals(id)) {
                            AnchorLocation loc = new AnchorLocation();
                            loc.projectPath = projectPath;
                            loc.filePath = filePath;
                            loc.line = (int) anchor.get("line");
                            loc.id = id;
                            loc.preview = (String) anchor.get("preview");
                            return loc;
                        }
                    }
                }
            }
            return null;
        } catch (IOException e) {
            logger.error("查找锚点失败", e);
            return null;
        }
    }

    // ==================== 工具 9: insert_at_anchor ====================

    private String insertAtAnchor(String anchorId, String content, String position) {
        AnchorLocation loc = findAnchor(anchorId);
        if (loc == null) return "❌ 锚点不存在: " + anchorId;

        try {
            Path filePath = safeResolve(loc.projectPath, loc.filePath);
            if (!Files.exists(filePath)) return "❌ 文件不存在: " + loc.filePath;

            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            int targetLine = loc.line - 1;

            if ("before".equals(position)) {
                lines.add(targetLine, content);
            } else {
                lines.add(targetLine + 1, content);
            }

            Files.write(filePath, lines, StandardCharsets.UTF_8);
            buildAnchorIndex(loc.projectPath);
            return "✅ 已在 " + loc.filePath + " 的锚点 [" + anchorId + "] 之后插入代码";

        } catch (IOException e) {
            logger.error("插入代码失败", e);
            return "❌ 插入失败: " + e.getMessage();
        }
    }

    // ==================== 工具：delete_between_anchors ====================

    private String deleteBetweenAnchors(String startAnchor, String endAnchor) {
        // 1. 查找两个锚点
        AnchorLocation startLoc = findAnchor(startAnchor);
        AnchorLocation endLoc = findAnchor(endAnchor);
        if (startLoc == null) return "❌ 起始锚点不存在: " + startAnchor;
        if (endLoc == null) return "❌ 结束锚点不存在: " + endAnchor;

        // 2. 必须在同一文件中
        if (!startLoc.filePath.equals(endLoc.filePath)) {
            return "❌ 两个锚点不在同一个文件中";
        }

        // 3. 起始行必须在结束行之前
        if (startLoc.line >= endLoc.line) {
            return "❌ 起始锚点必须在结束锚点之前";
        }

        // 4. 读取文件，删除中间内容
        try {
            Path filePath = safeResolve(startLoc.projectPath, startLoc.filePath);
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);

            int startLine = startLoc.line - 1;
            int endLine = endLoc.line - 1;

            // 如果中间没有内容，返回提示
            if (endLine - startLine <= 1) {
                return "⚠️ 两个锚点之间没有内容可删除";
            }

            List<String> newLines = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (i > startLine && i < endLine) {
                    continue; // 跳过中间行
                }
                newLines.add(lines.get(i));
            }

            Files.write(filePath, newLines, StandardCharsets.UTF_8);

            // 5. 更新锚点索引
            buildAnchorIndex(startLoc.projectPath);

            int deletedLines = (endLine - startLine) - 1;
            return "✅ 已删除从 [" + startAnchor + "] 到 [" + endAnchor + "] 之间的 " + deletedLines + " 行代码";

        } catch (IOException e) {
            logger.error("删除代码块失败", e);
            return "❌ 删除失败: " + e.getMessage();
        }
    }

    // ==================== 工具 11 ：find_references ====================

    private String findReferences(String symbol, String path, String filePattern) {
        try {
            Path startPath = safeResolve(path != null ? path : ".");
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

    // ==================== 工具：find_callers ====================

    private String findCallers(String functionName, String path, String filePattern) {
        try {
            Path startPath = safeResolve(path != null ? path : ".");
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

    /**
     * 提取调用所在的函数上下文
     */
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

    // ==================== 工具：find_callees ====================

    private String findCallees(String functionName, String path, boolean recursive, Integer depth) {
        try {
            Path startPath = safeResolve(path != null ? path : ".");
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

    /**
     * 递归分析函数调用关系
     */
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

    /**
     * 查找函数定义的位置（基于符号查找）
     */
    private AnchorLocation findFunctionDefinition(String functionName, Path startPath) {
        // 尝试在锚点中查找
        AtomicReference<AnchorLocation> loc = new AtomicReference<>(findAnchor(functionName));
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

    /**
     * 查找函数结束位置（匹配大括号）
     */
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

    /**
     * 判断是否为关键字（不应该作为函数调用分析）
     */
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
