package com.myagent.workflow.testtool.controller;

import com.myagent.workflow.testtool.model.ChartData;
import com.myagent.workflow.testtool.model.IterationData;
import com.myagent.workflow.testtool.model.TestResult;
import com.myagent.workflow.testtool.view.chart.Chart;
import com.myagent.workflow.testtool.view.chart.ChartFactory;
import com.myagent.workflow.testtool.view.chart.LineChartEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 图表控制器 —— 管理图表的创建、切换、数据更新
 * 属于 Controller 层
 */
public class ChartController {

    private Chart currentChart;
    private Consumer<Chart> onChartChanged = null;

    private LineChartEntity lineChartA;  // 启用压缩的折线图
    private LineChartEntity lineChartB;  // 禁用压缩的折线图

    /**
     * 设置图表变更监听（View 层实现，用于更新 UI）
     */
    public void setOnChartChanged(Consumer<Chart> listener) {
        this.onChartChanged = listener;
    }

    /**
     * 根据测试结果生成对比柱状图数据
     */
    public ChartData createComparisonData(TestResult resultA, TestResult resultB) {
        List<String> xAxisLabels = List.of("总成本 (CNY)", "迭代轮次", "命中率 (%)");

        Map<String, List<Number>> values = new LinkedHashMap<>();
        values.put("启用压缩", List.of(
                resultA.totalCost(),
                (double) resultA.totalIterations(),
                resultA.cacheHitRate()
        ));
        values.put("禁用压缩", List.of(
                resultB.totalCost(),
                (double) resultB.totalIterations(),
                resultB.cacheHitRate()
        ));

        return new ChartData(ChartFactory.TYPE_BAR, xAxisLabels, List.of("启用压缩", "禁用压缩"), values);
    }

    /**
     * 显示对比柱状图
     */
    public void showComparison(TestResult resultA, TestResult resultB) {

        // System.out.println("[4] showComparison 被调用");

        if (resultA == null || resultB == null || !resultA.isValid() || !resultB.isValid()) {
            // System.out.println("THERE IS NULL");
            return;
        }

        // 使用新的对比图表
        Chart chart = ChartFactory.createComparisonChart("Token 消耗与成本对比", resultA, resultB);
        currentChart = chart;

        if (onChartChanged != null) {
            // System.out.println("[5] onChartChanged 不为 null，准备触发");
            onChartChanged.accept(chart);
        } else {
            // System.out.println("[5] onChartChanged 为 null，跳过回调");
        }
    }

    /**
     * 初始化两张折线图（测试开始时调用）
     */
    public void initLineCharts() {
        lineChartA = new LineChartEntity("启用压缩 - 实时迭代", null);
        lineChartB = new LineChartEntity("禁用压缩 - 实时迭代", null);
        // 立即渲染空图表
        if (onChartChanged != null) {
            onChartChanged.accept(lineChartA);
            onChartChanged.accept(lineChartB);
        }
    }

    /**
     * 更新折线图数据（每轮迭代调用）
     * @param isCompressionOn true 表示启用压缩，false 表示禁用压缩
     * @param data 本轮迭代数据
     */
    public void updateLineChart(boolean isCompressionOn, IterationData data) {
        LineChartEntity chart = isCompressionOn ? lineChartA : lineChartB;
        if (chart == null) return;

        chart.addDataPoint(data);

        if (onChartChanged != null) {
            onChartChanged.accept(chart);
        }
    }

    /**
     * 重置折线图（测试结束时或重新开始时调用）
     */
    public void resetLineCharts() {
        if (lineChartA != null) lineChartA.reset();
        if (lineChartB != null) lineChartB.reset();
    }

    /**
     * 获取当前图表
     */
    public Chart getCurrentChart() {
        return currentChart;
    }

    /**
     * 更新当前图表的数据（用于动态更新）
     */
    public void updateCurrentChart(ChartData newData) {
        if (currentChart != null) {
            currentChart.update(newData);
            if (onChartChanged != null) {
                onChartChanged.accept(currentChart);
            }
        }
    }

    /**
     * 清空图表
     */
    public void clear() {
        currentChart = null;
        if (lineChartA != null) lineChartA.reset();
        if (lineChartB != null) lineChartB.reset();
        if (onChartChanged != null) {
            onChartChanged.accept(null);
        }
    }

    public LineChartEntity getLineChart(boolean isCompressionOn) {
        return isCompressionOn ? lineChartA : lineChartB;
    }
}