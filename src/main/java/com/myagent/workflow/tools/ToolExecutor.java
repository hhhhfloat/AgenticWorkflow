package com.myagent.workflow.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.AgentConfig;
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

    // @anchor: toolExecutor_constructor
    public ToolExecutor(AgentConfig config, ObjectMapper objectMapper) {
        this.compiler = new Compiler(config);
        this.fileOp = new FileOperator();
        this.anchorMgr = new AnchorManager(objectMapper);
        this.searcher = new CodeSearcher(anchorMgr);
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
            default:
                return "未知工具: " + functionName;
        }
    }


    // ==================== 工具 : compile_and_run ====================

    private String compileAndRun(String filename, String mode, boolean run) {
        try {
            Path filePath = PathUtils.safeResolve(filename);

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

}
