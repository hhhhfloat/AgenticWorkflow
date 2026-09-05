package com.myagent.workflow.testtool;

import com.myagent.workflow.testtool.core.PromptContents;
import com.myagent.workflow.testtool.model.TestResult;
import com.myagent.workflow.testtool.view.chart.Chart;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public class MainView {

    // ===== UI 组件（由 TestRunnerFX 引用） =====
    public TextArea promptArea;
    public TextArea logArea;
    public Button runBtn;
    public Button stopBtn;
    public Label statusLabel;
    public TextField maxIterField;
    public TextField projectNameField;
    public VBox chartContainer;
    public HBox chartBox;
    public VBox lineChartBoxA;
    public VBox lineChartBoxB;
    public TableView<TestResult> resultTable;

    // 状态面板
    public VBox statusPanelA, statusPanelB;
    public Label statusALabel, statusBLabel;
    public Label costA, costB, tokensA, tokensB, hitA, hitB;

    private VBox root;

    private static final String DEFAULT_PROMPT = PromptContents.buildDefault().prompt();
    private static final String DEFAULT_PROJECT_NAME = PromptContents.buildDefault().projectName();

    public void buildUI() {
        root = new VBox(5);
        root.setPadding(new Insets(10));

        root.getChildren().add(createPromptArea());
        root.getChildren().add(createStatusPanelsAndLog());
        root.getChildren().add(createCombinedChartArea());  // 替换原来的两个方法
        root.getChildren().add(createResultTable());
        root.getChildren().add(createStatusBar());
    }

    public VBox getRoot() { return root; }

    // ===== UI 构建方法 =====
    private VBox createPromptArea() {

        Label promptLabel = new Label("📝 测试提示词");
        promptLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        promptArea = new TextArea(DEFAULT_PROMPT);
        promptArea.setPrefHeight(100);
        promptArea.setWrapText(true);

        // 迭代次数输入框
        Label iterLabel = new Label("最大迭代次数:");
        iterLabel.setStyle("-fx-font-size: 13px;");
        maxIterField = new TextField("30");
        maxIterField.setPrefWidth(60);
        HBox iterBox = new HBox(5, iterLabel, maxIterField);

        // 项目名称输入框
        Label nameLabel = new Label("项目名称:");
        nameLabel.setStyle("-fx-font-size: 13px;");
        projectNameField = new TextField(DEFAULT_PROJECT_NAME);
        projectNameField.setPrefWidth(150);
        HBox nameBox = new HBox(5, nameLabel, projectNameField);

        // 按钮
        runBtn = new Button("▶ 运行测试");
        runBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;");
        stopBtn = new Button("⏹ 停止");
        stopBtn.setStyle("-fx-background-color: #FF5722; -fx-text-fill: white; -fx-font-weight: bold;");
        stopBtn.setDisable(true);
        HBox buttonBox = new HBox(10, runBtn, stopBtn);
        buttonBox.setPadding(new Insets(5, 0, 5, 0));

        HBox controlBox = new HBox(20, nameBox, iterBox, buttonBox);
        controlBox.setPadding(new Insets(5, 0, 5, 0));

        VBox topBox = new VBox(5, promptLabel, promptArea, controlBox);
        topBox.setPadding(new Insets(10));
        return topBox;
    }

    private HBox createStatusPanelsAndLog() {
        // 状态面板 A
        statusPanelA = createStatusPanel("测试 A：启用压缩", "#4CAF50");
        statusPanelA.setPrefWidth(200);
        HBox.setHgrow(statusPanelA, Priority.NEVER);

        // 状态面板 B
        statusPanelB = createStatusPanel("测试 B：禁用压缩", "#FF5722");
        statusPanelB.setPrefWidth(200);
        HBox.setHgrow(statusPanelB, Priority.NEVER);

        // 日志区域
        Label logLabel = new Label("📋 实时日志");
        logLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefHeight(150);
        logArea.setPrefWidth(500);
        logArea.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");
        logArea.setWrapText(true);

        VBox logBox = new VBox(5, logLabel, logArea);
        logBox.setPrefWidth(500);
        logBox.setPadding(new Insets(5));

        // 水平排列，间距 15px
        HBox statusBox = new HBox(15, statusPanelA, statusPanelB, logBox);
        statusBox.setPadding(new Insets(8, 10, 8, 10));
        return statusBox;
    }

    private VBox createStatusPanel(String title, String color) {
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        Label status = new Label("⏸ 等待中");
        status.setStyle("-fx-font-size: 12px;");

        Label cost = new Label("成本：—");
        Label tokens = new Label("Token：—");
        Label hit = new Label("命中率：—");

        VBox box = new VBox(3, titleLabel, status, cost, tokens, hit);
        box.setPadding(new Insets(8));
        box.setStyle("-fx-border-color: " + color + "; -fx-border-width: 2px; -fx-border-radius: 5px; -fx-background-radius: 5px;");
        // 删除 setPrefWidth(300)，让 HBox 控制宽度

        if (title.contains("启用压缩")) {
            statusALabel = status;
            costA = cost;
            tokensA = tokens;
            hitA = hit;
        } else {
            statusBLabel = status;
            costB = cost;
            tokensB = tokens;
            hitB = hit;
        }

        return box;
    }

// 替换原有的 createChartArea() 和 createLineChartArea() 为：

    private VBox createCombinedChartArea() {
        VBox combinedContainer = new VBox(10);
        combinedContainer.setPadding(new Insets(10));
        combinedContainer.setStyle("-fx-border-color: #ddd; -fx-border-width: 1px; -fx-border-radius: 5px;");

        Label titleLabel = new Label("📈 实时迭代趋势 & 📊 对比图表");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        HBox chartRow = new HBox(10);
        chartRow.setAlignment(Pos.CENTER);
        chartRow.setPrefHeight(250);
        chartRow.setMinHeight(250);
        chartRow.setMaxHeight(250);

        // —— 折线图 A ——
        lineChartBoxA = new VBox(5);
        lineChartBoxA.setPadding(new Insets(5));
        lineChartBoxA.setStyle("-fx-border-color: #e0e0e0; -fx-border-width: 1px; -fx-border-radius: 3px;");
        lineChartBoxA.setPrefHeight(230);
        lineChartBoxA.setMinHeight(230);
        lineChartBoxA.setMaxHeight(230);
        HBox.setHgrow(lineChartBoxA, Priority.ALWAYS);
        Label labelA = new Label("启用压缩 - 实时");
        labelA.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
        lineChartBoxA.getChildren().add(labelA);

        // —— 折线图 B ——
        lineChartBoxB = new VBox(5);
        lineChartBoxB.setPadding(new Insets(5));
        lineChartBoxB.setStyle("-fx-border-color: #e0e0e0; -fx-border-width: 1px; -fx-border-radius: 3px;");
        lineChartBoxB.setPrefHeight(230);
        lineChartBoxB.setMinHeight(230);
        lineChartBoxB.setMaxHeight(230);
        HBox.setHgrow(lineChartBoxB, Priority.ALWAYS);
        Label labelB = new Label("禁用压缩 - 实时");
        labelB.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
        lineChartBoxB.getChildren().add(labelB);

        // —— 柱状图区域 (最终对比) ——
        chartContainer = new VBox(5);
        chartContainer.setPadding(new Insets(5));
        chartContainer.setStyle("-fx-border-color: #e0e0e0; -fx-border-width: 1px; -fx-border-radius: 3px;");
        chartContainer.setPrefHeight(230);
        chartContainer.setMinHeight(230);
        chartContainer.setMaxHeight(230);
        HBox.setHgrow(chartContainer, Priority.ALWAYS);

        Label chartLabel = new Label("最终对比");
        chartLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
        chartContainer.getChildren().add(chartLabel);

        // 🔥 修复：chartBox 使用 HBox 并设置 Hgrow，内容居中
        chartBox = new HBox();
        chartBox.setAlignment(Pos.CENTER);
        chartBox.setPrefHeight(200);
        chartBox.setMinHeight(200);
        HBox.setHgrow(chartBox, Priority.ALWAYS);

        // 用两个 Region 占位，让 Label 居中且 HBox 填满
        javafx.scene.layout.Region leftSpacer = new javafx.scene.layout.Region();
        javafx.scene.layout.Region rightSpacer = new javafx.scene.layout.Region();
        HBox.setHgrow(leftSpacer, Priority.ALWAYS);
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);

        Label waitLabel = new Label("等待测试完成...");
        chartBox.getChildren().addAll(leftSpacer, waitLabel, rightSpacer);

        chartContainer.getChildren().add(chartBox);

        chartRow.getChildren().addAll(lineChartBoxA, lineChartBoxB, chartContainer);
        combinedContainer.getChildren().addAll(titleLabel, chartRow);
        return combinedContainer;
    }


    private VBox createResultTable() {
        resultTable = new TableView<>();
        resultTable.setPrefHeight(150);

        TableColumn<TestResult, String> nameCol = new TableColumn<>("模式");
        nameCol.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleStringProperty(cellData.getValue().testName()));

        TableColumn<TestResult, Integer> iterCol = new TableColumn<>("迭代轮次");
        iterCol.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleIntegerProperty(cellData.getValue().totalIterations()).asObject());

        TableColumn<TestResult, Long> promptCol = new TableColumn<>("输入 Token");
        promptCol.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleLongProperty(cellData.getValue().totalPromptTokens()).asObject());

        TableColumn<TestResult, Double> costCol = new TableColumn<>("总成本 (CNY)");
        costCol.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleDoubleProperty(cellData.getValue().totalCost()).asObject());

        TableColumn<TestResult, Double> hitCol = new TableColumn<>("命中率 (%)");
        hitCol.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleDoubleProperty(cellData.getValue().cacheHitRate()).asObject());

        TableColumn<TestResult, Integer> compCol = new TableColumn<>("压缩次数");
        compCol.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleIntegerProperty(cellData.getValue().compressionCount()).asObject());

        resultTable.getColumns().addAll(nameCol, iterCol, promptCol, costCol, hitCol, compCol);

        VBox tableBox = new VBox(5, new Label("📋 结果明细"), resultTable);
        tableBox.setPadding(new Insets(10));
        return tableBox;
    }

    private Label createStatusBar() {
        statusLabel = new Label("就绪。点击「运行测试」开始。");
        statusLabel.setStyle("-fx-font-style: italic; -fx-padding: 5 0 0 0;");
        return statusLabel;
    }

    // ===== 图表更新方法 =====
    public void updateChart(Chart chart) {
        if (chart == null) {
            chartBox.getChildren().clear();
            chartBox.getChildren().add(new Label("等待测试完成..."));
            if (lineChartBoxA.getChildren().size() > 1) {
                lineChartBoxA.getChildren().remove(1, lineChartBoxA.getChildren().size());
            }
            if (lineChartBoxB.getChildren().size() > 1) {
                lineChartBoxB.getChildren().remove(1, lineChartBoxB.getChildren().size());
            }
            return;
        }

        if (chart instanceof com.myagent.workflow.testtool.view.chart.LineChartEntity) {
            String title = chart.getTitle();
            Node chartNode = chart.render();
            VBox.setVgrow(chartNode, Priority.ALWAYS);  // 只保留这一行

            if (title != null && title.contains("启用压缩")) {
                if (lineChartBoxA.getChildren().size() > 1) {
                    lineChartBoxA.getChildren().remove(1, lineChartBoxA.getChildren().size());
                }
                lineChartBoxA.getChildren().add(chartNode);
            } else if (title != null && title.contains("禁用压缩")) {
                if (lineChartBoxB.getChildren().size() > 1) {
                    lineChartBoxB.getChildren().remove(1, lineChartBoxB.getChildren().size());
                }
                lineChartBoxB.getChildren().add(chartNode);
            }
        } else {
            // 柱状图
            chartBox.getChildren().clear();
            Node chartNode = chart.render();
            VBox.setVgrow(chartNode, Priority.ALWAYS);
            HBox.setHgrow(chartNode, Priority.ALWAYS);   // 🔥 关键修复
            chartBox.getChildren().add(chartNode);
        }
    }

    public void resetCharts() {
        chartBox.getChildren().clear();
        chartBox.getChildren().add(new Label("等待测试完成后显示图表..."));
        if (lineChartBoxA.getChildren().size() > 1) {
            lineChartBoxA.getChildren().remove(1, lineChartBoxA.getChildren().size());
        }
        if (lineChartBoxB.getChildren().size() > 1) {
            lineChartBoxB.getChildren().remove(1, lineChartBoxB.getChildren().size());
        }
    }

    public void updateTable(TestResult resultA, TestResult resultB) {
        resultTable.getItems().clear();
        if (resultA != null && resultA.isValid()) {
            resultTable.getItems().add(resultA);
        }
        if (resultB != null && resultB.isValid()) {
            resultTable.getItems().add(resultB);
        }
    }
}