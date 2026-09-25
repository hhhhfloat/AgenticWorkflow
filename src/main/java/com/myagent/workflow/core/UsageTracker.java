// @anchor: usageTracker_tot_desc
// 用量与成本追踪：累计 Token 消耗，按峰谷时段计算价格并输出统计文本
package com.myagent.workflow.core;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.time.ZoneId;

/**
 * 用量追踪器 —— 记录 Token 消耗、计算成本、输出统计。
 * <p>
 * 由 ContextManager 持有。职责独立：只处理计费逻辑，不关心上下文语义。
 * <p>
 * 定价来源：DeepSeek 官方定价页，更新于 2026-09-17。
 * 高峰时段：北京时间 周一至周五 9:00-12:00、14:00-18:00；其余为空闲。
 */
// @anchor: usageTracker_class
// 用量追踪器：持有价格常量与累计计数，负责计费与统计
public class UsageTracker {

    // ===== 价格常量（元/百万 tokens） =====
    // @anchor: usageTracker_priceConstants
    // Flash 模型峰谷时段的输入（命中/未命中缓存）与输出单价
    // Flash —— DeepSeek-V4.1-Flash
    private static final double FLASH_IN_HIT_OFF_PEAK = 0.02;
    private static final double FLASH_IN_HIT_PEAK = 0.04;
    private static final double FLASH_IN_NOT_HIT_OFF_PEAK = 1.0;
    private static final double FLASH_IN_NOT_HIT_PEAK = 2.0;
    private static final double FLASH_OUT_OFF_PEAK = 4.0;
    private static final double FLASH_OUT_PEAK = 8.0;

    // ===== 累计统计 =====
    private long totalPromptTokens = 0;
    private long totalCachedTokens = 0;
    private long totalCompletionTokens = 0;
    private int apiCallCount = 0;
    private double price = 0;

    // @anchor: usageTracker_record
    // 记录一次 API 调用的用量，累计计数并返回本次成本
    /**
     * 记录一次 API 调用的用量，并返回本次成本。
     */
    public double record(String model, long promptTokens, long cachedTokens, long completionTokens) {
        double cost = calculateCost(model, promptTokens, cachedTokens, completionTokens);

        this.totalPromptTokens += promptTokens;
        this.totalCachedTokens += cachedTokens;
        this.totalCompletionTokens += completionTokens;
        this.apiCallCount++;
        this.price += cost;

        return cost;
    }

    // @anchor: usageTracker_formatStats
    // 生成成本统计文本（调用次数、输入/输出 Token、命中率、总成本）
    /**
     * 生成统计文本（供日志输出）。
     */
    public String formatStats() {
        long uncached = totalPromptTokens - totalCachedTokens;
        double hitRate = totalPromptTokens == 0 ? 0 : (double) totalCachedTokens / totalPromptTokens * 100;

        return "📊 ========== 成本统计 ==========\n" +
                "\n📨 API 调用次数: " + apiCallCount +
                "\n📥 总输入 Token: " + totalPromptTokens +
                "\n   ├─ 缓存命中: " + totalCachedTokens +
                "\n   └─ 缓存未命中: " + uncached +
                "\n♾️ 总缓存命中率: " + String.format("%.2f", hitRate) + "%" +
                "\n📤 总输出 Token: " + totalCompletionTokens +
                "\n💵 总成本: ¥" + String.format("%.6f", price) +
                (apiCallCount > 0 ? "\n📊 平均每次成本: ¥" + String.format("%.6f", price / apiCallCount) : "") +
                "\n\n==================================";
    }

    // @anchor: usageTracker_calculateCost
    // 按当前时段单价计算单次调用成本（不累计）
    /**
     * 计算单次调用的成本（不累计）。供 Main 算迭代粒度成本用。
     */
    /**
     * 计算单次调用的成本（不累计）。供 Main 算迭代粒度成本用。
     * <p>
     * 目前只使用 Flash 模型，model 参数保留以兼容调用链与未来扩展。
     */
    public double calculateCost(String model, long promptTokens, long cachedTokens, long completionTokens) {
        boolean peak = isPeakHour();
        long uncached = promptTokens - cachedTokens;

        double inHit = peak ? FLASH_IN_HIT_PEAK : FLASH_IN_HIT_OFF_PEAK;
        double inNotHit = peak ? FLASH_IN_NOT_HIT_PEAK : FLASH_IN_NOT_HIT_OFF_PEAK;
        double out = peak ? FLASH_OUT_PEAK : FLASH_OUT_OFF_PEAK;

        return (uncached / 1_000_000.0 * inNotHit) +
                (cachedTokens / 1_000_000.0 * inHit) +
                (completionTokens / 1_000_000.0 * out);
    }

    // @anchor: usageTracker_isPeakHour
    // 判断当前是否处于工作日高峰时段（北京时间，周末与夜间为空闲）
    private boolean isPeakHour() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        DayOfWeek dow = now.getDayOfWeek();

        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }

        int totalMinutes = now.getHour() * 60 + now.getMinute();
        return (totalMinutes >= 9 * 60 && totalMinutes < 12 * 60) ||
                (totalMinutes >= 14 * 60 && totalMinutes < 18 * 60);
    }

    // ==================== Getter ====================

    public long getTotalPromptTokens() { return totalPromptTokens; }
    public long getTotalCachedTokens() { return totalCachedTokens; }
    public long getTotalCompletionTokens() { return totalCompletionTokens; }
    public double getTotalPrice() { return price; }
    public int getApiCallCount() { return apiCallCount; }
}
