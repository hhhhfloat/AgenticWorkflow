package com.myagent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import okhttp3.*;
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

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    // DeepSeek API 配置
    private static final String API_URL = "https://api.deepseek.com/chat/completions";
    // 使用最新的 V4-Pro 模型（如果你的是 V4-Flash，可改为 deepseek-v4-flash）
    private static final String MODEL = "deepseek-v4-pro";
    // 沙箱目录：所有生成的文件和执行都在这个目录下进行，保证安全
    private static final String SANDBOX_DIR = "./sandbox";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    private java.util.function.Consumer<String> logConsumer = null;

    private volatile boolean stopRequested = false;

    private static final String ANCHOR_INDEX_FILE = "./sandbox/.anchor_index.json";

    public void setLogConsumer(java.util.function.Consumer<String> consumer) {
        this.logConsumer = consumer;
    }

    public Main(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();

        // 确保沙箱目录存在
        File sandbox = new File(SANDBOX_DIR);
        if (!sandbox.exists()) {
            sandbox.mkdirs();
        }
    }

    /**
     * 运行 Agent 工作流
     * @param userRequest 用户自然语言需求
     * @return Agent 的最终回答
     */
    public String run(String userRequest) throws IOException {
        // 初始化消息列表
        List<Map<String, Object>> messages = new ArrayList<>();

        // 系统提示：定义 Agent 的行为规则
        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", """
            你是一个资深全栈开发工程师，生成离线工具。
            
            ## 一、工作模式
            - 新建：沙箱根目录下创建独立子目录（英文小写，连字符分隔，如 `braille-translator`）。
            - 重构（整理/重构/优化）：提取内联 CSS/JS 为独立文件，分离模块化功能，更新引用，清理冗余。
            - 修改：在已有项目目录内操作，不得创建同名新目录。
            
            ## 二、项目结构（强制）
            **HTML 项目**：必须拆为三个文件，放在项目子目录下：
            - `index.html`（仅结构，禁止 `<style>` 和 `<script>`）
            - `style.css`（全部样式）
            - `script.js`（全部逻辑）
            - 通过 `<link>` 和 `<script src>` 引用。
            
            **Java 项目**：按 Maven 标准结构组织：
            - 源码放 `<项目名>/src/main/java/` 下，按功能分包，入口类含 `main` 方法。
            
            ## 三、操作流程
            1. 动手前：`list_directory` + `read_file`。
            2. 动手后：调用 `compile_and_run` 验证。
            3. 出错：分析错误 → 修改 → 重验，直至成功。
            4. 完成后：输出清晰的文档。
            
            ## 四、工具速查
            - `search_text(keyword, file_pattern?, path?)` → 搜索文本，返回路径:行号:预览。修改前必须先搜引用。
            - `list_directory(path?, recursive?)` → 列出目录（recursive=true 递归展开）。
            - `read_file(filename)` → 读取文件内容。
            - `write_java_file(filename, code)` → 写入文件（自动创建父目录）。
            - `compile_and_run(filename)` → 编译运行 .java，或浏览器预览 .html。
            - `delete_file(filename)` → 删除文件（不可恢复）。
            - `build_anchor_index(project_path)` → 扫描项目，提取 @anchor 注释，重建锚点索引。
            - `list_anchors(project_path)` → 列出项目的所有锚点（ID + 文件 + 行号）。
            
            ## 五、约束
            - 所有操作在 `./sandbox` 内，路径使用相对路径。
            - 后端仅用 Java 标准 JDK 库（`java.*`、`javax.*`）。
            - 写入后自动推送目录快照，无需重复 `list_directory`。
            - **修改方法/变量前必须先 `search_text` 查引用**。
            
            ## 六、效率
            - **并行调用**：同一次响应中可返回多个工具调用，减少迭代次数。
            - 示例：同时 `list_directory` + `search_text`。
            
            ## 七、锚点系统
            - 标记：在类/方法/代码块关键位置加 `@anchor:` 注释。
              - Java/JS: `// @anchor: 名称`
              - CSS: `/* @anchor: 名称 */`
              - HTML: `<!-- @anchor: 名称 -->`
              - 命名：`模块_功能`，如 `braille_encode`。
            - 修改后调用 `build_anchor_index(项目名)` 更新索引。
            - 用 `list_anchors(项目名)` 查看锚点，定位修改位置。
        """);
        messages.add(systemMsg);

        // 用户请求
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userRequest);
        messages.add(userMsg);

        // 定义工具（Tools）—— 与 DeepSeek Function Calling 格式一致
        List<Map<String, Object>> tools = buildToolDefinitions();

        // 最大迭代次数（防止无限循环）
        final int MAX_ITERATIONS = 15;

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            checkStop();  // ← 新增
            logIf("--- 第 " + (iteration + 1) + " 次迭代 ---");

            // 构建 API 请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", MODEL);
            requestBody.put("messages", messages);
            requestBody.put("tools", tools);
            requestBody.put("tool_choice", "auto");

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            // 发送请求
            checkStop();  // ← 新增
            Request httpRequest = new Request.Builder()
                    .url(API_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                    .build();

            String responseBody;
            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("API 请求失败: " + response.code() + " " + response.message());
                }
                responseBody = response.body().string();
            }

            // 解析响应
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.get("choices");
            if (choices == null || choices.size() == 0) {
                throw new IOException("API 返回异常: " + responseBody);
            }
            JsonNode messageNode = choices.get(0).get("message");
            Map<String, Object> assistantMsg = objectMapper.convertValue(messageNode, Map.class);
            messages.add(assistantMsg);

            // 检查是否有 tool_calls
            if (!messageNode.has("tool_calls") || messageNode.get("tool_calls").size() == 0) {
                // 没有工具调用，说明 Agent 已完成任务
                String content = messageNode.has("content") ? messageNode.get("content").asText() : "任务完成";
                logIf("Agent 完成: " + content);
                return content;
            }

            // 处理每个 tool_call
            ArrayNode toolCalls = (ArrayNode) messageNode.get("tool_calls");
            for (JsonNode tc : toolCalls) {
                String toolCallId = tc.get("id").asText();
                String functionName = tc.get("function").get("name").asText();
                String argumentsJson = tc.get("function").get("arguments").asText();
                logIf("🤖 模型决策: 调用工具 [" + functionName + "]");

                Map<String, Object> args = objectMapper.readValue(argumentsJson, Map.class);

                String result;
                checkStop();  // ← 新增
                switch (functionName) {
                    case "write_java_file":
                        result = writeJavaFile(
                                (String) args.get("filename"),
                                (String) args.get("code")
                        );
                        break;
                    case "compile_and_run":
                        result = compileAndRun((String) args.get("filename"));
                        break;
                    case "list_directory":
                        String listPath = (String) args.getOrDefault("path", ".");
                        boolean recursive = args.containsKey("recursive") && (boolean) args.get("recursive");
                        result = listDirectory(listPath, recursive);
                        break;
                    case "read_file":
                        result = readFile((String) args.get("filename"));
                        break;
                    case "delete_file":
                        result = deleteFile((String) args.get("filename"));
                        break;
                    case "search_text":
                        String keyword = (String) args.get("keyword");
                        String filePattern = (String) args.getOrDefault("file_pattern", ".*");
                        String searchPath = (String) args.getOrDefault("path", ".");
                        result = searchText(keyword, filePattern, searchPath);
                        break;
                    case "build_anchor_index":
                        String projectPath = (String) args.get("project_path");
                        result = buildAnchorIndex(projectPath);
                        break;
                    case "list_anchors":
                        String listProjectPath = (String) args.get("project_path");
                        result = listAnchors(listProjectPath);
                        break;
                    default:
                        result = "未知工具: " + functionName;
                }

                // 将工具执行结果作为 tool 消息添加到对话中
                Map<String, Object> toolMsg = new HashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", toolCallId);
                toolMsg.put("content", result);
                messages.add(toolMsg);

                String displayResult;
                if ("read_file".equals(functionName)) {
                    int totalLen = result.length();
                    if (totalLen > 300) {
                        displayResult = result.substring(0, 200) + "... [共 " + totalLen + " 字符，已截断显示]";
                    } else {
                        displayResult = result;
                    }
                } else {
                    displayResult = result;
                }
                logIf("工具 [" + functionName + "] 执行结果: " + displayResult);
            }
        }

        return "达到最大迭代次数，任务可能未完成。请检查生成的代码。";
    }

    /**
     * 构建工具定义（符合 DeepSeek Function Calling 规范）
     */
    private List<Map<String, Object>> buildToolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();

        // 工具1: list_directory
        Map<String, Object> listDirTool = new HashMap<>();
        listDirTool.put("type", "function");
        Map<String, Object> listFunc = new HashMap<>();
        listFunc.put("name", "list_directory");
        listFunc.put("description", "列出沙箱目录下指定路径的所有文件和子目录。如果 recursive 为 true，则递归列出所有层级（慎用，仅当项目较小时使用）。");
        Map<String, Object> listParams = new HashMap<>();
        listParams.put("type", "object");
        Map<String, Object> listProps = new HashMap<>();
        listProps.put("path", Map.of(
                "type", "string",
                "description", "要列出的相对路径，例如 '.' 表示沙箱根目录。默认为 '.'。"
        ));
        listProps.put("recursive", Map.of(
                "type", "boolean",
                "description", "是否递归列出所有子目录内容。默认为 false。仅在需要全面了解项目结构时设为 true。"
        ));
        listParams.put("properties", listProps);
        listParams.put("required", List.of()); // 不强制任何参数
        listFunc.put("parameters", listParams);
        listDirTool.put("function", listFunc);
        tools.add(listDirTool);

        // 工具2: write_java_file
        Map<String, Object> writeFileTool = new HashMap<>();
        writeFileTool.put("type", "function");
        Map<String, Object> writeFunc = new HashMap<>();
        writeFunc.put("name", "write_java_file");
        writeFunc.put("description", "将源代码（Java 或 HTML/CSS/JS）写入沙箱目录下的指定文件。如果文件已存在则覆盖。");
        Map<String, Object> writeParams = new HashMap<>();
        writeParams.put("type", "object");
        Map<String, Object> writeProps = new HashMap<>();
        writeProps.put("filename", Map.of(
                "type", "string",
                "description", "文件名，例如 Tool.java （必须包含 .java 后缀）"
        ));
        writeProps.put("code", Map.of(
                "type", "string",
                "description", "完整的 Java 源代码（包括 package 声明）"
        ));
        writeParams.put("properties", writeProps);
        writeParams.put("required", List.of("filename", "code"));
        writeFunc.put("parameters", writeParams);
        writeFileTool.put("function", writeFunc);
        tools.add(writeFileTool);

        // 工具3: compile_and_run
        Map<String, Object> runTool = new HashMap<>();
        runTool.put("type", "function");
        Map<String, Object> runFunc = new HashMap<>();
        runFunc.put("name", "compile_and_run");
        runFunc.put("description", "验证并运行/预览沙箱目录下的文件。如果文件是 .java 则编译并运行；如果文件是 .html 则在浏览器中打开预览。");
        Map<String, Object> runParams = new HashMap<>();
        runParams.put("type", "object");
        Map<String, Object> runProps = new HashMap<>();
        runProps.put("filename", Map.of(
                "type", "string",
                "description", "要执行的 Java 文件名（例如 Tool.java）"
        ));
        runParams.put("properties", runProps);
        runParams.put("required", List.of("filename"));
        runFunc.put("parameters", runParams);
        runTool.put("function", runFunc);
        tools.add(runTool);



        // 工具4: read_file
        Map<String, Object> readFileTool = new HashMap<>();
        readFileTool.put("type", "function");
        Map<String, Object> readFunc = new HashMap<>();
        readFunc.put("name", "read_file");
        readFunc.put("description", "读取沙箱目录下指定文件的内容（文本格式），返回文件内容。支持 Java、HTML、TXT 等文本文件。读取大小限制为 5000 字符，超过则截断并提示。");
        Map<String, Object> readParams = new HashMap<>();
        readParams.put("type", "object");
        Map<String, Object> readProps = new HashMap<>();
        readProps.put("filename", Map.of(
                "type", "string",
                "description", "文件名（相对路径），例如 'calculator.html' 或 'src/Tool.java'。"
        ));
        readParams.put("properties", readProps);
        readParams.put("required", List.of("filename"));
        readFunc.put("parameters", readParams);
        readFileTool.put("function", readFunc);
        tools.add(readFileTool);


        // 工具5: delete_file
        Map<String, Object> deleteFileTool = new HashMap<>();
        deleteFileTool.put("type", "function");
        Map<String, Object> deleteFunc = new HashMap<>();
        deleteFunc.put("name", "delete_file");
        deleteFunc.put("description", "删除沙箱目录下的指定文件。请谨慎使用，确认该文件不再需要后再删除。");
        Map<String, Object> deleteParams = new HashMap<>();
        deleteParams.put("type", "object");
        Map<String, Object> deleteProps = new HashMap<>();
        deleteProps.put("filename", Map.of(
                "type", "string",
                "description", "要删除的文件相对路径，例如 'calculator.html' 或 'src/main/java/com/old/Class.java'。"
        ));
        deleteParams.put("properties", deleteProps);
        deleteParams.put("required", List.of("filename"));
        deleteFunc.put("parameters", deleteParams);
        deleteFileTool.put("function", deleteFunc);
        tools.add(deleteFileTool);

        // 工具6: search_text
        Map<String, Object> searchTool = new HashMap<>();
        searchTool.put("type", "function");
        Map<String, Object> searchFunc = new HashMap<>();
        searchFunc.put("name", "search_text");
        searchFunc.put("description", "在沙箱目录中搜索指定文本（支持正则表达式），返回匹配的文件路径、行号和内容预览。用于查找代码引用、定位函数调用等。结果限制最多 30 条，超出会提示缩小范围。");
        Map<String, Object> searchParams = new HashMap<>();
        searchParams.put("type", "object");
        Map<String, Object> searchProps = new HashMap<>();
        searchProps.put("keyword", Map.of(
                "type", "string",
                "description", "要搜索的关键词（支持正则表达式，如 'parse\\('）"
        ));
        searchProps.put("file_pattern", Map.of(
                "type", "string",
                "description", "文件匹配模式，如 '*.java'、'*.html'，默认为所有文本文件（.java,.html,.css,.js,.txt,.xml,.json,.md,.properties,.yml,.yaml）"
        ));
        searchProps.put("path", Map.of(
                "type", "string",
                "description", "搜索起始相对路径，默认为 '.'（沙箱根目录）"
        ));
        searchParams.put("properties", searchProps);
        searchParams.put("required", List.of("keyword"));
        searchFunc.put("parameters", searchParams);
        searchTool.put("function", searchFunc);
        tools.add(searchTool);

        // 工具7: build_anchor_index
        Map<String, Object> buildAnchorTool = new HashMap<>();
        buildAnchorTool.put("type", "function");
        Map<String, Object> buildAnchorFunc = new HashMap<>();
        buildAnchorFunc.put("name", "build_anchor_index");
        buildAnchorFunc.put("description", "扫描指定项目目录下的所有文本文件，提取所有 @anchor 注释，重建锚点索引文件。通常在修改代码后调用此工具更新索引。");
        Map<String, Object> buildAnchorParams = new HashMap<>();
        buildAnchorParams.put("type", "object");
        Map<String, Object> buildAnchorProps = new HashMap<>();
        buildAnchorProps.put("project_path", Map.of(
                "type", "string",
                "description", "项目相对路径，如 'cipher-translator'。如果为空，则扫描整个沙箱。"
        ));
        buildAnchorParams.put("properties", buildAnchorProps);
        buildAnchorParams.put("required", List.of("project_path"));
        buildAnchorFunc.put("parameters", buildAnchorParams);
        buildAnchorTool.put("function", buildAnchorFunc);
        tools.add(buildAnchorTool);

// 工具8: list_anchors
        Map<String, Object> listAnchorTool = new HashMap<>();
        listAnchorTool.put("type", "function");
        Map<String, Object> listAnchorFunc = new HashMap<>();
        listAnchorFunc.put("name", "list_anchors");
        listAnchorFunc.put("description", "列出指定项目中的所有锚点，返回锚点ID、所在文件、行号和内容预览。");
        Map<String, Object> listAnchorParams = new HashMap<>();
        listAnchorParams.put("type", "object");
        Map<String, Object> listAnchorProps = new HashMap<>();
        listAnchorProps.put("project_path", Map.of(
                "type", "string",
                "description", "项目相对路径，如 'cipher-translator'。"
        ));
        listAnchorParams.put("properties", listAnchorProps);
        listAnchorParams.put("required", List.of("project_path"));
        listAnchorFunc.put("parameters", listAnchorParams);
        listAnchorTool.put("function", listAnchorFunc);
        tools.add(listAnchorTool);

        return tools;
    }

    /**
     * 工具实现：写入 Java 源文件到沙箱目录
     */
    private String writeJavaFile(String filename, String code) {
        try {
            Path filePath = Paths.get(SANDBOX_DIR, filename);
            Files.createDirectories(filePath.getParent());
            try (FileWriter writer = new FileWriter(filePath.toFile())) {
                writer.write(code);
            }

            if (logConsumer != null) {
                String snapshot = listDirectory(".", false);
                logConsumer.accept("[系统] 📁 目录结构已更新：\n" + snapshot);
            }

            // 返回简洁确认（只告诉 Agent 文件名，不重复描述）
            return "✅ " + filename;
        } catch (IOException e) {
            logger.error("写入文件失败", e);
            return "写入文件失败: " + e.getMessage();
        }
    }

    /**
     * 搜索沙箱目录中的文本（支持正则）
     * @param keyword 搜索关键词（正则表达式）
     * @param filePattern 文件匹配模式，如 "*.java"
     * @param path 起始相对路径
     * @return 搜索结果摘要
     */
    private String searchText(String keyword, String filePattern, String path) {
        try {
            Path startPath = Paths.get(SANDBOX_DIR, path);
            if (!Files.exists(startPath) || !Files.isDirectory(startPath)) {
                return "路径不存在或不是目录: " + path;
            }

            // 编译正则
            java.util.regex.Pattern pattern;
            try {
                pattern = java.util.regex.Pattern.compile(keyword);
            } catch (java.util.regex.PatternSyntaxException e) {
                return "关键词正则表达式错误: " + e.getMessage() + "。如需普通文本搜索，请转义特殊字符。";
            }

            // 排除目录
            List<String> excludedDirs = Arrays.asList("target", "build", ".git", ".idea", "node_modules");
            // 支持的文本文件扩展名
            List<String> textExtensions = Arrays.asList(".java", ".html", ".htm", ".css", ".js", ".jsx", ".ts", ".tsx",
                    ".txt", ".xml", ".json", ".md", ".properties", ".yml", ".yaml", ".sh", ".bat", ".gradle", ".sql");

            // 解析 filePattern
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

            // 使用 AtomicInteger 在 lambda 中安全计数
            java.util.concurrent.atomic.AtomicInteger matchCount = new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.concurrent.atomic.AtomicInteger resultCount = new java.util.concurrent.atomic.AtomicInteger(0);
            List<String> results = Collections.synchronizedList(new ArrayList<>());

            // 标记是否达到上限
            final int MAX_RESULTS = 30;

            Files.walk(startPath)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        // 如果已经达到上限，跳过后续处理
                        if (resultCount.get() >= MAX_RESULTS) {
                            return;
                        }
                        try {
                            Path relativePath = startPath.relativize(file);
                            String relPathStr = relativePath.toString().replace('\\', '/');

                            // 检查是否排除目录
                            for (String excluded : excludedDirs) {
                                if (relPathStr.startsWith(excluded + "/") || relPathStr.startsWith(excluded + "\\")) {
                                    return;
                                }
                            }

                            // 检查文件扩展名
                            String fileName = file.getFileName().toString();
                            String ext = "";
                            int dotIdx = fileName.lastIndexOf('.');
                            if (dotIdx > 0) {
                                ext = fileName.substring(dotIdx).toLowerCase();
                            }

                            if (!patterns.isEmpty()) {
                                boolean matchedExt = false;
                                for (String pat : patterns) {
                                    if (pat.startsWith(".") && ext.equals(pat)) {
                                        matchedExt = true;
                                        break;
                                    } else if (ext.equals("." + pat)) {
                                        matchedExt = true;
                                        break;
                                    } else if (fileName.endsWith(pat)) {
                                        matchedExt = true;
                                        break;
                                    }
                                }
                                if (!matchedExt) return;
                            } else {
                                if (!textExtensions.contains(ext)) {
                                    return;
                                }
                            }

                            // 读取文件并逐行匹配
                            List<String> lines = Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8);
                            int lineNum = 0;
                            for (String line : lines) {
                                lineNum++;
                                if (resultCount.get() >= MAX_RESULTS) {
                                    break;
                                }
                                if (pattern.matcher(line).find()) {
                                    String preview = line.trim();
                                    if (preview.length() > 80) {
                                        preview = preview.substring(0, 80) + "...";
                                    }
                                    results.add(relPathStr + ":" + lineNum + ":" + preview);
                                    matchCount.incrementAndGet();
                                    resultCount.incrementAndGet();
                                }
                            }
                        } catch (IOException e) {
                            // 忽略无法读取的文件
                        }
                    });

            // 构建返回信息
            if (results.isEmpty()) {
                return "🔍 未找到匹配 \"" + keyword + "\" 的内容。";
            }

            StringBuilder sb = new StringBuilder();
            int total = matchCount.get();
            int shown = results.size();
            sb.append("🔍 找到 ").append(total).append(" 条匹配结果");
            if (total > shown) {
                sb.append("（仅显示前 ").append(MAX_RESULTS).append(" 条）");
            }
            sb.append("：\n");
            for (String result : results) {
                sb.append(result).append("\n");
            }
            return sb.toString();

        } catch (IOException e) {
            logger.error("搜索失败", e);
            return "搜索失败: " + e.getMessage();
        }
    }

    /**
     * 工具实现：编译并运行 Java 文件
     */
    private String compileAndRun(String filename) {
        try {
            // ========== 新增：处理 HTML 文件 ==========
            if (filename.endsWith(".html") || filename.endsWith(".htm")) {
                File htmlFile = Paths.get(SANDBOX_DIR, filename).toFile();
                if (!htmlFile.exists()) {
                    return "HTML 文件不存在: " + filename;
                }
                // 使用 Java Desktop API 在默认浏览器中打开
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(htmlFile.toURI());
                    return "✅ 已在默认浏览器中打开 " + filename + "（请查看弹窗或新标签页）";
                } else {
                    return "⚠️ 当前系统不支持自动打开浏览器，请手动打开文件: " + htmlFile.getAbsolutePath();
                }
            }
            // 1. 编译
            ProcessBuilder compilePb = new ProcessBuilder(
                    "javac",
                    "-d", SANDBOX_DIR,   // 将 class 文件输出到沙箱根目录
                    Paths.get(SANDBOX_DIR, filename).toString()
            );
            compilePb.redirectErrorStream(true);
            Process compileProc = compilePb.start();
            int compileExit = compileProc.waitFor();
            String compileOutput = new String(compileProc.getInputStream().readAllBytes());

            if (compileExit != 0) {
                return "编译失败 (退出码 " + compileExit + "):\n" + compileOutput;
            }

            // 2. 运行：需要从文件名推断类名（去掉 .java）
            String className = filename.replace(".java", "");
            // 运行目录设为沙箱目录
            ProcessBuilder runPb = new ProcessBuilder(
                    "java",
                    "-cp", SANDBOX_DIR,
                    className
            );
            runPb.directory(new File(SANDBOX_DIR));
            runPb.redirectErrorStream(true);
            Process runProc = runPb.start();
            // 设置超时（30秒）
            boolean finished = runProc.waitFor(30, TimeUnit.SECONDS);
            String runOutput = new String(runProc.getInputStream().readAllBytes());

            if (!finished) {
                runProc.destroyForcibly();
                return "运行超时（超过30秒），已强制终止。\n输出:\n" + runOutput;
            }

            int runExit = runProc.exitValue();
            if (runExit != 0) {
                return "运行失败 (退出码 " + runExit + "):\n" + runOutput;
            } else {
                return "运行成功！\n输出:\n" + runOutput;
            }
        } catch (IOException | InterruptedException e) {
            logger.error("编译/运行过程异常", e);
            return "编译/运行异常: " + e.getMessage();
        }
    }

    /**
     * 列出沙箱目录下的文件和子目录（相对路径）
     */
    /**
     * 列出沙箱目录下的文件和子目录（支持递归）
     * @param path 相对路径
     * @param recursive 是否递归展开所有子目录
     */
    private String listDirectory(String path, boolean recursive) {
        if (path == null || path.trim().isEmpty()) {
            path = ".";
        }
        try {
            Path dirPath = Paths.get(SANDBOX_DIR, path);
            if (!Files.exists(dirPath) || !Files.isDirectory(dirPath)) {
                return "路径不存在或不是目录: " + path;
            }

            if (!recursive) {
                // 非递归模式保持原样
                StringBuilder sb = new StringBuilder();
                sb.append("📁 目录 ").append(path).append(" 的内容：\n");
                Files.list(dirPath).forEach(p -> {
                    String name = p.getFileName().toString();
                    String type = Files.isDirectory(p) ? "📁" : "📄";
                    sb.append(type).append(" ").append(name).append("\n");
                });
                return sb.toString();
            }

            // ===== 递归模式：压缩格式 =====
            StringBuilder sb = new StringBuilder();
            sb.append("📁 ").append(path).append("/\n");
            buildTree(sb, dirPath, "", true);
            return sb.toString();

        } catch (IOException e) {
            logger.error("列出目录失败", e);
            return "列出目录失败: " + e.getMessage();
        }
    }

    /**
     * 递归构建目录树（压缩格式）
     */
    private void buildTree(StringBuilder sb, Path dir, String indent, boolean isLast) {
        try {
            List<Path> entries = Files.list(dir)
                    .sorted((a, b) -> {
                        // 目录在前，文件在后
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
                    // 目录：显示名称 + 统计子项数量
                    long subCount;
                    try (var stream = Files.list(p)) {
                        subCount = stream.count();
                    }
                    sb.append(indent).append(prefix).append("📁 ").append(name);
                    if (subCount > 0) {
                        sb.append(" (").append(subCount).append(" 项)");
                    }
                    sb.append("\n");
                    // 递归进入子目录
                    String nextIndent = indent + (isLastEntry ? "    " : "│   ");
                    buildTree(sb, p, nextIndent, isLastEntry);
                } else {
                    // 文件
                    sb.append(indent).append(prefix).append("📄 ").append(name).append("\n");
                }
            }
        } catch (IOException e) {
            // 忽略无法读取的目录
        }
    }

    /**
     * 读取文本文件内容（限制 50000 字符）
     */
    private String readFile(String filename) {
        try {
            Path filePath = Paths.get(SANDBOX_DIR, filename);
            if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
                return "文件不存在或是一个目录: " + filename;
            }
            // 读取全部内容
            String content = new String(Files.readAllBytes(filePath), java.nio.charset.StandardCharsets.UTF_8);
            // 限制长度，避免上下文爆炸
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

    /**
     * 删除沙箱目录下的文件（仅限文件，不能删除目录）
     */
    private String deleteFile(String filename) {
        try {
            Path filePath = Paths.get(SANDBOX_DIR, filename);
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

    /**
     * 扫描项目目录，提取所有 @anchor 注释，重建锚点索引
     * @param projectPath 项目相对路径，如 "cipher-translator"
     * @return 操作结果
     */
    private String buildAnchorIndex(String projectPath) {
        try {
            Path projectDir = Paths.get(SANDBOX_DIR, projectPath);
            if (!Files.exists(projectDir) || !Files.isDirectory(projectDir)) {
                return "项目目录不存在: " + projectPath;
            }

            // 读取现有索引（如果存在）
            Map<String, Map<String, List<Map<String, Object>>>> index = new HashMap<>();
            Path indexFile = Paths.get(ANCHOR_INDEX_FILE);
            if (Files.exists(indexFile)) {
                String content = new String(Files.readAllBytes(indexFile), StandardCharsets.UTF_8);
                index = objectMapper.readValue(content, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            }

            // 清空该项目在索引中的条目
            index.remove(projectPath);

            // 遍历项目目录下的所有文本文件
            List<String> textExtensions = Arrays.asList(".java", ".html", ".htm", ".css", ".js", ".jsx", ".ts", ".tsx",
                    ".txt", ".xml", ".json", ".md", ".properties", ".yml", ".yaml", ".sh", ".bat", ".gradle", ".sql");
            Map<String, List<Map<String, Object>>> projectAnchors = new HashMap<>();

            Files.walk(projectDir)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        String fileName = file.getFileName().toString();
                        String ext = "";
                        int dotIdx = fileName.lastIndexOf('.');
                        if (dotIdx > 0) {
                            ext = fileName.substring(dotIdx).toLowerCase();
                        }
                        if (!textExtensions.contains(ext)) {
                            return; // 跳过非文本文件
                        }

                        try {
                            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                            String relPath = projectDir.relativize(file).toString().replace('\\', '/');
                            List<Map<String, Object>> anchors = new ArrayList<>();

                            // 匹配 @anchor 注释
                            // 支持 // @anchor: id, /* @anchor: id */, <!-- @anchor: id -->
                            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                                    "//\\s*@anchor:\\s*(\\w+)|/\\*\\s*@anchor:\\s*(\\w+)\\s*\\*/|<!--\\s*@anchor:\\s*(\\w+)\\s*-->");
                            for (int i = 0; i < lines.size(); i++) {
                                String line = lines.get(i);
                                java.util.regex.Matcher matcher = pattern.matcher(line);
                                if (matcher.find()) {
                                    String id = matcher.group(1);
                                    if (id == null) id = matcher.group(2);
                                    if (id == null) id = matcher.group(3);
                                    if (id != null) {
                                        Map<String, Object> anchor = new HashMap<>();
                                        anchor.put("id", id);
                                        anchor.put("line", i + 1);
                                        anchor.put("preview", line.trim());
                                        anchors.add(anchor);
                                    }
                                }
                            }

                            if (!anchors.isEmpty()) {
                                projectAnchors.put(relPath, anchors);
                            }
                        } catch (IOException e) {
                            // 忽略读取失败的文件
                        }
                    });

            if (projectAnchors.isEmpty()) {
                // 没有锚点，从索引中移除该项目
                index.remove(projectPath);
            } else {
                index.put(projectPath, projectAnchors);
            }

            // 写入索引文件
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(index);
            Files.write(indexFile, json.getBytes(StandardCharsets.UTF_8));

            // 统计信息
            int totalFiles = projectAnchors.size();
            int totalAnchors = projectAnchors.values().stream().mapToInt(List::size).sum();
            return "✅ 锚点索引已重建：" + projectPath + " 中找到 " + totalAnchors + " 个锚点，分布在 " + totalFiles + " 个文件中。";

        } catch (IOException e) {
            logger.error("重建锚点索引失败", e);
            return "重建锚点索引失败: " + e.getMessage();
        }
    }

    /**
     * 列出项目的所有锚点
     * @param projectPath 项目相对路径
     * @return 锚点列表
     */
    private String listAnchors(String projectPath) {
        try {
            Path indexFile = Paths.get(ANCHOR_INDEX_FILE);
            if (!Files.exists(indexFile)) {
                return "锚点索引文件不存在，请先运行 build_anchor_index。";
            }

            String content = new String(Files.readAllBytes(indexFile), StandardCharsets.UTF_8);
            Map<String, Map<String, List<Map<String, Object>>>> index =
                    objectMapper.readValue(content, new com.fasterxml.jackson.core.type.TypeReference<>() {});

            if (!index.containsKey(projectPath)) {
                return "项目 " + projectPath + " 没有锚点记录。";
            }

            Map<String, List<Map<String, Object>>> projectAnchors = index.get(projectPath);
            StringBuilder sb = new StringBuilder();
            sb.append("📌 ").append(projectPath).append(" 的锚点列表：\n");
            for (Map.Entry<String, List<Map<String, Object>>> entry : projectAnchors.entrySet()) {
                String file = entry.getKey();
                List<Map<String, Object>> anchors = entry.getValue();
                for (Map<String, Object> anchor : anchors) {
                    String id = (String) anchor.get("id");
                    int line = (int) anchor.get("line");
                    String preview = (String) anchor.get("preview");
                    sb.append("  - ").append(file).append(":").append(line)
                            .append(" [").append(id).append("] ")
                            .append(preview).append("\n");
                }
            }
            return sb.toString();
        } catch (IOException e) {
            logger.error("列出锚点失败", e);
            return "列出锚点失败: " + e.getMessage();
        }
    }

    private void logIf(String message) {
        if (logConsumer != null) {
            logConsumer.accept(message);
        } else {
            logger.info(message);
        }
    }

    public void stop() {
        this.stopRequested = true;
    }

    /**
     * 检查是否被请求停止，如果是则抛出异常
     */
    private void checkStop() throws IOException {
        if (stopRequested) {
            throw new IOException("用户手动停止了任务");
        }
    }

    public static void main(String[] args) {
        // 1. 检查 API Key
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("错误: 请设置环境变量 DEEPSEEK_API_KEY");
            System.exit(1);
        }

        // 2. 检查是否输入了需求（重点改动）
        if (args.length == 0) {
            System.err.println("⚠️ 请通过命令行参数输入你的需求！");
            System.err.println("用法: java -jar agent.jar \"你的需求描述\"");
            System.err.println("示例: java -jar agent.jar \"写一个计算器HTML\"");
            System.exit(1);
        }

        // 3. 将第一个参数作为用户请求
        String request = args[0];
        System.out.println("📝 收到需求: " + request);

        Main agent = new Main(apiKey);
        try {
            String result = agent.run(request);
            System.out.println("========== Agent 最终回答 ==========");
            System.out.println(result);
        } catch (IOException e) {
            System.err.println("运行 Agent 时发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }


}