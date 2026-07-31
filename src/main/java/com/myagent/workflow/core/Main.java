package com.myagent.workflow.core;

import ch.qos.logback.core.joran.action.AppenderRefAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.myagent.workflow.tools.ToolDefinitions;
import com.myagent.workflow.tools.ToolExecutor;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * @anchor: main_class
 * Agent 工作流编排器 —— 核心职责：接收用户需求，驱动 DeepSeek API 多轮对话，调度工具执行。
 * 工具定义 → ToolDefinitions，工具实现 → ToolExecutor，配置 → AgentConfig。
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    private static final int MAX_MIDDLE_SIZE = 32768;

    private String currentModel;  // ✅ 新增：运行时可变模型指针


    // @anchor: main_fields
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final ToolExecutor toolExecutor;

    private static Consumer<String> logConsumer = null;
    private volatile boolean stopRequested = false;

    private final AgentConfig runConfig;

    private Thread runningThread = null;  // 新增：持有工作线程引用

    private final ContextManager contextManager;


    // @anchor: main_constructor
    public Main(AgentConfig runConfig) {
        this.apiKey = runConfig.apiKey();
        this.runConfig = runConfig;  // 存下来供 run 使用

        this.currentModel = runConfig.model(); // ✅ 默认使用配置的模型

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();

        File sandbox = new File(AgentConfig.getSandboxDir());
        if (!sandbox.exists()) {
            sandbox.mkdirs();
        }


        this.contextManager = new ContextManager(
                4,  // hotSuffixSize
                MAX_MIDDLE_SIZE, // maxMiddleSize (字符数)
                httpClient,
                objectMapper,
                apiKey,
                Main::logIf,   // 传入静态方法引用作为日志消费者
                false
        );

        this.toolExecutor = new ToolExecutor(
                runConfig,
                objectMapper,
                newModel -> {
                    this.currentModel = newModel;
                    logIf("🔄 [工具] Agent 主动切换模型至: " + newModel);
                },
                contextManager);

    }

    // ==================== API Key 校验 ====================

    /**
     * 校验 DeepSeek API Key 是否有效。
     * 调用 /v1/models 接口，如果返回 200 则有效，401 则无效。
     * 网络异常时返回 true（允许启动），避免因网络问题误判。
     */
    public static boolean checkApiKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return false;
        }

        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build();

            Request request = new Request.Builder()
                    .url("https://api.deepseek.com/v1/models")
                    .header("Authorization", "Bearer " + apiKey)
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {
                int code = response.code();
                if (code == 200) {
                    return true;
                } else if (code == 401) {
                    return false;
                } else {
                    // 其他状态码（如 429、500 等）视为 Key 可能有效但服务有问题
                    System.err.println("⚠️ API 返回异常状态码: " + code + "，请稍后重试");
                    return false;
                }
            }
        } catch (java.net.UnknownHostException e) {
            // 网络不通，提示但不阻止启动
            System.err.println("⚠️ 无法连接 DeepSeek API，请检查网络连接");
            return true; // 允许继续启动
        } catch (Exception e) {
            // 其他异常，如超时等，也允许继续（避免误判）
            System.err.println("⚠️ 验证 API Key 时发生异常: " + e.getMessage());
            return true;
        }
    }

    // @anchor: main_setLogConsumer
    public void setLogConsumer(Consumer<String> consumer) {
        this.logConsumer = consumer;
        this.toolExecutor.setLogConsumer(consumer);
    }

    // @anchor: main_run
    /**
     * 运行 Agent 工作流。
     * @param userRequest 用户自然语言需求
     * @return Agent 的最终回答
     */
    public String run(String userRequest, int maxIterations) throws IOException {
        this.runningThread = Thread.currentThread();
        try {
            // 初始化上下文管理器：将系统提示和用户请求放入前缀
            contextManager.init(SystemPrompt.get(), userRequest);

            // 工具定义（委托给 ToolDefinitions）
            List<Map<String, Object>> tools = ToolDefinitions.build();

            for (int iteration = 0; iteration < maxIterations; iteration++) {
                checkStop();

                logIf("--- 第 " + (iteration + 1) + " 次迭代 ---");

                // 构建 API 请求体
                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", currentModel);
                requestBody.put("messages", contextManager.buildMessages());
                requestBody.put("tools", tools);
                requestBody.put("tool_choice", "auto");

                String jsonBody = objectMapper.writeValueAsString(requestBody);

                // 发送 HTTP 请求
                checkStop();
                Request httpRequest = new Request.Builder()
                        .url(AgentConfig.getApiUrl())
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                        .build();

                String responseBody;
                try (Response response = httpClient.newCall(httpRequest).execute()) {
                    if (!response.isSuccessful()) {
                        throw new IOException("API 请求失败: " + response.code() + " " + response.message());
                    }
                    assert response.body() != null;
                    responseBody = response.body().string();
                }

                // 解析响应
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode choices = root.get("choices");
                if (choices == null || choices.isEmpty()) {
                    throw new IOException("API 返回异常: " + responseBody);
                }
                JsonNode messageNode = choices.get(0).get("message");
                Map<String, Object> assistantMsg = objectMapper.convertValue(messageNode, Map.class);

                // 记录模型返回的 content（解释性文字）
                if (messageNode.has("content") && !messageNode.get("content").isNull()) {
                    String content = messageNode.get("content").asText();
                    if (!content.isEmpty()) {
                        logIf("💬 " + content);
                    }
                }

                // 无 tool_calls → Agent 已完成
                if (!messageNode.has("tool_calls") || messageNode.get("tool_calls").size() == 0) {
                    String content = messageNode.has("content") ? messageNode.get("content").asText() : "任务完成";
                    logIf("Agent 完成: " + content);

                    // 记录本次 API 调用统计
                    JsonNode usage = root.get("usage");
                    if (usage != null) {
                        long prompt = usage.get("prompt_tokens").asLong(0);
                        long completion = usage.get("completion_tokens").asLong(0);
                        long cached = 0;
                        if (usage.has("prompt_tokens_details")) {
                            JsonNode details = usage.get("prompt_tokens_details");
                            if (details.has("cached_tokens")) {
                                cached = details.get("cached_tokens").asLong(0);
                            }
                        }
                        contextManager.recordUsage(currentModel, prompt, cached, completion);
                    }

                    // 本轮消息（仅 assistant）追加到后缀
                    List<Map<String, Object>> finalRound = new ArrayList<>();
                    finalRound.add(assistantMsg);
                    contextManager.appendRoundToSuffix(finalRound);
                    // 旋转并检查是否需要压缩（可能无压缩）
                    contextManager.rotateAndCompress();

                    return content;
                }

                // 处理 tool_calls（委托给 ToolExecutor）
                ArrayNode toolCalls = (ArrayNode) messageNode.get("tool_calls");
                // 本轮消息收集：包括 assistant 和所有 tool
                List<Map<String, Object>> currentRound = new ArrayList<>();
                currentRound.add(assistantMsg); // 先加入 assistant

                for (JsonNode tc : toolCalls) {
                    checkStop();
                    String toolCallId = tc.get("id").asText();
                    String functionName = tc.get("function").get("name").asText();
                    String argumentsJson = tc.get("function").get("arguments").asText();

                    // 记录工具调用参数（调试）
                    String outputJson = (functionName.equals("write_file") || functionName.equals("insert_at_anchor"))
                            ? (argumentsJson.substring(0, 30) + "...[已截断，共 " + argumentsJson.length() + " 字符]")
                            : argumentsJson;
                    logIf("🤖 模型决策: 调用工具 [" + functionName + "] 参数: " + outputJson);

                    Map<String, Object> args = objectMapper.readValue(argumentsJson, Map.class);

                    checkStop();

                    String result = toolExecutor.dispatch(functionName, args);

                    if (result == null) {
                        result = "（工具返回 null）";
                        logIf("⚠️ 工具 [" + functionName + "] 返回 null，已替换为占位符");
                    }

                    // 将工具结果添加到对话
                    Map<String, Object> toolMsg = new HashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", toolCallId);
                    toolMsg.put("content", result);
                    currentRound.add(toolMsg); // 加入本轮

                    // 日志展示（对 read_file 结果做截断）
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

                // 记录本轮所有消息到上下文管理器
                contextManager.appendRoundToSuffix(currentRound);

                // 记录本次 API 调用统计（放在 append 之后或之前均可，但需在解析 usage 时完成）
                JsonNode usage = root.get("usage");
                if (usage != null) {
                    long prompt = usage.get("prompt_tokens").asLong(0);
                    long completion = usage.get("completion_tokens").asLong(0);
                    long cached = 0;
                    if (usage.has("prompt_tokens_details")) {
                        JsonNode details = usage.get("prompt_tokens_details");
                        if (details.has("cached_tokens")) {
                            cached = details.get("cached_tokens").asLong(0);
                        }
                    }
                    contextManager.recordUsage(currentModel, prompt, cached, completion);
                }

                // 旋转并触发压缩（如果需要）
                contextManager.rotateAndCompress();
            }

            return "达到最大迭代次数，任务可能未完成。请检查生成的代码。";

        } catch (IOException e) {
            throw e;
        } finally {
            // 输出成本统计
            contextManager.printStats();
            this.runningThread = null;
        }
    }

    // 原有 run(String) 保持兼容，调用新方法
    public String run(String userRequest) throws IOException {
        return run(userRequest, AgentConfig.getDefaultMaxIterations());
    }

    // @anchor: main_stop
    /** 请求停止 Agent */
    public void stop() {
        this.stopRequested = true;
        Thread t = this.runningThread;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();  // 中断工作线程
        }
    }

    // @anchor: main_checkStop
    /** 检查停止标志，若已请求停止则抛出异常 */
    private void checkStop() throws IOException {
        if (stopRequested) {
            throw new IOException("用户手动停止了任务");
        }
    }

    // @anchor: main_logIf
    public static void logIf(String message) {
        if (logConsumer != null) {
            logConsumer.accept(message);
        } else {
            logger.info(message);
        }
    }

    // @anchor: main_entry
    /**
     * 命令行入口（独立运行模式）。
     * 用法: java -jar agent.jar "需求描述"
     */
    public static void main(String[] args) {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("错误: 请设置环境变量 DEEPSEEK_API_KEY");
            System.exit(1);
        }

        if (args.length == 0) {
            System.err.println("⚠️ 请通过命令行参数输入你的需求！");
            System.err.println("用法: java -jar agent.jar \"你的需求描述\"");
            System.err.println("示例: java -jar agent.jar \"写一个计算器HTML\"");
            System.exit(1);
        }

        String request = args[0];
        System.out.println("📝 收到需求: " + request);

        Main agent = new Main(ConfigEditor.buildDefault());
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
