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
 * 上下文管理器 —— 双区存储模型：
 * - immutableBase：不可变基础区（SystemPrompt + 原始User + 目录树 + 累积摘要）
 * - volatileWorking：易失工作区（仅存最近 1~2 轮，每次 checkpoint 后清空）
 * <p>
 * v4.1 精细化增强：
 * - 双重裁剪策略（消息条数上限 + 字符数软上限）
 * - 详细的调试日志
 * - 提供工作区状态查询
 */
public class ContextManager {

    // ===== 核心存储 =====
    private final List<Map<String, Object>> immutableBase = new ArrayList<>();
    private final List<Map<String, Object>> volatileWorking = new ArrayList<>();
    // ===== 文件变化日志缓冲（待合并到 immutableBase） =====
    private final Set<String> pendingDocChanges = new HashSet<>();
    // ===== 🚀 新增：待压缩的 PROJECT.md（单独标记） =====
    private boolean projectMdPending = false;


    // ===== 裁剪阈值 =====
    // 硬上限：工作区最多保留 20 条消息（约 2-3 轮完整交互）
    private static final int MAX_WORKING_MESSAGES = 20;
    // 软上限：工作区序列化后超过 8000 字符触发裁剪（防止单次请求体过大）
    private static final int MAX_WORKING_CHARS = 300000;

    // ===== 压缩依赖 =====
    private final OkHttpClient httpClient;
    private final String apiKey;
    private final ObjectMapper objectMapper; // 已有，但用于序列化
    private final Consumer<String> logConsumer;


    // ===== 压缩状态追踪 =====
    private int roundsSinceLastCheckpoint = 0;
    private final int MIN_INTERVAL = AgentConfig.getCheckpointMinInterval();
    private final int MAX_INTERVAL = AgentConfig.getCheckpointMaxInterval();

    // ===== 历史记录 =====
    private Path historyFile;
    private final List<Long> historyOffsets = new ArrayList<>();

    // ===== 待应用的检查点摘要 =====
    private String pendingSummary = null;

    private final Compressor compressor;

    // ===== 计费统计 =====
    private long totalPromptTokens = 0;
    private long totalCachedTokens = 0;
    private long totalCompletionTokens = 0;
    private int apiCallCount = 0;
    private double price = 0;

    // ===== 价格常量 =====
    private static final double PRICE_FLASH_IN_HIT = 0.02;
    private static final double PRICE_FLASH_IN_NOT_HIT = 1;
    private static final double PRICE_FLASH_OUT = 2;
    private static final double PRICE_PRO_IN_HIT = 0.025;
    private static final double PRICE_PRO_IN_NOT_HIT = 3;
    private static final double PRICE_PRO_OUT = 6;

    public ContextManager(OkHttpClient httpClient, ObjectMapper objectMapper,
                          String apiKey, Consumer<String> logConsumer) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.logConsumer = logConsumer;
        this.compressor = new Compressor(httpClient, objectMapper, apiKey);  // ← 新增
        initHistoryFile();
    }



    // ===== 初始化历史文件 =====
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

    public void init(String systemPrompt, String userRequest) {
        immutableBase.add(Map.of("role", "system", "content", systemPrompt));
        immutableBase.add(Map.of("role", "user", "content", userRequest));
        for (Map<String, Object> msg : immutableBase) {
            appendToHistory(msg);
        }
        log("📌 [系统] 上下文初始化完成，基础区大小: " + immutableBase.size() + " 条消息");
    }

    /**
     * 追加一个完整的轮次到工作区（易失区）
     */
    public void appendToWorking(List<Map<String, Object>> round) {
        if (round == null || round.isEmpty()) return;

        // 1. 追加本轮消息到工作区
        volatileWorking.addAll(round);
        for (Map<String, Object> msg : round) {
            appendToHistory(msg);
        }
        roundsSinceLastCheckpoint++;

        // ✅ 如果有待应用的摘要，追加到 immutableBase 作为 system 消息
        if (pendingSummary != null && !pendingSummary.isBlank()) {
            String summary = pendingSummary;
            pendingSummary = null;

            // 🔥 关键：将摘要作为 system 消息追加，不参与交替规则
            Map<String, Object> summaryMsg = Map.of(
                    "role", "system",
                    "content", "【压缩摘要】\n" + summary
            );
            immutableBase.add(summaryMsg);
            appendToHistory(summaryMsg);

            // 清空工作区
            int workingSizeBefore = volatileWorking.size();
            volatileWorking.clear();
            roundsSinceLastCheckpoint = 0;

            log("📌 [系统] 压缩摘要已追加到基础区（system 角色），工作区已清空（原有 " + workingSizeBefore + " 条消息）");
            log("📊 [系统] 当前基础区消息数: " + immutableBase.size() + "，工作区消息数: 0");
        }

        // 双重裁剪（保持注释状态）
        // trimWorkingIfNeeded();
    }


    public int getRoundsSinceLastCheckpoint() {
        return roundsSinceLastCheckpoint;
    }


    /**
     * 构建完整的消息列表（供 API 请求使用）
     */
    public List<Map<String, Object>> buildMessages() {
        List<Map<String, Object>> result = new ArrayList<>(immutableBase);
        result.addAll(volatileWorking);
        return result;
    }

    // --- 具体压缩部分 ---
    /**
     * 处理检查点请求（由 ToolExecutor 调用）。
     * 返回 "__CHECKPOINT_TRIGGERED__" 表示压缩成功，Main 应清空工作区并继续。
     * 返回普通字符串表示压缩被拒绝（过早），Agent 需要继续工作。
     */
    public String requestCheckpoint(String phaseSummary, String nextPlan) {
        // 阈值检查（不变）
        if (roundsSinceLastCheckpoint < MIN_INTERVAL) {
            String msg = "⚠️ 距上次压缩仅过了 " + roundsSinceLastCheckpoint + " 轮...";
            log(msg);
            return msg;
        }

        if (roundsSinceLastCheckpoint > MAX_INTERVAL) {
            log("⚠️ 警告：已超过最大间隔 " + MAX_INTERVAL + " 轮（当前 " +
                    roundsSinceLastCheckpoint + " 轮），本次压缩为超期压缩");
        }

        log("📌 [系统] 开始压缩流程（距上次压缩已过 " + roundsSinceLastCheckpoint + " 轮）");

        try {
            // 1. 处理 PROJECT.md 压缩
            String compressedProjectMd = null;
            if (projectMdPending) {
                Path projectMdPath = Paths.get(AgentConfig.getSandboxDir())
                        .resolve("PROJECT.md").normalize();
                if (Files.exists(projectMdPath)) {
                    String rawContent = Files.readString(projectMdPath, StandardCharsets.UTF_8);
                    compressedProjectMd = compressor.compressProjectMd(rawContent);
                    log("📌 [系统] PROJECT.md 压缩完成，长度: " + compressedProjectMd.length());
                }
                projectMdPending = false;
            }

            // ✅ 新增：刷新普通文档变更（TODO.md、README.md 等），获取文档摘要
            String docsSummary = flushPendingChanges();

            // 2. 获取当前工作区的序列化内容
            String workingContent = null;
            if (!volatileWorking.isEmpty()) {
                workingContent = objectMapper.writeValueAsString(volatileWorking);
            }

            // 3. 🔥 通过 Compressor 压缩上下文（现在包含文档变更摘要）
            String structuredSummary = compressor.compressContext(
                    phaseSummary,
                    nextPlan,
                    workingContent,
                    compressedProjectMd,
                    docsSummary  // ← 新增参数
            );

            if (structuredSummary == null || structuredSummary.isBlank()) {
                log("⚠️ [系统] 上下文压缩失败");
                return "压缩失败，请稍后重试";
            }

            // 🔥 存储摘要（纯文本）到 pendingSummary
            this.pendingSummary = structuredSummary;
            log("📌 [系统] 压缩摘要已生成，将在本轮消息保存后应用");

            return "✅ 检查点已准备，将在本轮结束后自动应用压缩。";

        } catch (Exception e) {
            log("❌ [系统] 压缩流程异常: " + e.getMessage());
            e.printStackTrace();
            return "压缩过程发生异常: " + e.getMessage();
        }
    }

    // ===================== 辅助方法 =====================

    /**
     * 获取当前工作区的大小（消息条数）
     */
    public int getWorkingMessageCount() {
        return volatileWorking.size();
    }

    /**
     * 获取当前工作区的序列化字符数（用于调试）
     */
    public int getCurrentWorkingSize() {
        try {
            return objectMapper.writeValueAsString(volatileWorking).length();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 获取基础区的消息数
     */
    public int getBaseMessageCount() {
        return immutableBase.size();
    }

    public boolean isProjectMdPending() {
        return projectMdPending;
    }

    /**
     * 双重裁剪策略：
     * 1. 若消息条数超过 MAX_WORKING_MESSAGES，移除最早的消息至上限。
     * 2. 若序列化字符数超过 MAX_WORKING_CHARS，进一步裁剪至字符数以下（保留最新消息）。
     */
    private void trimWorkingIfNeeded() {
        // ----- 策略1：按消息条数硬裁剪 -----
        if (volatileWorking.size() > MAX_WORKING_MESSAGES) {
            int toRemove = volatileWorking.size() - MAX_WORKING_MESSAGES;
            volatileWorking.subList(0, toRemove).clear();
            log("✂️ [系统] 工作区超限（> " + MAX_WORKING_MESSAGES + " 条消息），已移除最早 " + toRemove + " 条消息");
        }

        // ----- 策略2：按字符数软裁剪（保留最新消息，逐条移除最旧的直到达标） -----
        try {
            int currentSize = objectMapper.writeValueAsString(volatileWorking).length();
            int removeCount = 0;
            while (currentSize > MAX_WORKING_CHARS && volatileWorking.size() > 1) {
                // 移除最旧的一条消息
                volatileWorking.remove(0);
                removeCount++;
                currentSize = objectMapper.writeValueAsString(volatileWorking).length();
            }
            if (removeCount > 0) {
                log("✂️ [系统] 工作区字符数超限（> " + MAX_WORKING_CHARS + " 字符），已移除最早 " + removeCount + " 条消息，当前大小: " + currentSize + " 字符");
            }
        } catch (Exception e) {
            // 序列化失败则跳过字符数裁剪
        }
    }


    // ===================== 历史记录 =====================

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

    public Path getHistoryFile() { return historyFile; }
    public List<Long> getHistoryOffsets() { return historyOffsets; }

    // ===================== 计费统计 =====================

    public void recordUsage(String model, long promptTokens, long cachedTokens, long completionTokens) {
        this.totalPromptTokens += promptTokens;
        this.totalCachedTokens += cachedTokens;
        this.totalCompletionTokens += completionTokens;
        this.apiCallCount++;
        this.price += calculateCost(model, promptTokens, cachedTokens, completionTokens);
    }

    public void printStats() {
        long uncached = totalPromptTokens - totalCachedTokens;
        double hitRate = totalPromptTokens == 0 ? 0 : (double) totalCachedTokens / totalPromptTokens * 100;
        log("📊 ========== 成本统计 ==========\n" +
                "\n📨 API 调用次数: " + apiCallCount +
                "\n📥 总输入 Token: " + totalPromptTokens +
                "\n   ├─ 缓存命中: " + totalCachedTokens +
                "\n   └─ 缓存未命中: " + uncached +
                "\n♾️ 总缓存命中率: " + String.format("%.2f", hitRate) + "%" +
                "\n📤 总输出 Token: " + totalCompletionTokens +
                "\n💵 总成本: ¥" + String.format("%.6f", price) +
                (apiCallCount > 0 ? "\n📊 平均每次成本: ¥" + String.format("%.6f", price / apiCallCount) : "") +
                "\n\n=================================="
        );
    }

    private double calculateCost(String model, long promptTokens, long cachedTokens, long completionTokens) {
        boolean isPro = AgentConfig.getModelPro().equals(model);
        double inHit = isPro ? PRICE_PRO_IN_HIT : PRICE_FLASH_IN_HIT;
        double inNotHit = isPro ? PRICE_PRO_IN_NOT_HIT : PRICE_FLASH_IN_NOT_HIT;
        double out = isPro ? PRICE_PRO_OUT : PRICE_FLASH_OUT;
        long uncached = promptTokens - cachedTokens;
        return (uncached / 1_000_000.0 * inNotHit) +
                (cachedTokens / 1_000_000.0 * inHit) +
                (completionTokens / 1_000_000.0 * out);
    }

    /**
     * 记录一个关键文档发生了变更（写入缓冲区，等待合并）。
     * 在 write_file 检测到 PROJECT.md / TODO.md / README.md 时调用。
     * 使用 Set 去重，避免重复记录同一文件。
     */
    public void addPendingDocChange(String filename) {
        if (filename == null || filename.isBlank()) return;
        // 如果是 PROJECT.md，单独标记
        if (filename.equalsIgnoreCase("PROJECT.md")) {
            projectMdPending = true;
            log("📝 [系统] 标记 PROJECT.md 待压缩（将在下次检查点处理）");
            return;
        }
        pendingDocChanges.add(filename);
        log("📝 [系统] 记录待合并文档变更: " + filename + "（缓冲区当前 " + pendingDocChanges.size() + " 个文件待合并）");
    }

    /**
     * 将缓冲区中的所有待合并文档变更，读取最新内容后合并成一条日志，
     * 追加到 immutableBase 末尾，然后清空缓冲区。
     * 返回一个文本摘要，供压缩器使用。
     */
    public String flushPendingChanges() {
        StringBuilder summary = new StringBuilder();

        // 1. 处理 PROJECT.md 的占位日志（如果有）
        if (projectMdPending) {
            Map<String, Object> placeholderMsg = Map.of(
                    "role", "system",
                    "content", "【PROJECT.md 已更新】\n（将在下次压缩时处理）"
            );
            immutableBase.add(placeholderMsg);
            appendToHistory(placeholderMsg);
            log("📌 [系统] PROJECT.md 变更占位已写入基础区（system 角色）");
            projectMdPending = false;
            summary.append("PROJECT.md 已更新。");
        }

        // 2. 处理普通文档（TODO.md、README.md 等）
        if (pendingDocChanges.isEmpty()) {
            return summary.toString();  // 没有普通文档变更
        }

        summary.append("本次周期内关键文档变动（非 PROJECT.md）：\n");
        Path sandboxRoot = Paths.get(AgentConfig.getSandboxDir()).toAbsolutePath().normalize();
        int successCount = 0;

        StringBuilder logBuilder = new StringBuilder();
        logBuilder.append("【本次周期内关键文档变动（非 PROJECT.md）】\n");

        for (String filename : pendingDocChanges) {
            try {
                Path filePath = sandboxRoot.resolve(filename).normalize();
                if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                    logBuilder.append("- ").append(filename).append(": （文件不存在或已被删除）\n");
                    summary.append("- ").append(filename).append(": 已删除\n");
                    continue;
                }

                String content = Files.readString(filePath, StandardCharsets.UTF_8);
                // 普通文档截断到 2000 字符（用于日志显示）
                String truncated = content.length() > 2000 ? content.substring(0, 2000) + "\n...（已截断）" : content;
                logBuilder.append("- ").append(filename).append(":\n");
                logBuilder.append("```\n").append(truncated).append("\n```\n\n");

                // 用于压缩摘要（保留完整内容，但限制长度避免过大）
                String summaryContent = content.length() > 3000 ? content.substring(0, 3000) + "\n...（已截断）" : content;
                summary.append("- ").append(filename).append(":\n").append(summaryContent).append("\n\n");
                successCount++;
            } catch (IOException e) {
                logBuilder.append("- ").append(filename).append(": （读取失败: ").append(e.getMessage()).append("）\n");
                summary.append("- ").append(filename).append(": 读取失败\n");
            }
        }

        if (successCount == 0) {
            logBuilder.append("（无有效文件内容可记录）");
        } else {
            // 追加文档日志到 immutableBase（作为历史记录）
            Map<String, Object> logMsg = Map.of(
                    "role", "user",
                    "content", logBuilder.toString()
            );
            immutableBase.add(logMsg);
            appendToHistory(logMsg);
            log("📌 [系统] 已合并 " + pendingDocChanges.size() + " 个文档变更到基础区");
        }

        // 清空缓冲区
        pendingDocChanges.clear();

        return summary.toString();
    }

    // ===================== 日志 =====================

    private void log(String message) {
        if (logConsumer != null) {
            logConsumer.accept(message);
        } else {
            System.out.println(message);
        }
    }
}