package com.myagent.workflow.testtool.view.chart;

import com.myagent.workflow.testtool.model.ChartData;
import com.myagent.workflow.testtool.model.IterationData;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * 折线图实体 —— 水平并排双图设计
 * - 左图：Token 消耗 + 缓存命中量（双线，Y 轴自动缩放）
 * - 右图：累计命中率（橙色）+ 本轮命中率（绿色），Y 轴固定 0~100%
 */
public class LineChartEntity extends Chart {

    private static final int MAX_DATA_POINTS = 60;

    // ===== Token 图（左侧） =====
    private final LineChart<Number, Number> tokenChart;
    private final XYChart.Series<Number, Number> tokenSeries;
    private final NumberAxis tokenYAxis;

    // ===== 命中率图（右侧） =====
    private final LineChart<Number, Number> hitRateChart;
    private final XYChart.Series<Number, Number> cumulativeSeries;   // 累计命中率（橙色）
    private final XYChart.Series<Number, Number> currentRoundSeries; // 本轮命中率（绿色）
    private final NumberAxis hitRateYAxis;

    // ===== 共享 X 轴 =====
    private final NumberAxis sharedXAxis;

    private final HBox container;
    private int dataCount = 0;

    public LineChartEntity(String title, ChartData data) {
        super(title, data);

        // ===== 共享 X 轴 =====
        sharedXAxis = new NumberAxis();
        sharedXAxis.setLabel("迭代轮次");
        sharedXAxis.setForceZeroInRange(false);
        sharedXAxis.setAutoRanging(true);
        sharedXAxis.setTickUnit(1);

        // ===== 1. Token 图（左侧） =====
        tokenYAxis = new NumberAxis();
        tokenYAxis.setLabel("Token 消耗");
        tokenYAxis.setForceZeroInRange(true);
        tokenYAxis.setAutoRanging(true);
        tokenYAxis.setTickLabelFormatter(new javafx.util.StringConverter<Number>() {
            @Override
            public String toString(Number object) {
                long val = object.longValue();
                if (val >= 1_000_000) return String.format("%.1fM", val / 1_000_000.0);
                if (val >= 1_000) return String.format("%.1fK", val / 1_000.0);
                return String.valueOf(val);
            }
            @Override
            public Number fromString(String string) {
                string = string.replace("K", "000").replace("M", "000000");
                try { return Double.parseDouble(string); } catch (NumberFormatException e) { return 0; }
            }
        });

        tokenChart = new LineChart<>(sharedXAxis, tokenYAxis);
        tokenChart.setTitle("Token 消耗 / 缓存命中量");
        tokenChart.setAnimated(false);
        tokenChart.setLegendVisible(true);
        tokenChart.setCreateSymbols(true);
        tokenChart.setPrefHeight(195);
        tokenChart.setStyle("-fx-font-size: 10px;");

        // —— Token 消耗曲线（蓝色） ——
        tokenSeries = new XYChart.Series<>();
        tokenSeries.setName("Token 消耗");
        tokenChart.getData().add(tokenSeries);

        // ===== 2. 命中率图（右侧） =====
        hitRateYAxis = new NumberAxis(0, 100, 10);
        hitRateYAxis.setLabel("命中率 (%)");
        hitRateYAxis.setForceZeroInRange(true);
        hitRateYAxis.setTickLabelFormatter(new javafx.util.StringConverter<Number>() {
            @Override
            public String toString(Number object) {
                return String.format("%.0f%%", object.doubleValue());
            }
            @Override
            public Number fromString(String string) {
                return Double.parseDouble(string.replace("%", ""));
            }
        });

        hitRateChart = new LineChart<>(sharedXAxis, hitRateYAxis);
        hitRateChart.setTitle("缓存命中率");
        hitRateChart.setAnimated(false);
        hitRateChart.setLegendVisible(true);   // ✅ 打开图例，区分两条线
        hitRateChart.setCreateSymbols(true);
        hitRateChart.setPrefHeight(195);
        hitRateChart.setStyle("-fx-font-size: 10px;");

        // —— 累计命中率曲线（橙色） ——
        cumulativeSeries = new XYChart.Series<>();
        cumulativeSeries.setName("累计命中率");
        hitRateChart.getData().add(cumulativeSeries);

        // —— 本轮命中率曲线（绿色） ——
        currentRoundSeries = new XYChart.Series<>();
        currentRoundSeries.setName("本轮命中率");
        hitRateChart.getData().add(currentRoundSeries);

        // ===== 3. 水平容器 =====
        container = new HBox(5);
        container.setPadding(Insets.EMPTY);
        HBox.setHgrow(tokenChart, Priority.ALWAYS);
        HBox.setHgrow(hitRateChart, Priority.ALWAYS);
        container.getChildren().addAll(tokenChart, hitRateChart);
    }

    /**
     * 添加一个新的数据点（同时更新两个图）
     */
    public void addDataPoint(IterationData data) {
        if (data == null) return;

        int iteration = data.iteration();
        double token = data.promptTokens();
        double cached = data.cachedTokens();

        // 左侧图：两条线
        tokenSeries.getData().add(new XYChart.Data<>(iteration, token));

        // 右侧图：两条线
        cumulativeSeries.getData().add(new XYChart.Data<>(iteration, data.cacheHitRate()));          // 累计命中率
        currentRoundSeries.getData().add(new XYChart.Data<>(iteration, data.currentRoundHitRate())); // 本轮命中率

        dataCount++;

        // 滑动窗口裁剪
        if (tokenSeries.getData().size() > MAX_DATA_POINTS) {
            tokenSeries.getData().remove(0);
            cumulativeSeries.getData().remove(0);
            currentRoundSeries.getData().remove(0);
        }

        // 动态调整 X 轴范围（共享）
        if (iteration > sharedXAxis.getUpperBound()) {
            sharedXAxis.setUpperBound(iteration + 2);
        }
        if (iteration > 10 && sharedXAxis.getLowerBound() < iteration - 15) {
            sharedXAxis.setLowerBound(iteration - 15);
        }
        sharedXAxis.setTickUnit(Math.max(1, (sharedXAxis.getUpperBound() - sharedXAxis.getLowerBound()) / 10));

        tokenYAxis.requestLayout();
    }

    public void reset() {
        tokenSeries.getData().clear();
        cumulativeSeries.getData().clear();
        currentRoundSeries.getData().clear();
        dataCount = 0;

        sharedXAxis.setLowerBound(0);
        sharedXAxis.setUpperBound(10);
        sharedXAxis.setTickUnit(1);
    }

    public int getDataCount() {
        return dataCount;
    }

    @Override
    public Node render() {
        return container;
    }

    @Override
    public void update(ChartData newData) {
        // 不需要实现
    }
}