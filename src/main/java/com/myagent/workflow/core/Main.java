package com.myagent.workflow.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.myagent.workflow.session.Session;
import com.myagent.workflow.tools.ToolDefinitions;
import com.myagent.workflow.tools.ToolExecutor;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Agent 工作流编排器。
 * <p>
 * v5.0 重构要点：
 * - 绑定 Session：Main 的生命周期从属于一个 Session，不再是独立的一次性执行器
 * - 日志实例化：logConsumer 从静态改为通过 Session 转发
 * - 传输层通用化：sendAndReceive 只接收组装好的 requestBody
 * - 多轮对话：首轮 init()，后续 appendUserMessage()；任务结束 mergeSummaryToBase()
 * - 去冗余：移除 outputCutTools、iterationListenerIsNull、未使用的静态 logIf
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    // ===== 绑定的会话 =====
    private final Session session;
    private final AgentConfig runConfig;

    // ===== 依赖 =====
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final ToolExecutor toolExecutor;
    private final ContextManager contextManager;

    // ===== 实例级运行时状态 =====
    private volatile boolean stopRequested = false;
    private Thread runningThread = null;
    private String currentModel;

    // ===== 迭代监听器（供测试程序实时追踪） =====
    public interface IterationListener {
        void onIteration(int iteration, long promptTokens, long cachedTokens,
                         long completionTokens, double cost);
    }

    public record TaskUsage(long promptTokens, long cachedTokens, long completionTokens,
                            int apiCalls, double cost) {}

    private volatile TaskUsage lastTaskUsage = null;
    public TaskUsage getLastTaskUsage() { return lastTaskUsage; }

    private IterationListener iterationListener = null;

    // ==================== 构造 ====================

    /**
     * 首选构造：绑定到 Session。
     * <p>
     * 若 Session 尚无 ContextManager，此构造会创建并注入。
     */
    public Main(Session session) {
        this.session = session;
        this.runConfig = session.getConfig();
        this.apiKey = runConfig.apiKey();
        this.currentModel = runConfig.model();

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

        // 确保 Session 持有 ContextManager（延迟注入）
        if (session.getContextManager() == null) {
            ContextManager cm = new ContextManager(
                    objectMapper,
                    session::log
            );
            session.attachContextManager(cm);
        }
        this.contextManager = session.getContextManager();

        // ToolExecutor 绑定日志到 Session
        this.toolExecutor = new ToolExecutor(
                runConfig,
                objectMapper
        );
        this.toolExecutor.setLogConsumer(session::log);
    }

    /**
     * 兼容构造：内部创建一个独立的临时 Session。
     * 供 TestRunnerFX 等场景使用。
     */
    public Main(AgentConfig config) {
        this(new Session(config));
    }

    // ==================== API Key 校验 ====================

    /**
     * 校验 DeepSeek API Key 是否有效。
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
                if (code == 200) return true;
                if (code == 401) return false;
                System.err.println("⚠️ API 返回异常状态码: " + code + "，请稍后重试");
                return false;
            }
        } catch (java.net.UnknownHostException e) {
            System.err.println("⚠️ 无法连接 DeepSeek API，请检查网络连接");
            return true;
        } catch (Exception e) {
            System.err.println("⚠️ 验证 API Key 时发生异常: " + e.getMessage());
            return true;
        }
    }

    // ==================== 运行入口 ====================

    /**
     * 在绑定的 Session 中运行一次任务。
     * <p>
     * 若 Session 上下文为空，视为首轮（init）；
     * 否则视为多轮对话（appendUserMessage）。
     * 任务结束后，摘要合并到 immutableBase，工作区清空。
     */
    public String run(String userRequest, int maxIterations) throws IOException {
        this.runningThread = Thread.currentThread();
        session.markRunning(this);
        session.touch();

        long startPrompt = contextManager.getTotalPromptTokens();
        long startCached = contextManager.getTotalCachedTokens();
        long startCompletion = contextManager.getTotalCompletionTokens();
        int startCalls = contextManager.getApiCallCount();
        double startPrice = contextManager.getTotalPrice();

        String finalContent = null;
        try {
            // 1. 准备用户消息（内部处理首轮/后续分支，并同步 meta）
            session.prepareUserMessage(userRequest, SystemPrompt.get());

            // 2. 工具定义
            List<Map<String, Object>> tools = ToolDefinitions.build();

            // 3. 主循环
            for (int iteration = 0; iteration < maxIterations; iteration++) {
                checkStop();
                session.log("--- 第 " + (iteration + 1) + " 次迭代 ---");

                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", currentModel);
                requestBody.put("messages", contextManager.buildMessages());
                requestBody.put("tools", tools);
                requestBody.put("tool_choice", "auto");

                JsonNode root = sendAndReceive(requestBody);

                JsonNode choices = root.get("choices");
                if (choices == null || choices.isEmpty()) {
                    throw new IOException("API 返回异常");
                }
                JsonNode messageNode = choices.get(0).get("message");
                Map<String, Object> assistantMsg = objectMapper.convertValue(messageNode, Map.class);

                // 记录 usage
                recordUsage(root, iteration);

                // 无 tool_calls → 任务完成
                if (!messageNode.has("tool_calls") || messageNode.get("tool_calls").size() == 0) {
                    finalContent = messageNode.has("content")
                            ? messageNode.get("content").asText()
                            : "任务完成";
                    List<Map<String, Object>> finalRound = new ArrayList<>();
                    finalRound.add(assistantMsg);
                    contextManager.appendToWorking(finalRound);
                    break;
                }

                // 处理 tool_calls
                ArrayNode toolCalls = (ArrayNode) messageNode.get("tool_calls");
                List<Map<String, Object>> currentRound = new ArrayList<>();
                currentRound.add(assistantMsg);

                for (JsonNode tc : toolCalls) {
                    checkStop();
                    String toolCallId = tc.get("id").asText();
                    String functionName = tc.get("function").get("name").asText();
                    String argumentsJson = tc.get("function").get("arguments").asText();

                    String outputJson = (argumentsJson.length() > 100)
                            ? argumentsJson.substring(0, 100) + "...[共 " + argumentsJson.length() + " 字符]"
                            : argumentsJson;
                    session.log("🤖 调用工具 [" + functionName + "] 参数: " + outputJson);

                    Map<String, Object> args = objectMapper.readValue(argumentsJson, Map.class);
                    checkStop();
                    String result = toolExecutor.dispatch(functionName, args);
                    if (result == null) {
                        result = "（工具返回 null）";
                    }

                    Map<String, Object> toolMsg = new HashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", toolCallId);
                    toolMsg.put("content", result);
                    currentRound.add(toolMsg);

                    session.log("工具 [" + functionName + "] 结果: " + getDisplayResult(functionName, result));
                }

                contextManager.appendToWorking(currentRound);
            }

            if (finalContent == null) {
                finalContent = "达到最大迭代次数，任务可能未完成。";
            }

            return finalContent;

        } finally {
            this.lastTaskUsage = new TaskUsage(
                    contextManager.getTotalPromptTokens() - startPrompt,
                    contextManager.getTotalCachedTokens() - startCached,
                    contextManager.getTotalCompletionTokens() - startCompletion,
                    contextManager.getApiCallCount() - startCalls,
                    contextManager.getTotalPrice() - startPrice
            );
            if (finalContent != null) {
                contextManager.mergeSummaryToBase(buildTaskSummary(finalContent));
            }
            contextManager.flushRawLog();
            contextManager.printStats();
            session.markIdle();
            this.runningThread = null;
        }
    }

    public String run(String userRequest) throws IOException {
        return run(userRequest, AgentConfig.getDefaultMaxIterations());
    }

    /**
     * 兜底摘要生成：当 Agent 未主动压缩时，用它的最终回复作为摘要。
     * 若未来 Agent 稳定遵守"结束前必须压缩"的约定，此方法调用频率会大幅降低。
     */
    private String buildTaskSummary(String finalContent) {
        if (finalContent == null || finalContent.isBlank()) {
            return "本轮任务结束（无输出）。";
        }
        return finalContent;   // ← 原样返回
    }

    // ==================== 传输层 ====================

    /**
     * 通用传输层：发送 requestBody，返回解析后的 JsonNode。
     * <p>
     * 只负责 HTTP 传输与 JSON 解析，不关心 body 内容。
     */
    public JsonNode sendAndReceive(Map<String, Object> requestBody) throws IOException {
        String jsonBody = objectMapper.writeValueAsString(requestBody);
        contextManager.appendRawLog("request", jsonBody);

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
                String errBody = response.body() != null ? response.body().string() : "(null)";
                throw new IOException("API 请求失败: " + response.code() + " | " + errBody);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("响应体为空");
            }
            responseBody = body.string();
        }

        contextManager.appendRawLog("response", responseBody);
        return objectMapper.readTree(responseBody);
    }

    // ==================== 辅助 ====================

    private void recordUsage(JsonNode root, int iteration) {
        JsonNode usage = root.get("usage");
        if (usage == null) return;

        long prompt = usage.get("prompt_tokens").asLong(0);
        long completion = usage.get("completion_tokens").asLong(0);
        long cached = 0;
        if (usage.has("prompt_tokens_details")) {
            JsonNode details = usage.get("prompt_tokens_details");
            if (details.has("cached_tokens")) {
                cached = details.get("cached_tokens").asLong(0);
            }
        }
        double cost = contextManager.recordUsage(currentModel, prompt, cached, completion);

        if (iterationListener != null) {
            iterationListener.onIteration(iteration + 1, prompt, cached, completion, cost);
        }
    }

    private static String getDisplayResult(String functionName, String result) {
        if ("read_file".equals(functionName) && result.length() > 300) {
            return result.substring(0, 200) + "... [共 " + result.length() + " 字符]";
        }
        return result;
    }

    // ==================== 停止 ====================

    public void stop() {
        this.stopRequested = true;
        Thread t = this.runningThread;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
        }
    }

    private void checkStop() throws IOException {
        if (stopRequested) {
            throw new IOException("用户手动停止了任务");
        }
    }

    // ==================== Setter / Getter ====================

    public void setIterationListener(IterationListener listener) {
        this.iterationListener = listener;
    }

    public Session getSession() {
        return session;
    }

    // ==================== CLI 入口 ====================

    public static void main(String[] args) {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("错误: 请设置环境变量 DEEPSEEK_API_KEY");
            System.exit(1);
        }

        if (args.length == 0) {
            System.err.println("用法: java -jar agent.jar \"你的需求描述\"");
            System.exit(1);
        }

        Main agent = new Main(ConfigEditor.buildDefault());
        try {
            agent.run(args[0]);
        } catch (IOException e) {
            System.err.println("运行 Agent 时发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
}