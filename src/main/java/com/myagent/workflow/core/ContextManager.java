// @anchor: contextManager_intro
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
 * - 上下文压缩：新增 applyCompaction（LLM 摘要 + 保留最近 N 对）与 lastPromptTokens 记录
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

    // ===== 最近一次 API 调用的 prompt_tokens（上下文窗口大小的精确值） =====
    private volatile long lastPromptTokens = 0;

    // ===== 压缩指令（拼接在 immutableBase 之后作为 user 消息发给模型） =====
    private static final String COMPACT_INSTRUCTION = """
    把上述对话历史压缩成一段结构化摘要，供后续任务作为记忆使用。
    严格按以下结构输出，不要添加任何结构之外的文字、说明或收尾语：

    <history>
    TOTAL_GOAL: 整个会话的最终目标，一两句话
    USER_CONSTRAINTS: 用户明确提出的、跨轮次必须遵守的约束。每条一行，按主题分组。若没有，填「无」
    COMPLETED: 已完成的关键任务。按时间顺序综合精简总结（做了什么，结果如何）
    PENDING: 未完成事项，按优先级排序。若全部完成，填「无」
    KEY_FILES: 关键文件及其作用，逗号分隔，只列重点
    </history>

    注意：
    - **不需要展现技术细节**，这些内容已经在项目的文档中有解释。
    - TOTAL_GOAL 保留自会话开始以来的总目标，不能只写最近一轮
    - USER_CONSTRAINTS 是累积的：如果历史摘要中已存在约束，合并进来，不要丢失
    - COMPLETED 按时间顺序，先总结更早的，再详述最近的
    - 2000 字以内
    """;

    // ==================== 构造 ====================

    // @anchor: contextManager_constructor
    // 创建上下文管理器并装配历史记录器
    /**
     * 推荐的新构造签名。
     */
    public ContextManager(ObjectMapper objectMapper, Consumer<String> logConsumer, String sessionId) {
        this.objectMapper = objectMapper;
        this.logConsumer = logConsumer;
        this.historyRecorder = new HistoryRecorder(objectMapper, logConsumer, sessionId);
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

    // @anchor: contextManager_clearVolatileWorking
    // 清空工作区（任务被中断时调用），不合并任何摘要
    public void clearVolatileWorking() {
        int before = volatileWorking.size();
        volatileWorking.clear();
        if (before > 0) {
            log("📌 [系统] 任务被中断，工作区已清空（原 " + before + " 条消息）");
        }
    }

    // @anchor: contextManager_applyCompaction
// 用 LLM 生成的摘要替换基础区历史，保留 SystemPrompt 与最近 N 对 (user, summary)
    /**
     * 应用压缩结果。
     * <p>
     * 结构变化（以 keepRecentPairs=2 为例）：
     * <pre>
     * 压缩前：[system][user1][summary1][user2][summary2][user3][summary3]
     * 压缩后：[system][历史摘要][user2][summary2][user3][summary3]
     * </pre>
     * SystemPrompt 永远保留在第 0 位；从第 K 个最新 user 开始保留到末尾。
     * <p>
     * 切点语义：保留范围一定以 user 开头（通过 {@link #findCompactionStart} 保证），
     * 不会出现"只有 summary 没有对应 user"的孤儿摘要；半轮（user 无 summary）也会被保留。
     *
     * @param summary         LLM 生成的压缩摘要
     * @param keepRecentPairs 保留最近多少对 (user, summary)，下限 1
     */
    public void applyCompaction(String summary, int keepRecentPairs) {
        if (summary == null || summary.isBlank()) {
            log("⚠️ [系统] 压缩摘要为空，跳过");
            return;
        }
        if (immutableBase.size() <= 2) {
            log("⚠️ [系统] 基础区消息太少，无需压缩");
            return;
        }

        // 下限 1：至少保留最近一轮 user 请求，避免压缩后模型看不到用户最新意图
        int effectivePairs = Math.max(1, keepRecentPairs);
        int startIdx = findCompactionStart(effectivePairs);

        // startIdx <= 1：压缩范围为 [1, startIdx)，为空；不改变基础区
        if (startIdx <= 1) {
            log("⚠️ [系统] 压缩范围为空，跳过（基础区 " + immutableBase.size() + " 条）");
            return;
        }

        int beforeCount = immutableBase.size();
        Map<String, Object> sysPrompt = immutableBase.get(0);
        List<Map<String, Object>> recent = new ArrayList<>(
                immutableBase.subList(startIdx, immutableBase.size())
        );

        immutableBase.clear();
        immutableBase.add(sysPrompt);
        immutableBase.add(Map.of("role", "system", "content", "【历史摘要】\n" + summary));
        immutableBase.addAll(recent);

        log("📦 [系统] 基础区已压缩：" + beforeCount + " 条 → " + immutableBase.size()
                + " 条（保留最近 " + effectivePairs + " 对，从索引 " + startIdx + " 开始）");
    }

    // @anchor: contextManager_findCompactionStart
    /**
     * 找到压缩切点：从尾部向前数 keepRecentPairs 个 user 消息，返回该 user 的索引。
     * <p>
     * 索引 0 始终是 SystemPrompt；压缩范围是 [1, startIdx)，保留范围是 [startIdx, size)。
     * <p>
     * 语义保证：
     * <ul>
     *   <li>保留范围一定以 user 开头，不会出现"只有 summary 没有对应 user"的孤儿摘要；</li>
     *   <li>如果历史中 user 数量不足 K 个，返回第一个 user 的索引（全部保留，等于不压缩）；</li>
     *   <li>如果没有任何 user（只有 SystemPrompt + summary），返回 size（压缩范围为空）；</li>
     *   <li>不依赖基础区形状是否规整（有半轮时也能正确定位）。</li>
     * </ul>
     *
     * @param keepRecentPairs 保留最近多少对，调用方保证 >= 1
     * @return 保留范围的起始索引；压缩范围是 [1, 返回值)
     */
    public int findCompactionStart(int keepRecentPairs) {
        if (keepRecentPairs < 1) keepRecentPairs = 1;

        int userCount = 0;
        for (int i = immutableBase.size() - 1; i > 0; i--) {
            Map<String, Object> msg = immutableBase.get(i);
            if ("user".equals(msg.get("role"))) {
                userCount++;
                if (userCount == keepRecentPairs) {
                    return i;
                }
            }
        }
        // 历史中 user 数量不足 K 个：从第一个 user 开始保留（不压缩任何 user）
        for (int i = 1; i < immutableBase.size(); i++) {
            if ("user".equals(immutableBase.get(i).get("role"))) {
                return i;
            }
        }
        // 没有任何 user
        return immutableBase.size();
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
    // @anchor: contextManager_lastPromptTokens
    // 最近一次 API 调用的 prompt_tokens（由 Main.recordUsage 写入）
    public long getLastPromptTokens() { return lastPromptTokens; }
    public void setLastPromptTokens(long tokens) { this.lastPromptTokens = tokens; }

    // @anchor: contextManager_compactInstruction
    // 压缩指令文本，供 CompactHandler 拼接压缩请求使用
    public String getCompactInstruction() { return COMPACT_INSTRUCTION; }

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

    // @anchor: contextManager_recovery
    // recovery 文件相关方法转发（供 ResumeHandler / HasRecoveryHandler 使用）
    public boolean hasRecovery() {
        return historyRecorder.hasRecovery();
    }

    public java.nio.file.Path getRecoveryPath() {
        return historyRecorder.getRecoveryPath();
    }

    public void clearRecovery() {
        historyRecorder.clearRecovery();
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

    // @anchor: contextManager_baseFingerprint
    // 基础区指纹：条数 + 最后一条消息 content 的 hash
    public String getBaseFingerprint() {
        if (immutableBase.isEmpty()) return "0:empty";
        Map<String, Object> last = immutableBase.get(immutableBase.size() - 1);
        String content = String.valueOf(last.get("content"));
        return immutableBase.size() + ":" + Integer.toHexString(content.hashCode());
    }

    // @anchor: contextManager_writeRecovery
    // 写 recovery 快照：当前 buildMessages() + 基础区指纹
    public void writeRecovery() {
        historyRecorder.writeRecovery(buildMessages(), getBaseFingerprint());
    }

    // @anchor: contextManager_readRecovery
    // 读 recovery 快照；格式不兼容或损坏时返回 null
    public HistoryRecorder.RecoverySnapshot readRecovery() {
        return historyRecorder.readRecovery();
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
