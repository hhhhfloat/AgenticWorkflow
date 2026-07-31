package com.myagent.workflow.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

/**
 * 上下文管理器 —— 负责三段式消息存储、轮次滚动、压缩触发与执行
 */
public class ContextManager {

    // 三段结构：每个元素是一个轮次（List<Map>）
    private final List<List<Map<String, Object>>> prefix = new ArrayList<>();
    private final List<List<Map<String, Object>>> toCompress = new ArrayList<>();
    private final List<List<Map<String, Object>>> suffix = new ArrayList<>();

    private final int hotSuffixRounds;   // 热后缀保留轮次数（如 3 轮）
    private final int maxCompressSize;   // 触发压缩的字符数阈值

    // 依赖
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final Consumer<String> logConsumer;

    // 历史记录
    private Path historyFile;
    private final List<Long> historyOffsets = new ArrayList<>();

    // 计费统计
    private long totalPromptTokens = 0;
    private long totalCachedTokens = 0;
    private long totalCompletionTokens = 0;
    private int apiCallCount = 0;
    private double price = 0;

    // 价格常量
    private static final double PRICE_FLASH_IN_HIT = 0.02;
    private static final double PRICE_FLASH_IN_NOT_HIT = 1;
    private static final double PRICE_FLASH_OUT = 2;
    private static final double PRICE_PRO_IN_HIT = 0.025;
    private static final double PRICE_PRO_IN_NOT_HIT = 3;
    private static final double PRICE_PRO_OUT = 6;

    private final boolean compressionEnabled;

    public ContextManager(int hotSuffixRounds, int maxCompressSize,
                          OkHttpClient httpClient, ObjectMapper objectMapper,
                          String apiKey, Consumer<String> logConsumer,
                          boolean compressionEnabled) {
        this.hotSuffixRounds = hotSuffixRounds;
        this.maxCompressSize = maxCompressSize;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.logConsumer = logConsumer;
        this.compressionEnabled = compressionEnabled;
        initHistoryFile();
    }

    // 初始化历史文件
    private void initHistoryFile() {
        try {
            String sessionId = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + "_" +
                    UUID.randomUUID().toString().substring(0, 6);
            Path tempDir = Paths.get("./temp");
            if (!Files.exists(tempDir)) {
                Files.createDirectories(tempDir);
            }
            this.historyFile = tempDir.resolve("history_" + sessionId + ".jsonl");
            Files.writeString(historyFile, "", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("⚠️ 历史文件初始化失败: " + e.getMessage());
        }
    }

    // ===================== 对外 API =====================

    /**
     * 初始化：将系统提示和用户请求作为第一个轮次放入前缀
     */
    public void init(String systemPrompt, String userRequest) {
        List<Map<String, Object>> initialRound = new ArrayList<>();
        initialRound.add(Map.of("role", "system", "content", systemPrompt));
        initialRound.add(Map.of("role", "user", "content", userRequest));
        prefix.add(initialRound);
        // 记录历史
        for (Map<String, Object> msg : initialRound) {
            appendToHistory(msg);
        }
    }

    /**
     * 追加一个完整的轮次到后缀末尾（由主循环调用）
     */
    public void appendRoundToSuffix(List<Map<String, Object>> round) {
        if (round == null || round.isEmpty()) return;
        suffix.add(round);
        for (Map<String, Object> msg : round) {
            appendToHistory(msg);
        }
    }

    /**
     * 旋转 + 压缩判断（每轮迭代结束后调用）
     */
    public void rotateAndCompress() {

        if(!compressionEnabled)return;

        // 1. 如果后缀超过上限，将最旧的轮次移到中间
        while (suffix.size() > hotSuffixRounds) {
            toCompress.add(suffix.remove(0));
        }

        // 2. 计算中间体积，判断是否触发压缩
        int middleSize = estimateMiddleSize();
        if (middleSize >= maxCompressSize) {
            String summary = compressWithFlash();
            if (summary != null && !summary.isEmpty()) {
                List<Map<String, Object>> summaryRound = new ArrayList<>();
                summaryRound.add(Map.of("role", "user", "content", "【累积摘要】" + summary));
                prefix.add(summaryRound);
                appendToHistory(summaryRound.get(0));
                toCompress.clear();
                log("🧹 [系统] 上下文已压缩，中间已清空");
            }
        }
    }

    /**
     * 构建完整的消息列表（供 API 请求使用）
     */
    public List<Map<String, Object>> buildMessages() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (List<Map<String, Object>> round : prefix) result.addAll(round);
        for (List<Map<String, Object>> round : toCompress) result.addAll(round);
        for (List<Map<String, Object>> round : suffix) result.addAll(round);
        return result;
    }

    // ===================== 压缩核心 =====================

    private String compressWithFlash() {
        // 如果没有中间内容，跳过压缩
        if (toCompress == null || toCompress.isEmpty()) {
            return "\n";
        }

        // 展平中间部分用于白名单遍历
        List<Map<String, Object>> flatMiddle = flatten(toCompress);

        // ===== 1. 白名单保护 =====
        List<String> whitelistFiles = Arrays.asList("PROJECT.md", "TODO.md", "UPDATE.md", "README.md");
        StringBuilder whitelistContext = new StringBuilder();
        List<Map<String, Object>> filteredMiddle = new ArrayList<>();
        List<String> allDirectoryResults = new ArrayList<>();

        for (Map<String, Object> msg : flatMiddle) {
            String role = (String) msg.get("role");
            if ("tool".equals(role)) {
                String content = (String) msg.get("content");
                if (content == null) {
                    filteredMiddle.add(msg);
                    continue;
                }

                boolean isWhitelistFile = whitelistFiles.stream().anyMatch(content::contains);
                if (isWhitelistFile) {
                    whitelistContext.append("【保留关键文件】\n").append(content).append("\n\n");
                    continue;
                }

                boolean isListDirectory = content.startsWith("📁 目录") ||
                        content.startsWith("[LIST_DIRECTORY_RESULT]");
                if (isListDirectory) {
                    allDirectoryResults.add(content);
                    continue;
                }
            }
            filteredMiddle.add(msg);
        }

        // 添加目录结果到白名单
        if (!allDirectoryResults.isEmpty()) {
            whitelistContext.append("【目录结构快照（按调用顺序）】\n");
            for (int i = 0; i < allDirectoryResults.size(); i++) {
                whitelistContext.append("--- 第 ").append(i + 1).append(" 次目录列举 ---\n");
                whitelistContext.append(allDirectoryResults.get(i)).append("\n");
            }
            whitelistContext.append("\n");
        }

        String whitelistPrefix = !whitelistContext.isEmpty() ?
                "【以下为系统保留的目录结构和关键文件内容，请勿忽略】\n" +
                        whitelistContext.toString() + "\n" : "";

        if (filteredMiddle.isEmpty()) {
            return whitelistPrefix + "（无其他历史操作）";
        }

        // ===== 2. 构建压缩提示词 =====
        String sysPrompt = """
        你是一个**中间历史叙事者**。你的唯一职责是：将【中间部分】的历史操作压缩为一段**流畅的叙事性摘要**，让【上文】和【下文】能够自然衔接。

        【核心原则】
        1. **只压缩中间部分**：你收到的是完整的【上文】+【中间】+【下文】，但你的摘要必须**仅基于【中间】的内容生成**。上文和下文只用于帮助你理解“从哪来、到哪去”，但不要将其内容写入摘要。
        2. **叙事而非报告**：你的输出应该是一段连续的叙述文字（不是要点列表），用“首先……接着……随后……”等过渡词串联，让 Agent 读起来像在读一段连贯的故事。
        3. **过渡性质**：摘要的开头要能承接上文（如“在确认项目结构后，…… ”），结尾要能自然引向下文（如“至此，代码已就绪，可以开始验证”）。
        4. **只写中间发生了什么**：不需要重复上文已有的内容（如用户需求、系统提示），也不需要预判下文要做什么（如下一步验证）。只描述中间部分实际执行的操作。

        【提取内容（针对中间部分）】
        1. 执行了哪些工具调用（list_directory / read_file / write_file / compile_and_run / …）
        2. 这些工具调用的关键结果（文件创建成功、编译通过、发现了什么问题）
        3. 任何对后续有影响的状态变更（如“TODO.md 已更新”、“锚点索引已重建”）

        【严格禁止】
        1. ❌ 不要输出任何“当前任务状态”或“待办事项”列表——那不是叙事的一部分。
        2. ❌ 不要输出任何代码细节（类名、方法签名、CSS 变量名）——那不是叙事的一部分。
        3. ❌ 不要重复上文（系统提示、用户请求）的内容。
        4. ❌ 不要预判下文（下一步应该做什么）的内容。
        5. ❌ 不要使用“✅”、“⏳”、“**”等标记——那是报告格式，不是叙事。

        【格式要求】
        - 输出 800~1000 字的中文叙事段落（不是要点，不是列表）。
        - 使用自然的过渡词（“于是”、“接下来”、“然后”、“随后”等）。
        - 开头：承接上文的一句话（如“在确认项目目录后，助手开始创建核心文件”）。
        - 结尾：自然引向下文的一句话（如“至此，所有源文件已准备就绪”）。

        【示例输出】
        在确认项目目录后，助手依次创建了三个核心源文件。首先创建了 index.html，定义了计算器的完整 DOM 结构，包含显示屏、按钮面板和主题切换按钮。随后创建了 style.css，实现了一套通过 CSS 变量驱动的双主题系统，覆盖了浅色和深色模式的全部颜色变量和按钮样式。最后创建了 script.js，实现了完整的计算器逻辑，包括状态管理、数字输入、运算符处理和键盘支持。三个文件均成功写入磁盘，TODO.md 中对应的步骤已标记为完成。
        """;

        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(Map.of("role", "system", "content", sysPrompt));

        int originLength = 0;

        try {
            // 构建上下文：将所有三段展平后序列化
            String contextInfo = "【上文（最近历史开始前）】\n" +
                    serializeNested(prefix) +
                    "\n【中间待压缩部分】\n" +
                    serializeMessages(filteredMiddle) +
                    "\n【下文（最近历史末尾）】\n" +
                    serializeNested(suffix);

            originLength = contextInfo.length();
            msgs.add(Map.of("role", "user", "content", "请压缩以下历史记录：\n" + contextInfo));

        } catch (Exception e) {
            return whitelistPrefix + "（历史记录序列化失败，跳过压缩）";
        }

        // ===== 3. 构建请求体 =====
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "deepseek-v4-flash");
        requestBody.put("messages", msgs);
        requestBody.put("max_tokens", 2048);
        requestBody.put("temperature", 0.3);

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            return whitelistPrefix + "（压缩请求构建失败）";
        }

        // ===== 4. 发送请求 =====
        Request httpRequest = new Request.Builder()
                .url(AgentConfig.getApiUrl())
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                System.err.println("⚠️ 压缩 API 请求失败: " + response.code() + " " + response.message());
                return whitelistPrefix + "（压缩服务暂时不可用，已跳过）";
            }

            String responseBody = response.body().string();
            JsonNode root = objectMapper.readTree(responseBody);

            // 解析 usage
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
                this.totalPromptTokens += prompt;
                this.totalCachedTokens += cached;
                this.totalCompletionTokens += completion;
                this.apiCallCount++;
                this.price += calculateCost("deepseek-v4-flash", prompt, cached, completion);
            }

            // 解析摘要
            JsonNode choices = root.get("choices");
            if (choices == null || choices.size() == 0) {
                return whitelistPrefix + "（压缩响应格式异常）";
            }
            JsonNode messageNode = choices.get(0).get("message");
            if (messageNode == null || !messageNode.has("content") || messageNode.get("content").isNull()) {
                return whitelistPrefix + "（压缩响应缺少内容）";
            }
            String summary = messageNode.get("content").asText().trim();

            if (summary.length() > 4000) {
                summary = summary.substring(0, 4000) + "...（已截断）";
            }

            log("LENGTH : " + originLength + " --> " + summary.length() + "\nCONTENT : \n" + summary);

            return whitelistPrefix + "\n【压缩摘要】\n" + summary;

        } catch (Exception e) {
            System.err.println("⚠️ 压缩过程异常: " + e.getMessage());
            return whitelistPrefix + "（压缩过程中发生异常，已跳过）";
        }
    }

    // ===================== 辅助方法 =====================

    private int estimateMiddleSize() {
        // 估算中间所有轮次的序列化长度
        int total = 0;
        for (List<Map<String, Object>> round : toCompress) {
            total += serializeMessages(round).length();
        }
        return total;
    }

    private String serializeMessages(List<Map<String, Object>> msgs) {
        try {
            return objectMapper.writeValueAsString(msgs);
        } catch (Exception e) {
            return "（序列化失败）";
        }
    }

    private String serializeNested(List<List<Map<String, Object>>> nested) {
        if (nested == null || nested.isEmpty()) return "（空）";
        StringBuilder sb = new StringBuilder();
        for (List<Map<String, Object>> round : nested) {
            sb.append(serializeMessages(round)).append("\n");
        }
        return sb.toString();
    }

    private List<Map<String, Object>> flatten(List<List<Map<String, Object>>> nested) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (List<Map<String, Object>> round : nested) {
            result.addAll(round);
        }
        return result;
    }

    private double calculateCost(String model, long promptTokens, long cachedTokens, long completionTokens) {
        boolean isPro = "deepseek-v4-pro".equals(model);
        double inHit = isPro ? PRICE_PRO_IN_HIT : PRICE_FLASH_IN_HIT;
        double inNotHit = isPro ? PRICE_PRO_IN_NOT_HIT : PRICE_FLASH_IN_NOT_HIT;
        double out = isPro ? PRICE_PRO_OUT : PRICE_FLASH_OUT;
        long uncached = promptTokens - cachedTokens;
        return (uncached / 1_000_000.0 * inNotHit) +
                (cachedTokens / 1_000_000.0 * inHit) +
                (completionTokens / 1_000_000.0 * out);
    }

    private void appendToHistory(Map<String, Object> msg) {
        if (historyFile == null) return;
        try {
            long offset = Files.size(historyFile);
            String line = objectMapper.writeValueAsString(msg) + System.lineSeparator();
            Files.writeString(historyFile, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            historyOffsets.add(offset);
        } catch (IOException e) {
            System.err.println("⚠️ 历史记录写入失败: " + e.getMessage());
        }
    }

    // ===================== 对外统计 =====================

    public void recordUsage(String model, long promptTokens, long cachedTokens, long completionTokens) {
        this.totalPromptTokens += promptTokens;
        this.totalCachedTokens += cachedTokens;
        this.totalCompletionTokens += completionTokens;
        this.apiCallCount++;
        this.price += calculateCost(model, promptTokens, cachedTokens, completionTokens);
    }

    public void printStats() {
        log("📊 ========== 成本统计 ==========\n"+
                "\n📨 API 调用次数: " + apiCallCount +
                "\n📥 总输入 Token: " + totalPromptTokens +
                "\n   ├─ 缓存命中: " + totalCachedTokens +
                "\n   └─ 缓存未命中: " + (totalPromptTokens - totalCachedTokens) +
                "\n♾️总缓存命中率："+ totalCachedTokens*100L/totalPromptTokens + "%" +
                "\n📤 总输出 Token: " + totalCompletionTokens +
                "\n💵 总成本: ¥" + String.format("%.6f", price) +
                ((apiCallCount > 0)?("📊 平均每次成本: ¥" + String.format("%.6f", price / apiCallCount)):"")
                +"\n\n=================================="
        );
    }

    public Path getHistoryFile() { return historyFile; }
    public List<Long> getHistoryOffsets() { return historyOffsets; }

    // ===================== 日志 =====================

    private void log(String message) {
        if (logConsumer != null) {
            logConsumer.accept(message);
        } else {
            System.out.println(message);
        }
    }
}