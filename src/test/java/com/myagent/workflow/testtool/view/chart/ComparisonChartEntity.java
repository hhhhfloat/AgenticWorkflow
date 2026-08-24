package com.myagent.workflow.testtool.view.chart;

import com.myagent.workflow.testtool.model.ChartData;
import com.myagent.workflow.testtool.model.TestResult;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.chart.*;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;

/**
 * 对比柱状图实体 —— Token 堆叠 + 成本横线 + 命中率标注
 * 设计风格类似 DeepSeek 官网的 Token 计价展示
 */
public class ComparisonChartEntity extends Chart {

    private StackedBarChart<String, Number> chart;
    private TestResult resultA;
    private TestResult resultB;

    private static final Color COLOR_CACHED = Color.rgb(68, 173, 255);   // 浅蓝
    private static final Color COLOR_UNCACHED = Color.rgb(30, 80, 160);  // 深蓝
    private static final Color COLOR_OUTPUT = Color.rgb(46, 180, 130);   // 深绿
    private static final Color COLOR_COST_LINE = Color.rgb(255, 140, 0); // 橙色

    public ComparisonChartEntity(String title, TestResult resultA, TestResult resultB) {
        super(title, null);
        this.resultA = resultA;
        this.resultB = resultB;
    }

    @Override
    public Node render() {
        if (resultA == null || resultB == null || !resultA.isValid() || !resultB.isValid()) {
            return new Label("数据不足，无法生成对比图表");
        }

        CategoryAxis xAxis = new CategoryAxis();
        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Token 数量");

        chart = new StackedBarChart<>(xAxis, yAxis);
        chart.setTitle(title);
        chart.setAnimated(false);
        chart.setPrefHeight(280);
        chart.setLegendVisible(true);
        chart.setStyle("-fx-font-size: 12px;");

        // 构建堆叠数据
        buildChart();

        // 叠加成本横线和标注 → 使用 VBox 叠加
        return overlayCostLines(chart);
    }

    private void buildChart() {
        chart.getData().clear();

        // 系列1：缓存命中（浅蓝，底部）
        XYChart.Series<String, Number> cachedSeries = new XYChart.Series<>();
        cachedSeries.setName("缓存命中");
        cachedSeries.getData().add(new XYChart.Data<>("启用压缩", resultA.totalCachedTokens()));
        cachedSeries.getData().add(new XYChart.Data<>("禁用压缩", resultB.totalCachedTokens()));
        cachedSeries.getNode().setStyle("-fx-bar-fill: " + toHex(COLOR_CACHED) + ";");

        // 系列2：缓存未命中（深蓝，中间）
        XYChart.Series<String, Number> uncachedSeries = new XYChart.Series<>();
        uncachedSeries.setName("缓存未命中");
        long uncachedA = resultA.totalPromptTokens() - resultA.totalCachedTokens();
        long uncachedB = resultB.totalPromptTokens() - resultB.totalCachedTokens();
        uncachedSeries.getData().add(new XYChart.Data<>("启用压缩", uncachedA));
        uncachedSeries.getData().add(new XYChart.Data<>("禁用压缩", uncachedB));
        uncachedSeries.getNode().setStyle("-fx-bar-fill: " + toHex(COLOR_UNCACHED) + ";");

        // 系列3：输出（深绿，顶部）
        XYChart.Series<String, Number> outputSeries = new XYChart.Series<>();
        outputSeries.setName("输出");
        outputSeries.getData().add(new XYChart.Data<>("启用压缩", resultA.totalCompletionTokens()));
        outputSeries.getData().add(new XYChart.Data<>("禁用压缩", resultB.totalCompletionTokens()));
        outputSeries.getNode().setStyle("-fx-bar-fill: " + toHex(COLOR_OUTPUT) + ";");

        chart.getData().addAll(cachedSeries, uncachedSeries, outputSeries);
    }

    /**
     * 在图表上方叠加成本横线和标注
     */
    private Node overlayCostLines(StackedBarChart<String, Number> chart) {
        VBox container = new VBox();
        container.setStyle("-fx-background-color: transparent;");

        // 使用 VBox 包裹 chart，在 chart 上方叠加一个透明层
        // 但 JavaFX 的 StackPane 更适合做叠加
        javafx.scene.layout.StackPane stackPane = new javafx.scene.layout.StackPane();
        stackPane.getChildren().add(chart);

        // 创建一个透明的标注层（在 chart 上方）
        // 由于 StackedBarChart 无法直接叠加，我们使用 chart 的 parent 来添加标注
        // 更可靠的方式：在 chart 的布局完成后，用子节点添加

        // 简化的方式：使用 Region 作为标注层
        javafx.scene.layout.Region overlay = new javafx.scene.layout.Region();
        overlay.setMouseTransparent(true);
        overlay.setStyle("-fx-background-color: transparent;");

        // 由于在 chart 上叠加精确位置比较复杂，这里使用 VBox 在图表下方显示补充信息
        // 更实际的做法：在图表下方添加一个信息面板

        VBox infoPanel = new VBox(5);
        infoPanel.setPadding(new Insets(10, 0, 0, 0));
        infoPanel.setStyle("-fx-font-size: 12px;");

        // 构建成本横线的文字表示（用表格形式展示）
        infoPanel.getChildren().add(createInfoTable());

        VBox result = new VBox(5, chart, infoPanel);
        return result;
    }

    private Node createInfoTable() {
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(30);
        grid.setVgap(5);
        grid.setStyle("-fx-font-size: 12px;");

        // 表头
        grid.add(new Label("指标"), 0, 0);
        grid.add(new Label("启用压缩"), 1, 0);
        grid.add(new Label("禁用压缩"), 2, 0);

        // 成本行（橙色高亮）
        Label costLabel = new Label("总成本");
        costLabel.setStyle("-fx-font-weight: bold;");
        Label costAVal = new Label(String.format("¥%.6f", resultA.totalCost()));
        costAVal.setStyle("-fx-text-fill: #FF8C00; -fx-font-weight: bold;");
        Label costBVal = new Label(String.format("¥%.6f", resultB.totalCost()));
        costBVal.setStyle("-fx-text-fill: #FF8C00; -fx-font-weight: bold;");
        grid.add(costLabel, 0, 1);
        grid.add(costAVal, 1, 1);
        grid.add(costBVal, 2, 1);

        // 命中率行
        grid.add(new Label("命中率"), 0, 2);
        grid.add(new Label(String.format("%.2f%%", resultA.cacheHitRate())), 1, 2);
        grid.add(new Label(String.format("%.2f%%", resultB.cacheHitRate())), 2, 2);

        // 迭代轮次行
        grid.add(new Label("迭代轮次"), 0, 3);
        grid.add(new Label(String.valueOf(resultA.totalIterations())), 1, 3);
        grid.add(new Label(String.valueOf(resultB.totalIterations())), 2, 3);

        return grid;
    }

    private String toHex(Color color) {
        return String.format("#%02X%02X%02X",
                (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255),
                (int) (color.getBlue() * 255));
    }

    @Override
    public void update(ChartData newData) {
        // 不需要实现
    }
}