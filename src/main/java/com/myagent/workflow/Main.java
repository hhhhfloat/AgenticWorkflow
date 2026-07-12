package com.myagent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
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

    // @anchor: main_fields
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final ToolExecutor toolExecutor;

    private Consumer<String> logConsumer = null;
    private volatile boolean stopRequested = false;

    // @anchor: main_constructor
    public Main(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();

        // 确保沙箱目录存在
        File sandbox = new File(AgentConfig.SANDBOX_DIR);
        if (!sandbox.exists()) {
            sandbox.mkdirs();
        }

        // 初始化工具执行器
        this.toolExecutor = new ToolExecutor(AgentConfig.SANDBOX_DIR, AgentConfig.ANCHOR_INDEX_FILE, objectMapper);
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
        List<Map<String, Object>> messages = new ArrayList<>();

        // 系统提示
        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", SystemPrompt.get());
        messages.add(systemMsg);

        // 用户请求
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userRequest);
        messages.add(userMsg);

        // 工具定义（委托给 ToolDefinitions）
        List<Map<String, Object>> tools = ToolDefinitions.build();

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            checkStop();
            logIf("--- 第 " + (iteration + 1) + " 次迭代 ---");

            // 构建 API 请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", AgentConfig.MODEL);
            requestBody.put("messages", messages);
            requestBody.put("tools", tools);
            requestBody.put("tool_choice", "auto");

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            // 发送 HTTP 请求
            checkStop();
            Request httpRequest = new Request.Builder()
                    .url(AgentConfig.API_URL)
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

            // ✅ 新增：记录模型返回的 content（解释性文字）
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
                return content;
            }

            // 处理 tool_calls（委托给 ToolExecutor）
            ArrayNode toolCalls = (ArrayNode) messageNode.get("tool_calls");
            for (JsonNode tc : toolCalls) {
                String toolCallId = tc.get("id").asText();
                String functionName = tc.get("function").get("name").asText();
                String argumentsJson = tc.get("function").get("arguments").asText();
                logIf("🤖 模型决策: 调用工具 [" + functionName + "]");

                Map<String, Object> args = objectMapper.readValue(argumentsJson, Map.class);

                checkStop();
                String result = toolExecutor.dispatch(functionName, args);

                // 将工具结果添加到对话
                Map<String, Object> toolMsg = new HashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", toolCallId);
                toolMsg.put("content", result);
                messages.add(toolMsg);

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
        }

        return "达到最大迭代次数，任务可能未完成。请检查生成的代码。";
    }

    // 原有 run(String) 保持兼容，调用新方法
    public String run(String userRequest) throws IOException {
        return run(userRequest, AgentConfig.MAX_ITERATIONS);
    }

    // @anchor: main_stop
    /** 请求停止 Agent */
    public void stop() {
        this.stopRequested = true;
    }

    // @anchor: main_checkStop
    /** 检查停止标志，若已请求停止则抛出异常 */
    private void checkStop() throws IOException {
        if (stopRequested) {
            throw new IOException("用户手动停止了任务");
        }
    }

    // @anchor: main_logIf
    private void logIf(String message) {
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
