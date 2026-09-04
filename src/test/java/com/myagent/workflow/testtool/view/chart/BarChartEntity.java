package com.myagent.workflow.testtool.view.chart;

import com.myagent.workflow.testtool.model.ChartData;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;

import java.util.List;

/**
 * 柱状图实体 —— 用于对比不同指标
 * 使用 nodeProperty 监听方式安全设置颜色，避免 NPE
 */
public class BarChartEntity extends Chart {

    private BarChart<String, Number> barChart;
    private static final String[] DEFAULT_COLORS = {
            "#4CAF50",  // 绿色
            "#FF7043",  // 橙红色
            "#42A5F5",  // 蓝色
            "#FFA726",  // 橙色
            "#AB47BC",  // 紫色
            "#26C6DA"   // 青色
    };

    public BarChartEntity(String title, ChartData data) {
        super(title, data);
    }

    @Override
    public Node render() {
        if (data == null || !data.isValid()) {
            Label emptyLabel = new Label("暂无数据");
            emptyLabel.setStyle("-fx-font-size: 16px; -fx-text-fill: #888;");
            return new StackPane(emptyLabel);
        }

        CategoryAxis xAxis = new CategoryAxis();
        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("数值");

        barChart = new BarChart<>(xAxis, yAxis);
        barChart.setTitle(title);
        barChart.setAnimated(false);
        barChart.setPrefHeight(250);
        barChart.setLegendVisible(true);

        buildChart();

        return barChart;
    }

    @Override
    public void update(ChartData newData) {
        this.data = newData;
        if (barChart != null) {
            buildChart();
        }
    }

    private void buildChart() {
        if (barChart == null) return;

        barChart.getData().clear();

        int colorIndex = 0;
        for (String seriesName : data.seriesNames()) {
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            series.setName(seriesName);

            List<Number> values = data.getValuesForSeries(seriesName);
            for (int i = 0; i < data.xAxisLabels().size() && i < values.size(); i++) {
                series.getData().add(new XYChart.Data<>(
                        data.xAxisLabels().get(i),
                        values.get(i)
                ));
            }

            barChart.getData().add(series);

            // ✅ 安全设置颜色：使用监听器方式
            if (colorIndex < DEFAULT_COLORS.length) {
                String color = DEFAULT_COLORS[colorIndex];
                // 先尝试直接设置（如果节点已存在）
                if (series.getNode() != null) {
                    series.getNode().setStyle("-fx-bar-fill: " + color + ";");
                }
                // 同时添加监听器，确保节点创建后也能设置
                final String finalColor = color;
                series.nodeProperty().addListener((obs, oldNode, newNode) -> {
                    if (newNode != null) {
                        newNode.setStyle("-fx-bar-fill: " + finalColor + ";");
                    }
                });
            }
            colorIndex++;
        }
    }
}