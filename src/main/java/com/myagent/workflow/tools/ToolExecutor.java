package com.myagent.workflow.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.AgentConfig;
import com.myagent.workflow.core.ContextManager;
import com.myagent.workflow.core.Main;
import com.myagent.workflow.model.FileStructure;
import com.myagent.workflow.parser.FileStructureFormatter;
import com.myagent.workflow.parser.StructureParser;
import com.myagent.workflow.parser.StructureParserRegistry;
import com.myagent.workflow.security.ScanResult;
import com.myagent.workflow.security.SecurityScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * @anchor: toolExecutor_class
 * 工具执行器 —— 实现所有 Agent 可调用工具的具体逻辑。
 * 从 Main.java 中独立出来，Main 仅保留工作流编排。
 */
public class ToolExecutor {
    private static final Logger logger = LoggerFactory.getLogger(ToolExecutor.class);

    private final FileOperator fileOp;
    private final CodeSearcher searcher;
    private final Compiler compiler;
    private final AnchorManager anchorMgr;
    private Consumer<String> logConsumer;
    private final Consumer<String> modelSwitcher;  // 新增：模型切换回调
    // 字段
    private final ContextManager contextManager; // 替换原来的 Main main
    private ObjectMapper objectMapper;

    private final AgentConfig config;



    // @anchor: toolExecutor_constructor
    public ToolExecutor(AgentConfig config, ObjectMapper objectMapper, Consumer<String> modelSwitcher, ContextManager contextManager) {
        this.config = config;
        this.compiler = new Compiler(config);
        this.fileOp = new FileOperator();
        this.objectMapper = objectMapper;
        this.anchorMgr = new AnchorManager(objectMapper);
        this.searcher = new CodeSearcher(anchorMgr);
        this.modelSwitcher = modelSwitcher;  // 初始化
        this.contextManager = contextManager;
    }

    public void setLogConsumer(Consumer<String> consumer) {
        this.logConsumer = consumer;
    }

    // ==================== 工具调度入口 ====================

    /**
     * @anchor: toolExecutor_dispatch
     * 根据工具名和参数分发执行，返回结果字符串。
     */
    public String dispatch(String functionName, Map<String, Object> args) throws IOException {
        switch (functionName) {
            case "write_file":
                return fileOp.writeFile((String) args.get("filename"), (String) args.get("code"));
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
                return anchorMgr.listAnchors((String) args.get("project_path"));
            case "insert_at_anchor":
                return anchorMgr.insertAtAnchor(
                        (String) args.get("anchor_id"),
                        (String) args.get("content"),
                        (String) args.getOrDefault("position", "after"));
            case "delete_between_anchors":
                return anchorMgr.deleteBetweenAnchors(
                        (String) args.get("startAnchor"),
                        (String) args.get("endAnchor")
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
            case "switch_model":
                return switchModel(args);
            case "query_history":
                return queryHistory(args);
            case "read_between_anchors":
                return anchorMgr.readBetweenAnchors(
                        (String) args.get("startAnchor"),
                        (String) args.get("endAnchor")
                );
            case "get_file_structure":
                return getFileStructure((String) args.get("filename"));
            case "request_checkpoint":
                String phaseSummary = (String) args.get("phase_summary");
                String nextPlan = (String) args.get("next_plan");
                return contextManager.requestCheckpoint(phaseSummary, nextPlan);
            default:
                return "未知工具: " + functionName;
        }
    }


    // ==================== 工具 : compile_and_run ====================

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

    private String switchModel(Map<String, Object> args) {
        if (modelSwitcher == null) {
            return "⚠️ 模型切换功能未启用（回调未设置）";
        }
        String target = (String) args.get("target");
        if (target == null) {
            return "❌ 缺少参数 'target'，请指定 'pro' 或 'flash'";
        }
        String modelName;
        if ("pro".equalsIgnoreCase(target)) {
            modelName = "deepseek-v4-pro";
        } else if ("flash".equalsIgnoreCase(target)) {
            modelName = "deepseek-v4-flash";
        } else {
            return "❌ 不支持的模型类型: " + target + "，请使用 'pro' 或 'flash'";
        }
        modelSwitcher.accept(modelName);
        return "✅ 模型已切换至: " + modelName;
    }

    // queryHistory 方法
    private String queryHistory(Map<String, Object> args) {
        String keyword = (String) args.get("keyword");
        int limit = args.containsKey("limit") ? (int) args.get("limit") : 10;
        if (limit <= 0) limit = 10;

        Path historyFile = contextManager.getHistoryFile();
        if (historyFile == null || !Files.exists(historyFile)) {
            return "[]";
        }

        List<Map<String, Object>> results = new ArrayList<>();
        try (Stream<String> lines = Files.lines(historyFile, StandardCharsets.UTF_8)) {
            Iterator<String> iterator = lines.iterator();
            while (iterator.hasNext()) {
                String line = iterator.next();
                if (line.trim().isEmpty()) continue;
                JsonNode node = objectMapper.readTree(line);
                if (node.has("content")) {
                    String content = node.get("content").asText();
                    boolean matched = false;
                    try {
                        matched = Pattern.compile(keyword, Pattern.CASE_INSENSITIVE)
                                .matcher(content).find();
                    } catch (Exception e) {
                        matched = content.toLowerCase().contains(keyword.toLowerCase());
                    }
                    if (matched) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("role", node.get("role").asText());
                        String snippet = content.length() > 200 ? content.substring(0, 200) + "..." : content;
                        entry.put("snippet", snippet);
                        results.add(entry);
                        if (results.size() >= limit) break;
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("⚠️ 查询历史失败: " + e.getMessage());
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(results);
        } catch (Exception e) {
            return "[]";
        }
    }

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

}
