// @anchor: main_intro
// Agent 主循环：驱动 DeepSeek 多轮对话、分发工具调用、记录用量并刷新索引
package com.myagent.workflow.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.myagent.workflow.core.config.AgentConfig;
import com.myagent.workflow.core.config.ConfigEditor;
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
// @anchor: main_class
// Agent 编排器：一个实例服务一个 Session，负责多轮 API 调用与工具分发
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
    private volatile Call currentCall = null;

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

    // @anchor: main_constructor
    // 首选构造：绑定 Session 并创建/复用其 ContextManager 与 ToolExecutor
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
                    session::log,
                    session.getSessionId()
            );
            session.attachContextManager(cm);
        }
        this.contextManager = session.getContextManager();

        // ToolExecutor 绑定日志到 Session
        this.toolExecutor = new ToolExecutor(
                runConfig,
                objectMapper,
                session.getWorkProject()
        );
        this.toolExecutor.setLogConsumer(session::log);
    }

    // @anchor: main_constructorWithConfig
    // 兼容构造：内部创建一个独立的临时 Session
    /**
     * 兼容构造：内部创建一个独立的临时 Session。
     * 供 TestRunnerFX 等场景使用。
     */
    public Main(AgentConfig config) {
        this(new Session(config));
    }

    // ==================== API Key 校验 ====================

    // @anchor: main_checkApiKey
    // 调用 /v1/models 校验 API Key 是否有效（网络异常时按可用处理）
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

    // @anchor: main_run
// 执行一次新任务：准备用户消息 → 主循环 → finally 清理
    public String run(String userRequest, int maxIterations) throws IOException {
        return runInternal(true, userRequest, maxIterations);
    }

    // @anchor: main_runResume
    // 恢复执行：跳过 prepareUserMessage，volatileWorking 已从 recovery 恢复
    public String runResume(int maxIterations) throws IOException {
        return runInternal(false, null, maxIterations);
    }
    // @anchor: main_runDefault
    // 便捷重载：使用默认最大迭代次数运行
    public String run(String userRequest) throws IOException {
        return run(userRequest, AgentConfig.getDefaultMaxIterations());
    }

    // @anchor: main_runInternal
    // 统一执行入口：isNew=true 走新任务流程，false 走恢复流程
    private String runInternal(boolean isNew, String userRequest, int maxIterations) throws IOException {
        this.runningThread = Thread.currentThread();

        long startPrompt = 0, startCached = 0, startCompletion = 0;
        int startCalls = 0;
        double startPrice = 0;

        String finalContent = null;
        try {
            session.markRunning(this);
            session.touch();

            try{
                String wp = session.getWorkProject();
                if(wp != null && !wp.isBlank()){
                    toolExecutor.rebuildCurrentProjectIndex(wp);
                } else {
                    toolExecutor.refreshAllProjectIndexes();
                }
            } catch (Exception e){
                logger.warn("任务启动前刷新项目索引失败", e);
            }

            startPrompt = contextManager.getTotalPromptTokens();
            startCached = contextManager.getTotalCachedTokens();
            startCompletion = contextManager.getTotalCompletionTokens();
            startCalls = contextManager.getApiCallCount();
            startPrice = contextManager.getTotalPrice();

            // 新任务才准备用户消息；恢复任务的工作区已由 ResumeHandler 注入
            if (isNew) {
                session.prepareUserMessage(userRequest, SystemPrompt.get(session.getWorkProject()));
            }

            List<Map<String, Object>> tools = ToolDefinitions.build();

            // ===== 主循环 =====
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

                recordUsage(root, iteration);

                if (!messageNode.has("tool_calls") || messageNode.get("tool_calls").size() == 0) {
                    finalContent = messageNode.has("content")
                            ? messageNode.get("content").asText()
                            : "任务完成";
                    List<Map<String, Object>> finalRound = new ArrayList<>();
                    finalRound.add(assistantMsg);
                    contextManager.appendToWorking(finalRound);
                    break;
                }

                ArrayNode toolCalls = (ArrayNode) messageNode.get("tool_calls");
                List<Map<String, Object>> currentRound = new ArrayList<>();
                currentRound.add(assistantMsg);

                // 单轮去重：同一轮响应里 name + arguments 完全相同的 tool_call 只执行一次。
                // 模型异常时可能在单次响应里重复输出相同调用，导致工具被无意义地执行多次。
                // 仍然为每个 tool_call_id 返回 tool message，保持协议兼容。
                Map<String, String> roundCache = new HashMap<>();

                for (JsonNode tc : toolCalls) {
                    checkStop();
                    String toolCallId = tc.get("id").asText();
                    String functionName = tc.get("function").get("name").asText();
                    String argumentsJson = tc.get("function").get("arguments").asText();

                    // 用 \u0000 分隔避免不同 name/args 拼出相同 key
                    String dedupKey = functionName + "\u0000" + argumentsJson;

                    String result;
                    if (roundCache.containsKey(dedupKey)) {
                        result = roundCache.get(dedupKey);
                        session.log("⚠️ 本轮重复工具调用，跳过执行 [" + functionName + "] 参数: "
                                + ToolLogFormatter.formatArgs(argumentsJson));
                    } else {
                        session.log("🤖 调用工具 [" + functionName + "] 参数: "
                                + ToolLogFormatter.formatArgs(argumentsJson));
                        try {
                            Map<String, Object> args = objectMapper.readValue(argumentsJson, Map.class);
                            result = toolExecutor.dispatch(functionName, args);
                            if (result == null) result = "（工具返回 null）";
                        } catch (Exception e) {
                            result = "【工具执行失败】" + e.getClass().getSimpleName()
                                    + ": " + e.getMessage() + "\n请调整参数或换一种方式重试。";
                            session.log("⚠️ 工具 [" + functionName + "] 异常:\n"
                                    + ToolLogFormatter.formatStackTrace(e));
                            logger.error("工具异常: {}", functionName, e);
                        }
                        roundCache.put(dedupKey, result);
                    }

                    Map<String, Object> toolMsg = new HashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", toolCallId);
                    toolMsg.put("content", result);
                    currentRound.add(toolMsg);

                    session.log("工具 [" + functionName + "] 结果: "
                            + ToolLogFormatter.formatResult(functionName, result));
                }
                toolExecutor.flushDirtyAnchors();
                contextManager.appendToWorking(currentRound);
                // 保证末轮工具结果在 recovery 里，crash 后不重跑已完成的工作
                contextManager.writeRecovery();
            }

            if (finalContent == null) {
                finalContent = "达到最大迭代次数，任务可能未完成。";
            }

            return finalContent;

        } finally {
            // ===== best-effort 阶段 =====

            try {
                this.lastTaskUsage = new TaskUsage(
                        contextManager.getTotalPromptTokens() - startPrompt,
                        contextManager.getTotalCachedTokens() - startCached,
                        contextManager.getTotalCompletionTokens() - startCompletion,
                        contextManager.getApiCallCount() - startCalls,
                        contextManager.getTotalPrice() - startPrice
                );
            } catch (Exception e) {
                logger.warn("记录 usage 失败", e);
            }

            try {
                if (finalContent != null) {
                    contextManager.mergeSummaryToBase(buildTaskSummary(finalContent));
                } else {
                    // 异常中断：清空工作区；recovery 由上一轮工具执行后写入，保持不变
                    contextManager.clearVolatileWorking();
                }
            } catch (Exception e) {
                logger.warn("清理任务上下文失败", e);
            }

            try {
                toolExecutor.refreshAllProjectIndexes();
            } catch (Exception e) {
                logger.warn("刷新项目索引失败", e);
            }

            try {
                contextManager.flushRawLog();
                contextManager.compressRawLog();
            } catch (Exception e) {
                logger.warn("落盘原始日志失败", e);
            }

            // 自然完成或用户主动停止都清除 recovery；真实异常保留供后续恢复
            if (finalContent != null || stopRequested) {
                try {
                    contextManager.clearRecovery();
                } catch (Exception e) {
                    logger.warn("清除 recovery 失败", e);
                }
            }

            try {
                contextManager.printStats();
            } catch (Exception e) {
                logger.warn("打印统计失败", e);
            }

            session.markIdle();
            this.runningThread = null;
            // 如果 close() 在 stopTask 超时时注册了"任务结束后移除"回调，现在触发。
            // 正常完成、用户停止、异常中断都会走到这里；有回调则执行 save + remove。
            session.fireCloseIfRequested();
        }
    }

    // @anchor: main_buildTaskSummary
    // 生成兜底任务摘要（Agent 未主动压缩时以其最终回复代替）
    /**
     * 兜底摘要生成：当 Agent 未主动压缩时，用它的最终回复作为摘要。
     * 若未来 Agent 稳定遵守"结束前必须压缩"的约定，此方法调用频率会大幅降低。
     */
    private String buildTaskSummary(String finalContent) {
        if (finalContent == null || finalContent.isBlank()) {
            return "本轮任务结束（无输出）。";
        }
        if(finalContent.length() > 20_000){
            logger.warn("任务摘要异常长（{}字符）", finalContent.length());
        }
        return finalContent;   // ← 原样返回
    }

    // ==================== 传输层 ====================

    // @anchor: main_sendAndReceive
    // 发送请求体到 DeepSeek API 并返回解析后的 JSON
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
        Call call = httpClient.newCall(httpRequest);
        this.currentCall = call;
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                String errBody = response.body() != null ? response.body().string() : "(null)";
                throw new IOException("API 请求失败: " + response.code() + " | " + errBody);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("响应体为空");
            }
            responseBody = body.string();
        } finally{
            this.currentCall = null;
        }

        contextManager.appendRawLog("response", responseBody);
        // 每轮成功后立即落盘：更新 recovery（覆盖写）+ 追加到全量 temp 日志
        contextManager.flushRawLog();
        return objectMapper.readTree(responseBody);
    }

    // ==================== 辅助 ====================

    // @anchor: main_recordUsage
    // 解析响应 usage 字段并累计成本，同时回调迭代监听器
    private void recordUsage(JsonNode root, int iteration) {
        JsonNode usage = root.get("usage");
        if (usage == null) return;

        JsonNode promptNode = usage.get("prompt_tokens");
        long prompt = promptNode != null ? promptNode.asLong(0) : 0;
        contextManager.setLastPromptTokens(prompt);
        JsonNode completionNode = usage.get("completion_tokens");
        long completion = completionNode != null ? completionNode.asLong(0) : 0;
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

    // ==================== 停止 ====================

    // @anchor: main_stop
    // 请求停止任务：置停止标记、取消在途 HTTP、中断运行线程
    public void stop() {
        this.stopRequested = true;
        Call c = this.currentCall;
        if(c != null) {
            try { c.cancel(); } catch (Exception ignored) {}
        }
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

    // @anchor: main_cli
    // 命令行入口：校验 API Key 与参数后按需求描述运行一次任务
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
