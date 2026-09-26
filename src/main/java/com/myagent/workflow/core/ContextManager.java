// @anchor: contextManager_tot_desc
// 上下文管理器：双区消息存储（不可变基础区 + 易失工作区）、多轮对话与快照恢复
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
// @anchor: contextManager_class
// 上下文管理器：维护 API 请求消息列表、用量统计与历史落盘
public class ContextManager {

    // ===== 核心存储 =====
    private final List<Map<String, Object>> immutableBase = new ArrayList<>();
    private final List<Map<String, Object>> volatileWorking = new ArrayList<>();

    // ===== 依赖（实例级） =====
    private final ObjectMapper objectMapper;
    private Consumer<String> logConsumer;

    private final HistoryRecorder historyRecorder;
    private final UsageTracker usageTracker = new UsageTracker();

    // ==================== 构造 ====================

    // @anchor: contextManager_constructor
    // 创建上下文管理器并装配历史记录器
    /**
     * 推荐的新构造签名。
     */
    public ContextManager(ObjectMapper objectMapper, Consumer<String> logConsumer) {
        this.objectMapper = objectMapper;
        this.logConsumer = logConsumer;
        this.historyRecorder = new HistoryRecorder(objectMapper, logConsumer);   // ← 新增
    }

    // ==================== 生命周期 ====================

    // @anchor: contextManager_init
    // 会话首次初始化：写入 SystemPrompt 与首条用户请求
    /**
     * 首次初始化：设置 SystemPrompt 和首条用户请求。
     * 只在会话创建时调用一次。
     */
    public void init(String systemPrompt, String userRequest) {
        immutableBase.add(Map.of("role", "system", "content", systemPrompt));
        immutableBase.add(Map.of("role", "user", "content", userRequest));
        log("📌 [系统] 上下文初始化完成，基础区大小: " + immutableBase.size() + " 条消息");
    }

    // @anchor: contextManager_appendUserMessage
    // 多轮对话：向基础区追加一条用户消息（不重置上下文）
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
        log("📌 [系统] 用户消息已追加到基础区，当前基础区大小: " + immutableBase.size() + " 条消息");
    }

    // @anchor: contextManager_mergeSummaryToBase
    // 把本轮任务摘要并入基础区并清空工作区
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

        Map<String, Object> summaryMsg = Map.of(
                "role", "system",
                "content", "【任务摘要】\n" + summary
        );
        immutableBase.add(summaryMsg);

        int workingBefore = volatileWorking.size();
        volatileWorking.clear();

        log("📌 [系统] 任务摘要已合并到基础区，工作区已清空（原 " + workingBefore + " 条消息）");
        log("📊 [系统] 当前基础区: " + immutableBase.size() + " 条，工作区: 0 条");
    }

    // @anchor: contextManager_appendToWorking
    // 追加一个完整轮次（助手消息 + 工具结果）到工作区
    /**
     * 追加一个完整的轮次到工作区。
     */
    public void appendToWorking(List<Map<String, Object>> round) {
        if (round == null || round.isEmpty()) return;
        volatileWorking.addAll(round);
    }

    // ==================== 构建 ====================

    // @anchor: contextManager_buildMessages
    // 拼接基础区与工作区，生成发给 API 的完整消息列表
    /**
     * 构建完整的消息列表（供 API 请求使用）。
     */
    public List<Map<String, Object>> buildMessages() {
        List<Map<String, Object>> result = new ArrayList<>(immutableBase);
        result.addAll(volatileWorking);
        return result;
    }

    // ==================== 序列化 / 恢复 ====================

    // @anchor: contextManager_snapshot
    // 导出基础区与工作区的快照副本（供落盘）
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

    // @anchor: contextManager_restore
    // 用快照恢复基础区与工作区（供会话载入）
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

    // @anchor: contextManager_rawLog
    // 原始 API 日志的缓存、刷盘与压缩入口
    public void appendRawLog(String type, String jsonContent) {
        historyRecorder.cacheRawLog(type, jsonContent);
    }

    public void flushRawLog() {
        historyRecorder.flushRawLog();
    }

    public void compressRawLog() {
        historyRecorder.compress();
    }

    // @anchor: contextManager_recordUsage
    // 记录一次 API 调用用量并返回本次成本
    /**
     * 记录一次 API 调用，返回本次成本。
     */
    public double recordUsage(String model, long promptTokens, long cachedTokens, long completionTokens) {
        return usageTracker.record(model, promptTokens, cachedTokens, completionTokens);
    }

    // @anchor: contextManager_printStats
    // 输出累计用量与成本统计
    public void printStats() {
        log(usageTracker.formatStats());
    }

    // ==================== 日志 ====================

    // @anchor: contextManager_setLogConsumer
    // 后期注入日志消费者（SSE 连接建立后绑定），并同步给历史记录器
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

    public long getTotalPromptTokens() { return usageTracker.getTotalPromptTokens(); }
    public long getTotalCachedTokens() { return usageTracker.getTotalCachedTokens(); }
    public long getTotalCompletionTokens() { return usageTracker.getTotalCompletionTokens(); }
    public double getTotalPrice() { return usageTracker.getTotalPrice(); }
    public int getApiCallCount() { return usageTracker.getApiCallCount(); }

}
