package com.myagent.workflow.testtool.model;

import java.util.List;
import java.util.Map;

/**
 * 图表数据模型 —— 所有图表的数据载体
 * 属于 Model 层
 */
public record ChartData(
        String chartType,                      // bar / line / pie
        List<String> xAxisLabels,              // X轴标签（如 "总成本", "迭代轮次"）
        List<String> seriesNames,              // 系列名称（如 "启用压缩", "禁用压缩"）
        Map<String, List<Number>> values       // 系列名 → 数据列表
) {
    /**
     * 获取某个系列的数据
     */
    public List<Number> getValuesForSeries(String seriesName) {
        return values.getOrDefault(seriesName, List.of());
    }

    /**
     * 验证数据是否有效
     */
    public boolean isValid() {
        return xAxisLabels != null && !xAxisLabels.isEmpty()
                && seriesNames != null && !seriesNames.isEmpty()
                && values != null && !values.isEmpty();
    }

    /**
     * 创建空的对比数据（用于占位）
     */
    public static ChartData emptyComparison() {
        return new ChartData(
                "bar",
                List.of("总成本", "迭代轮次", "命中率"),
                List.of("启用压缩", "禁用压缩"),
                Map.of(
                        "启用压缩", List.of(0.0, 0.0, 0.0),
                        "禁用压缩", List.of(0.0, 0.0, 0.0)
                )
        );
    }
}