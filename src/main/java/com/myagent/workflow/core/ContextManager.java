package com.myagent.workflow.core;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;

/**
 * 上下文管理器 —— 双区存储模型 + 多轮对话支持。
 * <p>
 * 存储模型：
 * - immutableBase：不可变基础区（SystemPrompt + 所有用户消息 + 任务摘要 + 文档变更日志）
 * - volatileWorking：易失工作区（当前任务内的消息，任务结束后清空）
 * <p>
 * v5.0 重构要点：
 * - 去 Compressor 依赖：压缩改为后端本地格式化（Agent 自压缩）
 * - 实例化日志：logConsumer 从 final 改为可变，支持后期注入
 * - 多轮对话：appendUserMessage 不重置上下文，mergeSummaryToBase 合并任务产出
 * - 序列化：提供快照/恢复方法，供 SessionStorage 落盘使用
 * - 清理冗余：移除未使用的 pendingPhaseSummary / pendingNextPlan
 */
public class ContextManager {

    // ===== 核心存储 =====
    private final List<Map<String, Object>> immutableBase = new ArrayList<>();
    private final List<Map<String, Object>> volatileWorking = new ArrayList<>();

    // ===== 文件变化日志缓冲 =====
    private final Set<String> pendingDocChanges = new HashSet<>();
    private boolean projectMdPending = false;

    // ===== 依赖（实例级） =====
    private final ObjectMapper objectMapper;
    private Consumer<String> logConsumer;

    // ===== 压缩状态 =====
    private int roundsSinceLastCheckpoint = 0;
    private final int MIN_INTERVAL;
    private final int MAX_INTERVAL;
    private final boolean compressionEnabled;
    private boolean pendingCompression = false;
    private int compressionCount = 0;      // ← 新增：本次任务期间压缩发生的次数

    private final HistoryRecorder historyRecorder;
    private final UsageTracker usageTracker = new UsageTracker();

    // ==================== 构造 ====================

    /**
     * 推荐的新构造签名。
     */
    public ContextManager(ObjectMapper objectMapper,
                          Consumer<String> logConsumer,
                          boolean compressionEnabled,
                          int minInterval,
                          int maxInterval) {
        this.objectMapper = objectMapper;
        this.logConsumer = logConsumer;
        this.compressionEnabled = compressionEnabled;
        this.MIN_INTERVAL = minInterval;
        this.MAX_INTERVAL = maxInterval;
        this.historyRecorder = new HistoryRecorder(objectMapper, logConsumer);   // ← 新增
    }

    // ==================== 生命周期 ====================

    /**
     * 首次初始化：设置 SystemPrompt 和首条用户请求。
     * 只在会话创建时调用一次。
     */
    public void init(String systemPrompt, String userRequest) {
        immutableBase.add(Map.of("role", "system", "content", systemPrompt));
        immutableBase.add(Map.of("role", "user", "content", userRequest));
        for (Map<String, Object> msg : immutableBase) {
            historyRecorder.appendMessage(msg);
        }
        log("📌 [系统] 上下文初始化完成，基础区大小: " + immutableBase.size() + " 条消息");
    }

    /**
     * 追加一条用户消息到不可变基础区（多轮对话）。
     * 与 init() 的区别：不重置上下文，只追加。
     */
    public void appendUserMessage(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            log("⚠️ [系统] 收到空用户消息，已忽略");
            return;
        }
        Map<String, Object> msg = Map.of("role", "user", "content", userMessage);
        immutableBase.add(msg);
        historyRecorder.appendMessage(msg);
        log("📌 [系统] 用户消息已追加到基础区，当前基础区大小: " + immutableBase.size() + " 条消息");
    }

    /**
     * 将一次任务结束时的摘要合并到不可变基础区，并清空工作区。
     * 用于多轮对话：每次任务结束后调用，把本轮产出浓缩成一条 system 消息。
     */
    public void mergeSummaryToBase(String summary) {
        if (summary == null || summary.isBlank()) {
            log("⚠️ [系统] 摘要为空，跳过合并");
            return;
        }

        // 先合并待提交的文档变更
        historyRecorder.flushRawLog();
        flushPendingChanges();

        Map<String, Object> summaryMsg = Map.of(
                "role", "system",
                "content", "【任务摘要】\n" + summary
        );
        immutableBase.add(summaryMsg);
        historyRecorder.appendMessage(summaryMsg);

        int workingBefore = volatileWorking.size();
        volatileWorking.clear();
        roundsSinceLastCheckpoint = 0;

        log("📌 [系统] 任务摘要已合并到基础区，工作区已清空（原 " + workingBefore + " 条消息）");
        log("📊 [系统] 当前基础区: " + immutableBase.size() + " 条，工作区: 0 条");
    }

    /**
     * 追加一个完整的轮次到工作区。
     */
    public void appendToWorking(List<Map<String, Object>> round) {
        if (round == null || round.isEmpty()) return;

        // 压缩提醒注入
        if (compressionEnabled && roundsSinceLastCheckpoint >= MAX_INTERVAL) {
            for (int i = round.size() - 1; i >= 0; i--) {
                Map<String, Object> msg = round.get(i);
                if ("tool".equals(msg.get("role"))) {
                    String original = (String) msg.get("content");
                    String reminder = "\n\n💡 【系统提醒】已达到最大压缩间隔，请在当前里程碑完成后调用 request_checkpoint 压缩上下文。";
                    msg.put("content", original + reminder);
                    log("📌 [系统] 已向工具返回结果注入压缩提醒");
                    break;
                }
            }
        }

        // 追加本轮消息
        volatileWorking.addAll(round);
        for (Map<String, Object> msg : round) {
            historyRecorder.appendMessage(msg);
        }
        roundsSinceLastCheckpoint++;

        // 检测 Agent 自压缩摘要
        if (pendingCompression) {
            String summary = extractSnapshotFromRound(round);
            if (summary != null && !summary.isBlank()) {
                historyRecorder.flushRawLog();
                flushPendingChanges();

                Map<String, Object> summaryMsg = Map.of(
                        "role", "system",
                        "content", "【压缩摘要】\n" + summary
                );
                immutableBase.add(summaryMsg);                     // ← 修复：之前漏了这行？
                historyRecorder.appendMessage(summaryMsg);

                int workingBefore = volatileWorking.size();
                volatileWorking.clear();
                roundsSinceLastCheckpoint = 0;
                compressionCount++;                                // ← 新增

                log("📌 [系统] Agent 自压缩摘要已追加到基础区，工作区已清空（原 " + workingBefore + " 条消息）");
            } else {
                log("⚠️ [系统] 压缩模式下未检测到摘要，Agent 可能未按指令执行");
            }
            pendingCompression = false;
        }
    }

    private String extractSnapshotFromRound(List<Map<String, Object>> round) {
        for (Map<String, Object> msg : round) {
            String role = (String) msg.get("role");
            String content = (String) msg.get("content");
            if ("assistant".equals(role) && content != null
                    && content.contains("## PROJECT_STATE_SNAPSHOT")) {
                int startIdx = content.indexOf("## PROJECT_STATE_SNAPSHOT");
                if (startIdx != -1) {
                    return content.substring(startIdx);
                }
            }
        }
        return null;
    }

    // ==================== 构建 ====================

    /**
     * 构建完整的消息列表（供 API 请求使用）。
     */
    public List<Map<String, Object>> buildMessages() {
        List<Map<String, Object>> result = new ArrayList<>(immutableBase);
        result.addAll(volatileWorking);
        return result;
    }

    // ==================== 压缩 ====================

    /**
     * Agent 主动触发压缩（由 ToolExecutor 调用）。
     * 标记 pendingCompression，下一轮 Agent 会输出摘要。
     */
    public String requestCheckpoint(String phaseSummary, String nextPlan) {
        if (roundsSinceLastCheckpoint < MIN_INTERVAL) {
            return "⚠️ 距上次压缩仅过了 " + roundsSinceLastCheckpoint + " 轮（最小间隔 " + MIN_INTERVAL + "），请继续工作。";
        }

        if (roundsSinceLastCheckpoint > MAX_INTERVAL) {
            log("⚠️ 警告：已超过最大间隔 " + MAX_INTERVAL + " 轮");
        }

        log("📌 [系统] 进入压缩模式（距上次压缩已过 " + roundsSinceLastCheckpoint + " 轮）");
        this.pendingCompression = true;

        return "✅ 已进入压缩模式。下一轮请按系统提示词中的【压缩模式】要求生成摘要，不要进行任何代码修改或工具调用。";
    }

    // ==================== 序列化 / 恢复 ====================

    /**
     * 获取不可变基础区的快照（用于落盘）。
     */
    public List<Map<String, Object>> getImmutableBaseSnapshot() {
        return new ArrayList<>(immutableBase);
    }

    /**
     * 获取易失工作区的快照（用于落盘）。
     */
    public List<Map<String, Object>> getVolatileWorkingSnapshot() {
        return new ArrayList<>(volatileWorking);
    }

    /**
     * 从快照恢复不可变基础区。
     */
    public void restoreImmutableBase(List<Map<String, Object>> base) {
        immutableBase.clear();
        if (base != null) {
            immutableBase.addAll(base);
        }
        log("📌 [系统] 基础区已恢复，共 " + immutableBase.size() + " 条消息");
    }

    /**
     * 从快照恢复易失工作区。
     */
    public void restoreVolatileWorking(List<Map<String, Object>> working) {
        volatileWorking.clear();
        if (working != null) {
            volatileWorking.addAll(working);
        }
        log("📌 [系统] 工作区已恢复，共 " + volatileWorking.size() + " 条消息");
    }

    // ==================== 辅助查询 ====================
    public int getBaseMessageCount() {
        return immutableBase.size();
    }

    // ==================== 历史记录 ====================

    public void appendRawLog(String type, String jsonContent) {
        historyRecorder.cacheRawLog(type, jsonContent);
    }

    public void flushRawLog() {
        historyRecorder.flushRawLog();
    }

    public void compressRawLog() {
        historyRecorder.compress();
    }

    /**
     * 记录一次 API 调用，返回本次成本。
     */
    public double recordUsage(String model, long promptTokens, long cachedTokens, long completionTokens) {
        return usageTracker.record(model, promptTokens, cachedTokens, completionTokens);
    }

    public void printStats() {
        log(usageTracker.formatStats());
    }

    // ==================== 文档变更合并 ====================

    public String flushPendingChanges() {
        StringBuilder summary = new StringBuilder();

        // 1. 处理 PROJECT.md 占位
        if (projectMdPending) {
            Map<String, Object> placeholderMsg = Map.of(
                    "role", "system",
                    "content", "【PROJECT.md 已更新】\n（将在下次压缩时处理）"
            );
            immutableBase.add(placeholderMsg);
            historyRecorder.appendMessage(placeholderMsg);
            log("📌 [系统] PROJECT.md 变更占位已写入基础区");
            projectMdPending = false;
            summary.append("PROJECT.md 已更新。");
        }

        // 2. 处理普通文档
        if (pendingDocChanges.isEmpty()) {
            return summary.toString();
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
                    logBuilder.append("- ").append(filename).append(": （文件不存在）\n");
                    summary.append("- ").append(filename).append(": 已删除\n");
                    continue;
                }

                String content = Files.readString(filePath, StandardCharsets.UTF_8);
                String truncated = content.length() > 2000 ? content.substring(0, 2000) + "\n...（已截断）" : content;
                logBuilder.append("- ").append(filename).append(":\n");
                logBuilder.append("```\n").append(truncated).append("\n```\n\n");

                String summaryContent = content.length() > 3000 ? content.substring(0, 3000) + "\n...（已截断）" : content;
                summary.append("- ").append(filename).append(":\n").append(summaryContent).append("\n\n");
                successCount++;
            } catch (IOException e) {
                logBuilder.append("- ").append(filename).append(": （读取失败: ").append(e.getMessage()).append("）\n");
                summary.append("- ").append(filename).append(": 读取失败\n");
            }
        }

        if (successCount > 0) {
            Map<String, Object> logMsg = Map.of(
                    "role", "user",
                    "content", logBuilder.toString()
            );
            immutableBase.add(logMsg);
            historyRecorder.appendMessage(logMsg);
            log("📌 [系统] 已合并 " + pendingDocChanges.size() + " 个文档变更");
        }

        pendingDocChanges.clear();
        return summary.toString();
    }

    // ==================== 日志 ====================

    /**
     * 后期注入日志消费者（用于 Session 建立 SSE 连接后绑定）。
     */
    public void setLogConsumer(Consumer<String> consumer) {
        this.logConsumer = consumer;
        this.historyRecorder.setLogConsumer(consumer);
    }

    private void log(String message) {
        Consumer<String> consumer = this.logConsumer;
        if (consumer != null) {
            consumer.accept(message);
        } else {
            System.out.println(message);
        }
    }

    public int getCompressionCount() {
        return compressionCount;
    }

    public long getTotalPromptTokens() { return usageTracker.getTotalPromptTokens(); }
    public long getTotalCachedTokens() { return usageTracker.getTotalCachedTokens(); }
    public long getTotalCompletionTokens() { return usageTracker.getTotalCompletionTokens(); }
    public double getTotalPrice() { return usageTracker.getTotalPrice(); }
    public int getApiCallCount() { return usageTracker.getApiCallCount(); }

}