package com.myagent.workflow.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.AgentConfig;
import com.myagent.workflow.model.FileStructure;
import com.myagent.workflow.parser.FileStructureFormatter;
import com.myagent.workflow.parser.StructureParser;
import com.myagent.workflow.parser.StructureParserRegistry;
import com.myagent.workflow.security.ScanResult;
import com.myagent.workflow.security.SecurityScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.function.Consumer;

/**
 * 工具执行器 —— 实现所有 Agent 可调用工具的具体逻辑。
 * 从 Main.java 中独立出来，Main 仅保留工作流编排。
 */
// @anchor: toolExecutor_class
// 工具执行器：实现全部 Agent 可调用工具的逻辑、路径校验与结果回填
public class ToolExecutor {
    private static final Logger logger = LoggerFactory.getLogger(ToolExecutor.class);

    private final FileOperator fileOp;
    private final CodeSearcher searcher;
    private final Compiler compiler;
    private final AnchorManager anchorMgr;
    private Consumer<String> logConsumer;
    // 字段
    private ObjectMapper objectMapper;

    private final AgentConfig config;

    // 工具名 → 需要做路径检查的参数名
    private static final Map<String, List<String>> PATH_ARG_MAP = Map.ofEntries(
            Map.entry("read_file",          List.of("filename")),
            Map.entry("write_file",         List.of("filename")),
            Map.entry("delete_file",        List.of("filename")),
            Map.entry("get_file_structure", List.of("filename")),
            Map.entry("compile_and_run",    List.of("filename")),
            Map.entry("list_directory",     List.of("path")),
            Map.entry("search_text",        List.of("path")),
            Map.entry("find_references",    List.of("path")),
            Map.entry("find_callers",       List.of("path")),
            Map.entry("find_callees",       List.of("path")),
            Map.entry("list_anchors",       List.of("project_path", "file")),
            Map.entry("describe_anchors",   List.of("project_path", "file"))
            // build_anchor_index 不检查（自动构建项目信息，路径本身需要访问 .anchors.json）
            // read_between_anchors / insert_at_anchor / delete_between_anchors 不检查（无路径参数）
    );

    // @anchor: toolExecutor_constructor
    // 构造：注入配置与 ObjectMapper，并装配各工具组件
    public ToolExecutor(AgentConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.compiler = new Compiler(config);
        this.fileOp = new FileOperator();
        this.objectMapper = objectMapper;
        this.anchorMgr = new AnchorManager(objectMapper);
        this.searcher = new CodeSearcher(anchorMgr);
    }

    // @anchor: toolExecutor_setLogConsumer
    // 设置日志回调，把工具执行输出实时推送给 UI
    public void setLogConsumer(Consumer<String> consumer) {
        this.logConsumer = consumer;
    }

    // ==================== 工具调度入口 ====================
    /**
     * 根据工具名和参数分发执行，返回结果字符串。
     */
    // @anchor: toolExecutor_dispatch
    // 工具调度入口：按工具名分发到具体实现，返回结果字符串
    public String dispatch(String functionName, Map<String, Object> args) throws IOException {

        String violation = checkAccess(functionName, args);
        if(violation!=null)return violation;

        switch (functionName) {
            case "write_file":
                String writeFilename = (String) args.get("filename");
                String writeResult = fileOp.writeFile(writeFilename, (String) args.get("code"));
                if (writeResult != null) {
                    anchorMgr.markDirtyByFilename(writeFilename);
                }
                return writeResult;
            case "compile_and_run":
                String filename = (String) args.get("filename");
                String mode = (String) args.getOrDefault("mode", "auto");
                boolean run = !args.containsKey("run") || (boolean) args.get("run");
                return compileAndRun(filename, mode, run);
            case "list_directory":
                return fileOp.listDirectory(
                        (String) args.getOrDefault("path", "."),
                        args.containsKey("recursive") && (boolean) args.get("recursive"));
            case "read_file":
                return fileOp.readFile((String) args.get("filename"));
            case "delete_file":
                return fileOp.deleteFile((String) args.get("filename"));
            case "search_text":
                return searcher.searchText(
                        (String) args.get("keyword"),
                        (String) args.getOrDefault("file_pattern", ".*"),
                        (String) args.getOrDefault("path", "."));
            case "build_anchor_index":
                return anchorMgr.buildAnchorIndex((String) args.get("project_path"));
            case "list_anchors":
                String projectPath = (String) args.get("project_path");
                String file = (String) args.get("file");
                if (file != null && !file.isBlank()) {
                    return anchorMgr.listAnchors(projectPath, file);
                }
                return anchorMgr.listAnchors(projectPath);
            case "insert_at_anchor":
                return anchorMgr.insertAtAnchor(
                        (String) args.get("anchor_id"),
                        (String) args.get("content"),
                        (String) args.getOrDefault("position", "after"),
                        (String) args.get("file")
                );
            case "delete_between_anchors":
                return anchorMgr.deleteBetweenAnchors(
                        (String) args.get("startAnchor"),
                        (String) args.get("endAnchor"),
                        (String) args.get("file")
                );
            case "describe_anchors":
                return anchorMgr.describeAnchors(
                        (String) args.get("project_path"),
                        (String) args.get("file")
                );
            case "find_references":
                return searcher.findReferences(
                        (String) args.get("symbol"),
                        (String) args.getOrDefault("path", "."),
                        (String) args.getOrDefault("file_pattern", null)
                );
            case "find_callers":
                return searcher.findCallers(
                        (String) args.get("functionName"),
                        (String) args.getOrDefault("path", "."),
                        (String) args.getOrDefault("file_pattern", null)
                );
            case "find_callees":
                return searcher.findCallees(
                        (String) args.get("functionName"),
                        (String) args.getOrDefault("path", "."),
                        args.containsKey("recursive") && (boolean) args.get("recursive"),
                        args.containsKey("depth") ? (Integer) args.get("depth") : 1
                );
            case "read_between_anchors":
                return anchorMgr.readBetweenAnchors(
                        (String) args.get("startAnchor"),
                        (String) args.get("endAnchor"),
                        (String) args.get("file")
                );
            case "get_file_structure":
                return getFileStructure((String) args.get("filename"));
            default:
                return "未知工具: " + functionName;
        }
    }
    // @anchor: toolExecutor_checkAccess
    // 按工具类型校验其路径参数，违规时返回错误消息
    private String checkAccess(String toolName, Map<String, Object> args) {
        List<String> pathArgs = PATH_ARG_MAP.get(toolName);
        if (pathArgs == null) return null;

        for (String argName : pathArgs) {
            Object val = args.get(argName);
            if (!(val instanceof String path) || path.isBlank()) continue;

            String err = checkPath(toolName, path);
            if (err != null) return err;
        }
        return null;
    }

    // @anchor: toolExecutor_checkPath
    // 沙箱路径校验：拒绝 ".." 穿越、点开头路径与 UPDATE.md 危险读取/删除
    private String checkPath(String toolName, String path) {
        String normalized = path.replace('\\', '/');
        String[] segments = normalized.split("/");

        for (String seg : segments) {
            if ("..".equals(seg)) {
                return "❌ 路径中不允许出现 \"..\"：" + path;
            }
            if (seg.startsWith(".") && !seg.equals(".")) {
                return "❌ 不允许访问以 . 开头的文件/目录：" + path
                        + "。如需项目结构信息，请使用 list_anchors / describe_anchors / get_file_structure。";
            }
        }

        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        if ("UPDATE.md".equalsIgnoreCase(fileName)) {
            return switch (toolName) {
                case "read_file" -> "❌ UPDATE.md 不支持完整读取。请使用 read_between_anchors 按锚点读取。";
                case "delete_file" -> "❌ UPDATE.md 不允许删除。";
                default -> null;
            };
        }

        return null;
    }

    // ==================== 工具 : compile_and_run ====================

    // @anchor: toolExecutor_compileAndRun
    // 编译并运行：先安全扫描，再按模式调度编译器，成功后写入口注册表
    private String compileAndRun(String filename, String mode, boolean run) {
        try {
            Path filePath = PathUtils.safeResolve(filename);

            if(config.enableSecurityScan()){
                // ========== 🛡️ 安全检查 ==========
                SecurityScanner scanner = SecurityScanner.getInstance();
                ScanResult scanResult;

                if (Files.isDirectory(filePath)) {
                    // 目录模式：递归扫描所有源文件
                    scanResult = scanner.scanDirectory(filePath);
                } else if (Files.isRegularFile(filePath)) {
                    // 单文件模式
                    scanResult = scanner.scan(filePath);
                } else {
                    return "❌ 路径不存在: " + filename;
                }

                if (!scanResult.passed()) {
                    String report = scanResult.getFormattedReport();
                    logger.warn("安全扫描未通过: {}", filename);
                    return "❌ 安全扫描拦截:\n" + report;
                }
                // ========== 安全检查结束 ==========
            }else{
                logger.info("⚠\uFE0F 安全扫描已禁用，直接编译: {}", filename);
            }

            String result;

            if ("html".equalsIgnoreCase(mode)) {
                result = compiler.previewHtml(filePath, filename);
            } else if ("java".equalsIgnoreCase(mode)) {
                result = compiler.compileJava(filePath, filename, run);
            } else if ("maven".equalsIgnoreCase(mode)) {
                result = compiler.compileMaven(filePath, run);
            } else if ("cpp".equalsIgnoreCase(mode)) {
                result = compiler.compileAndRunCpp(filePath, filename, run);
            } else if ("python".equalsIgnoreCase(mode)) {
                result = compiler.runPython(filePath, filename, run);
            } else if ("node".equalsIgnoreCase(mode)) {
                result = compiler.runNode(filePath, filename, run);
            } else {
                result = compiler.compileAuto(filePath, filename, run);
            }


            if (!isErrorResult(result)) {
                Path projectDir = filePath.getParent();
                if (Files.isDirectory(filePath)) {
                    projectDir = filePath;
                }
                Path sandboxRoot = Paths.get(AgentConfig.getSandboxDir()).toAbsolutePath().normalize();  // ← 加 toAbsolutePath()


                if (projectDir != null && projectDir.startsWith(sandboxRoot)) {
                    writeEntryFile(projectDir, filename, mode);
                } else {
                    logger.warn("⚠️ 路径检查未通过，跳过写入");
                }
            }

            return result;

        } catch (IOException e) {
            logger.error("编译运行异常", e);
            return "编译运行异常: " + e.getMessage();
        }
    }

    // 辅助判断：检查结果是否包含错误标识
    // @anchor: toolExecutor_isError
    // 判断编译/运行结果字符串是否表示失败
    private boolean isErrorResult(String result) {
        if (result == null) return true;
        // 不再检查 "error"，因为编译输出可能包含它但编译是成功的
        return result.startsWith("❌") ||
                result.contains("失败") ||
                result.contains("超时") ||
                result.contains("未找到") ||
                result.contains("exception");
    }

    // 写入注册表
    // @anchor: toolExecutor_writeEntry
    // 把最近一次成功运行的入口信息写入 .agent_entry.json
    private void writeEntryFile(Path projectDir, String filename, String mode) {
        try {
            Path entryFile = projectDir.resolve(".agent_entry.json");
            logger.info("📝 正在写入注册表: " + entryFile);

            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("filename", filename);
            meta.put("mode", mode);
            String json = new ObjectMapper().writeValueAsString(meta);
            Files.writeString(entryFile, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            logger.info("✅ 注册表写入成功");
        } catch (IOException e) {
            logger.warn("❌ 注册表写入失败: {}", e.getMessage());
            e.printStackTrace();
        }
    }

    // @anchor: toolExecutor_getFileStructure
    // 解析文件并用格式化器输出精简的代码结构
    private String getFileStructure(String filename) {
        try {
            Path filePath = PathUtils.safeResolve(filename);
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return "❌ 文件不存在: " + filename;
            }

            StructureParser parser = StructureParserRegistry.getInstance().getParser(filePath);
            FileStructure structure = parser.parse(filePath);

            // 使用格式化器输出精简文本
            return FileStructureFormatter.format(structure);

        } catch (IOException e) {
            return "❌ 解析文件结构失败: " + e.getMessage();
        }
    }

    /**
     * 遍历沙箱下所有项目，逐个重建 .project_index.json。
     * 由 Main.run() 的 finally 块调用，一次运行结束刷新一次。
     */
    // @anchor: toolExecutor_refreshAllProjectIndexes
    // 一次运行结束后遍历沙箱各项目，重建其 .project_index.json
    public void refreshAllProjectIndexes() {
        try {
            Path sandbox = Paths.get(AgentConfig.getSandboxDir()).toAbsolutePath().normalize();
            if (!Files.isDirectory(sandbox)) return;

            try (var stream = Files.list(sandbox)) {
                for (Path projectDir : (Iterable<Path>) stream::iterator) {
                    if (!Files.isDirectory(projectDir)) continue;
                    String name = projectDir.getFileName().toString();
                    if (name.startsWith(".")) continue;

                    Path anchorsFile = projectDir.resolve(AgentConfig.getAnchorIndexName());
                    if (!Files.exists(anchorsFile)) continue;

                    try {
                        anchorMgr.rebuildProjectIndex(name);
                    } catch (Exception e) {
                        logger.warn("刷新项目索引失败: {} -> {}", name, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("刷新所有项目索引失败: {}", e.getMessage());
        }
    }

    /**
     * 由 Main 在一轮工具调用结束后触发，批量刷新锚点索引。
     */
    // @anchor: toolExecutor_flushDirtyAnchors
    // 一轮工具调用结束后批量刷新锚点索引中的脏文件
    public void flushDirtyAnchors() {
        anchorMgr.flushDirty();
    }

}
