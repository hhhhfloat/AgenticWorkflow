// @anchor: toolExecutor_intro
// 工具分发：按工具名校验后执行并返回结果
package com.myagent.workflow.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.config.AgentConfig;
import com.myagent.workflow.tools.anchor.AnchorManager;
import com.myagent.workflow.tools.runner.CompileRunner;
import com.myagent.workflow.tools.runner.Compiler;
import com.myagent.workflow.tools.runner.FileStructureRunner;
import com.myagent.workflow.tools.search.CodeSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private final AnchorManager anchorMgr;
    private final PathPolicy pathPolicy;                       // ← 新增
    private final CompileRunner compileRunner;
    private final FileStructureRunner fileStructureRunner;
    private final Map<String, ToolHandler> handlers = new HashMap<>();  // ← 新增
    private Consumer<String> logConsumer;
    private ObjectMapper objectMapper;
    private final AgentConfig config;
    private String workProject;

    // @anchor: toolExecutor_handler
    // 工具处理器：接收 Args，返回结果字符串
    @FunctionalInterface
    private interface ToolHandler {
        String run(Args args);
    }

    // @anchor: toolExecutor_constructor
    public ToolExecutor(AgentConfig config, ObjectMapper objectMapper, String workProject) {
        this(config, objectMapper, workProject, false);
    }

    public ToolExecutor(AgentConfig config, ObjectMapper objectMapper) {
        this(config, objectMapper, null, false);
    }

    // @anchor: toolExecutor_forManualRun
    // 手动执行模式：用于 /runProject 这类用户主动操作，跳过 workProject 写约束
    public static ToolExecutor forManualRun(AgentConfig config, ObjectMapper mapper) {
        return new ToolExecutor(config, mapper, null, true);
    }

    private ToolExecutor(AgentConfig config, ObjectMapper objectMapper,
                         String workProject, boolean manualMode) {
        this.config = config;
        this.fileOp = new FileOperator();
        this.objectMapper = objectMapper;
        this.anchorMgr = new AnchorManager(objectMapper, workProject);
        this.searcher = new CodeSearcher(anchorMgr);
        this.workProject = workProject;
        this.pathPolicy = new PathPolicy(workProject, manualMode);
        Compiler compiler = new Compiler(config);
        this.compileRunner = new CompileRunner(config, compiler);
        this.fileStructureRunner = new FileStructureRunner(anchorMgr);
        registerHandlers();
    }

    // @anchor: toolExecutor_registerHandlers
    // 注册全部工具处理器
    private void registerHandlers() {
        handlers.put("write_file", a -> {
            String filename = a.getString("filename");
            String result = fileOp.writeFile(filename, a.getString("code"));
            if (result != null) anchorMgr.markDirtyByFilename(filename);
            return result;
        });

        handlers.put("compile_and_run", a ->
                compileRunner.run(a.getString("filename"),
                        a.getString("mode", "auto"),
                        a.getBool("run", true)));

        handlers.put("list_directory", a ->
                fileOp.listDirectory(a.getString("path", "."),
                        a.getBool("recursive", false)));

        handlers.put("read_file", a -> fileOp.readFile(a.getString("filename")));

        handlers.put("delete_file", a -> {
            String filename = a.getString("filename");
            String result = fileOp.deleteFile(filename);
            if (result != null && result.startsWith("✅")) {
                anchorMgr.markDirtyByFilename(filename);
            }
            return result;
        });

        handlers.put("search_text", a ->
                searcher.searchText(a.getString("keyword"),
                        a.getString("file_pattern", ".*"),
                        a.getString("path", ".")));

        handlers.put("build_anchor_index", a ->
                anchorMgr.buildAnchorIndex(a.getString("project_path")));

        handlers.put("insert_at_anchor", a ->
                anchorMgr.insertAtAnchor(a.getString("anchor_id"),
                        a.getString("content"),
                        a.getString("position", "after"),
                        a.getString("file")));

        handlers.put("delete_between_anchors", a ->
                anchorMgr.deleteBetweenAnchors(a.getString("startAnchor"),
                        a.getString("endAnchor"),
                        a.getString("file")));

        handlers.put("describe_anchors", a ->
                anchorMgr.describeAnchors(a.getString("project_path"),
                        a.getString("file")));

        handlers.put("find_references", a ->
                searcher.findReferences(a.getString("symbol"),
                        a.getString("path", "."),
                        a.getString("file_pattern", null)));

        handlers.put("find_callers", a ->
                searcher.findCallers(a.getString("functionName"),
                        a.getString("path", "."),
                        a.getString("file_pattern", null)));

        handlers.put("find_callees", a ->
                searcher.findCallees(a.getString("functionName"),
                        a.getString("path", "."),
                        a.getBool("recursive", false),
                        a.getInt("depth", 1)));

        handlers.put("read_between_anchors", a ->
                anchorMgr.readBetweenAnchors(a.getString("startAnchor"),
                        a.getString("endAnchor"),
                        a.getString("file")));

        handlers.put("get_file_structure", a ->
                fileStructureRunner.run(a.getString("filename")));
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
    // 工具调度入口：路径校验 + 查表分发
    public String dispatch(String functionName, Map<String, Object> args) throws IOException {
        Args a = new Args(args);

        String violation = pathPolicy.check(functionName, a);
        if (violation != null) return violation;

        ToolHandler handler = handlers.get(functionName);
        if (handler == null) return "未知工具: " + functionName;
        return handler.run(a);
    }

    // ==================== 工具 : compile_and_run ====================

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
                        // O1：索引仍新鲜（无外部改动）则跳过重建
                        if (anchorMgr.isProjectFresh(name)) continue;
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
