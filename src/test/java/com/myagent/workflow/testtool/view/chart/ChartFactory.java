package com.myagent.workflow.testtool.view.chart;

import com.myagent.workflow.testtool.model.ChartData;
import com.myagent.workflow.testtool.model.TestResult;

/**
 * 图表工厂 —— 根据类型创建对应的图表实体
 */
public class ChartFactory {

    public static final String TYPE_BAR = "bar";
    public static final String TYPE_LINE = "line";
    public static final String TYPE_PIE = "pie";

    /**
     * 创建图表实体
     */
    public static Chart createChart(String type, String title, ChartData data) {
        return switch (type) {
            case TYPE_BAR -> new BarChartEntity(title, data);
            // case TYPE_LINE -> new LineChartEntity(title, data);  // 后续扩展
            // case TYPE_PIE -> new PieChartEntity(title, data);    // 后续扩展
            default -> throw new IllegalArgumentException("未知图表类型: " + type);
        };
    }

    /**
     * 创建对比柱状图（便捷方法）
     */
    public static Chart createComparisonChart(String title, TestResult resultA, TestResult resultB) {
        return new ComparisonChartEntity(title, resultA, resultB);
    }
}