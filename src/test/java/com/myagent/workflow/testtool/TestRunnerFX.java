package com.myagent.workflow.testtool;

import com.myagent.workflow.testtool.controller.ChartController;
import com.myagent.workflow.testtool.controller.LogController;
import com.myagent.workflow.testtool.controller.TestController;
import com.myagent.workflow.testtool.model.DataStore;
import com.myagent.workflow.testtool.model.TestConfig;
import com.myagent.workflow.testtool.model.TestResult;
import com.myagent.workflow.testtool.view.chart.Chart;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

public class TestRunnerFX extends Application {

    // ===== Controller 层 =====
    private final DataStore dataStore = new DataStore();
    private final TestController testController = new TestController(dataStore);
    private final LogController logController = new LogController();
    private final ChartController chartController = new ChartController();

    // ===== UI Components =====
    private TextArea promptArea;
    private TextArea logArea;
    private Button runBtn;
    private Button stopBtn;
    private Label statusLabel;
    private TextField maxIterField;
    private TextField projectNameField;  // ← 项目名称输入框
    // 图表容器
    private VBox chartContainer;
    private HBox chartBox;        // 内部 HBox，用于容纳多个图表

    // 状态面板
    private VBox statusPanelA, statusPanelB;
    private Label statusALabel, statusBLabel;
    private Label costA, costB, tokensA, tokensB, hitA, hitB;

    // 结果表格
    private TableView<TestResult> resultTable;

    // 测试结果缓存（用于回调更新）
    private TestResult resultA;
    private TestResult resultB;
    private int testCount = 0;

    private static final String DEFAULT_PROMPT = """
            使用javafx制作一个像素素材绘图器。
            可以自选颜色（六位六进制），画笔可像素级自选大小（不支持多种画笔）。
            像素大小可自选。过大的像素图无需缩放，只需在工作区截取显示对应位置的画面，并可以按住空格/鼠标中键用鼠标左键拖动。
            可以将绘制好的素材保存到png文件，存在项目目录下的一个文件夹里，可以前端打开访问。会自动将素材剪切到最小的矩形。
            规范项目格式，完全新建项目文档，忽略可能相关的名称。
            """;

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("🧪 压缩效果对比测试");

        setupControllers();

        VBox root = new VBox(5);
        root.setPadding(new Insets(10));

        // 顶部：提示词区域（不变）
        root.getChildren().add(createPromptArea());

        // 中间行：状态面板 + 日志（水平排列）
        HBox middleRow = new HBox(15);
        middleRow.setPadding(new Insets(5, 10, 5, 10));
        middleRow.setAlignment(javafx.geometry.Pos.CENTER);

        // 状态面板
        Node statusPanels = createStatusPanels();
        // 日志区域（缩小）
        Node logBox = createLogArea();

        // 让状态面板占据剩余空间，日志固定宽度
        HBox.setHgrow(statusPanels, Priority.ALWAYS);
        middleRow.getChildren().addAll(statusPanels, logBox);

        root.getChildren().add(middleRow);

        // 图表区域（扩大）
        root.getChildren().add(createChartArea());

        // 表格
        root.getChildren().add(createResultTable());

        // 底部状态栏
        root.getChildren().add(createStatusBar());

        Scene scene = new Scene(root, 1100, 800);
        primaryStage.setScene(scene);
        primaryStage.show();

        updateStatus("就绪。点击「运行测试」开始。", false);
    }

    // ===== 初始化 Controller 回调 =====
    private void setupControllers() {
        logController.setLogConsumer(this::appendLog);

        testController.setCallback(new TestController.TestCallback() {
            @Override
            public void onLog(String message) {
                logController.append(message);
            }

            @Override
            public void onTestStarted(String label) {
                Platform.runLater(() -> {
                    if (label.contains("启用压缩")) {
                        statusALabel.setText("⏳ 运行中...");
                    } else {
                        statusBLabel.setText("⏳ 运行中...");
                    }
                });
            }

            @Override
            public void onTestCompleted(TestResult result) {
                Platform.runLater(() -> {
                    if (result.testName().contains("启用压缩")) {
                        resultA = result;
                        statusALabel.setText("✅ 完成");
                        updatePanelData(costA, tokensA, hitA, result);
                    } else {
                        resultB = result;
                        statusBLabel.setText("✅ 完成");
                        updatePanelData(costB, tokensB, hitB, result);
                    }
                    updateTable();
                    if (resultA != null && resultB != null && resultA.isValid() && resultB.isValid()) {
                        chartController.showComparison(resultA, resultB);
                    }
                });
            }

            @Override
            public void onAllTestsCompleted() {
                Platform.runLater(() -> {
                    statusLabel.setText("✅ 全部测试完成！");
                    logController.append("✅ 全部测试完成！");
                    updateStatus("就绪", false);
                });
            }

            @Override
            public void onError(String error) {
                Platform.runLater(() -> {
                    logController.append("❌ " + error);
                    statusLabel.setText("❌ 错误: " + error);
                });
            }
        });

        chartController.setOnChartChanged(chart -> {
            Platform.runLater(() -> {
                chartBox.getChildren().clear();
                if (chart != null) {
                    Region chartNode = (Region) chart.render();
                    chartNode.setPrefWidth(350);
                    chartNode.setPrefHeight(220);
                    chartBox.getChildren().add(chartNode);
                } else {
                    chartBox.getChildren().add(new Label("暂无图表数据"));
                }
            });
        });
    }

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
        this.maxIterField = new TextField("30");
        maxIterField.setPrefWidth(60);
        maxIterField.setStyle("-fx-font-size: 13px;");
        HBox iterBox = new HBox(5, iterLabel, maxIterField);

        // 项目名称输入框
        Label nameLabel = new Label("项目名称:");
        nameLabel.setStyle("-fx-font-size: 13px;");
        this.projectNameField = new TextField("pixel-art-studio");
        projectNameField.setPrefWidth(150);
        projectNameField.setStyle("-fx-font-size: 13px;");
        HBox nameBox = new HBox(5, nameLabel, projectNameField);

        // 按钮行
        runBtn = new Button("▶ 运行测试");
        runBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;");
        runBtn.setOnAction(e -> runTests());

        stopBtn = new Button("⏹ 停止");
        stopBtn.setStyle("-fx-background-color: #FF5722; -fx-text-fill: white; -fx-font-weight: bold;");
        stopBtn.setOnAction(e -> stopTests());
        stopBtn.setDisable(true);

        HBox buttonBox = new HBox(10, runBtn, stopBtn);
        buttonBox.setPadding(new Insets(5, 0, 5, 0));

        // 组合控制行
        HBox controlBox = new HBox(20, nameBox, iterBox, buttonBox);
        controlBox.setPadding(new Insets(5, 0, 5, 0));

        VBox topBox = new VBox(5, promptLabel, promptArea, controlBox);
        topBox.setPadding(new Insets(10));
        return topBox;
    }

    private HBox createStatusPanels() {
        statusPanelA = createStatusPanel("测试 A：启用压缩", "#4CAF50");
        statusPanelB = createStatusPanel("测试 B：禁用压缩", "#FF5722");

        // 让两个面板平分宽度
        HBox.setHgrow(statusPanelA, Priority.ALWAYS);
        HBox.setHgrow(statusPanelB, Priority.ALWAYS);

        HBox statusBox = new HBox(20, statusPanelA, statusPanelB);
        statusBox.setPadding(new Insets(10));
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
        box.setPadding(new Insets(10));
        box.setStyle("-fx-border-color: " + color + "; -fx-border-width: 2px; -fx-border-radius: 5px; -fx-background-radius: 5px;");
        box.setPrefWidth(300);

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

    private VBox createLogArea() {
        Label logLabel = new Label("📋 实时日志");
        logLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefHeight(130);          // 缩小高度（匹配状态面板高度）
        logArea.setPrefWidth(350);           // 固定宽度
        logArea.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");
        logArea.setWrapText(true);

        VBox logBox = new VBox(5, logLabel, logArea);
        logBox.setPrefWidth(350);            // 固定宽度
        logBox.setPadding(new Insets(10));
        return logBox;
    }

    private VBox createChartArea() {
        // 外层 VBox：用于图表区域整体布局
        chartContainer = new VBox();
        chartContainer.setPrefHeight(250);           // 保持原有高度
        chartContainer.setPadding(new Insets(10));
        chartContainer.setStyle("-fx-border-color: #ddd; -fx-border-width: 1px; -fx-border-radius: 5px;");
        chartContainer.setFillWidth(true);

        // 内层 HBox：用于容纳多个图表并排
        chartBox = new HBox(20);
        chartBox.setAlignment(javafx.geometry.Pos.CENTER);
        chartBox.setPrefHeight(230);
        chartBox.setFillHeight(true);
        chartBox.getChildren().add(new Label("等待测试完成后显示图表..."));

        chartContainer.getChildren().add(chartBox);

        Label chartLabel = new Label("📊 对比图表");
        chartLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        VBox chartBoxOuter = new VBox(5, chartLabel, chartContainer);
        VBox.setVgrow(chartContainer, Priority.ALWAYS);
        chartBoxOuter.setPadding(new Insets(10));
        return chartBoxOuter;
    }

    private VBox createResultTable() {
        resultTable = new TableView<>();
        resultTable.setPrefHeight(150);

        TableColumn<TestResult, String> nameCol = new TableColumn<>("模式");
        nameCol.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleStringProperty(cellData.getValue().testName())
        );

        TableColumn<TestResult, Integer> iterCol = new TableColumn<>("迭代轮次");
        iterCol.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleIntegerProperty(cellData.getValue().totalIterations()).asObject()
        );

        TableColumn<TestResult, Long> promptCol = new TableColumn<>("输入 Token");
        promptCol.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleLongProperty(cellData.getValue().totalPromptTokens()).asObject()
        );

        TableColumn<TestResult, Double> costCol = new TableColumn<>("总成本 (CNY)");
        costCol.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleDoubleProperty(cellData.getValue().totalCost()).asObject()
        );

        TableColumn<TestResult, Double> hitCol = new TableColumn<>("命中率 (%)");
        hitCol.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleDoubleProperty(cellData.getValue().cacheHitRate()).asObject()
        );

        TableColumn<TestResult, Integer> compCol = new TableColumn<>("压缩次数");
        compCol.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleIntegerProperty(cellData.getValue().compressionCount()).asObject()
        );

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

    // ===== UI 更新方法 =====
    private void updateStatus(String msg, boolean isRunning) {
        statusLabel.setText(msg);
        if (isRunning) {
            runBtn.setDisable(true);
            runBtn.setText("⏳ 运行中...");
            stopBtn.setDisable(false);
        } else {
            runBtn.setDisable(false);
            runBtn.setText("▶ 运行测试");
            stopBtn.setDisable(true);
        }
    }

    private void appendLog(String msg) {
        System.out.println("[LogController] " + msg);
        Platform.runLater(() -> {
            logArea.appendText(msg + "\n");
            logArea.setScrollTop(Double.MAX_VALUE);
        });
    }

    private void updatePanelData(Label cost, Label tokens, Label hit, TestResult result) {
        if (result != null && result.isValid()) {
            cost.setText(String.format("成本：¥%.6f", result.totalCost()));
            tokens.setText(String.format("Token：%d", result.totalPromptTokens()));
            hit.setText(String.format("命中率：%.2f%%", result.cacheHitRate()));
        }
    }

    private void updateTable() {
        resultTable.getItems().clear();
        if (resultA != null && resultA.isValid()) {
            resultTable.getItems().add(resultA);
        }
        if (resultB != null && resultB.isValid()) {
            resultTable.getItems().add(resultB);
        }
    }

    // ===== 测试控制 =====
    private void runTests() {
        String prompt = promptArea.getText().trim();
        if (prompt.isEmpty()) {
            statusLabel.setText("⚠️ 请填写测试提示词。");
            return;
        }

        // ===== 读取项目名称 =====
        String projectName = projectNameField.getText().trim();
        if (projectName.isEmpty()) {
            projectName = "project";
        }

        // ===== 检查目录是否存在，提示用户 =====
        Path projectDir = Paths.get("sandbox", projectName);
        if (Files.exists(projectDir)) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("目录已存在");
            alert.setHeaderText("项目目录 " + projectName + " 已存在");
            alert.setContentText("是否删除并覆盖？点击「确定」删除，点击「取消」退出。");
            Optional<ButtonType> result = alert.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                try {
                    Files.walk(projectDir)
                            .sorted((a, b) -> b.compareTo(a))
                            .forEach(p -> {
                                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                            });
                    logController.append("🗑 已删除旧项目目录: " + projectName);
                } catch (IOException e) {
                    logController.append("❌ 删除旧目录失败: " + e.getMessage());
                    return;
                }
            } else {
                logController.append("⚠️ 用户取消测试，项目目录已存在: " + projectName);
                return;
            }
        }

        // ===== 将项目名称注入提示词 =====
        String fullPrompt = prompt + "\n\n项目名称：" + projectName + "，所有代码放在 " + projectName + "/ 目录下。";

        // ===== 读取迭代次数 =====
        int maxIter = 30;
        try {
            String iterText = maxIterField.getText().trim();
            if (!iterText.isEmpty()) {
                maxIter = Integer.parseInt(iterText);
                if (maxIter < 3) maxIter = 3;
                if (maxIter > 200) maxIter = 200;
            }
        } catch (NumberFormatException e) {
            logController.append("⚠️ 迭代次数格式错误，使用默认值 30");
        }

        // 重置 UI
        resultA = null;
        resultB = null;
        costA.setText("成本：...");
        tokensA.setText("Token：...");
        hitA.setText("命中率：...");
        costB.setText("成本：...");
        tokensB.setText("Token：...");
        hitB.setText("命中率：...");
        statusALabel.setText("⏸ 等待中");
        statusBLabel.setText("⏸ 等待中");
        logArea.clear();
        chartContainer.getChildren().clear();
        chartContainer.getChildren().add(new Label("等待测试完成后显示图表..."));
        resultTable.getItems().clear();
        testCount = 0;

        dataStore.clearHistory();

        // ✅ 使用 fullPrompt 创建配置
        TestConfig configA = new TestConfig(fullPrompt, maxIter, true, "启用压缩");
        TestConfig configB = new TestConfig(fullPrompt, maxIter, false, "禁用压缩");

        updateStatus("运行中...", true);
        logController.append("🧪 开始压缩效果对比测试");

        // ✅ 传入 projectName
        runSingleTestSequentially(configA, configB, projectName);
    }

    /**
     * 顺序运行两个测试，由回调驱动下一个
     */
    private void runSingleTestSequentially(TestConfig configA, TestConfig configB, String projectName) {
        final String finalProjectName = projectName;  // 用于回调

        TestController.TestCallback originalCallback = new TestController.TestCallback() {
            @Override
            public void onLog(String message) {
                logController.append(message);
            }

            @Override
            public void onTestStarted(String label) {
                Platform.runLater(() -> {
                    if (label.contains("启用压缩")) {
                        statusALabel.setText("⏳ 运行中...");
                    } else {
                        statusBLabel.setText("⏳ 运行中...");
                    }
                });
            }

            @Override
            public void onTestCompleted(TestResult result) {
                Platform.runLater(() -> {
                    if (result.testName().contains("启用压缩")) {
                        resultA = result;
                        statusALabel.setText("✅ 完成");
                        updatePanelData(costA, tokensA, hitA, result);
                        logController.append("✅ 测试 A 完成");
                        // 自动启动测试 B
                        Platform.runLater(() -> testController.runTest(configB, this));
                    } else {
                        resultB = result;
                        statusBLabel.setText("✅ 完成");
                        updatePanelData(costB, tokensB, hitB, result);
                        logController.append("✅ 测试 B 完成");
                        // 归档测试结果
                        archiveTestResult(result, finalProjectName);
                        updateTable();
                        if (resultA != null && resultB != null && resultA.isValid() && resultB.isValid()) {
                            chartController.showComparison(resultA, resultB);
                        }
                        statusLabel.setText("✅ 全部测试完成！");
                        updateStatus("就绪", false);
                    }
                });
            }

            @Override
            public void onAllTestsCompleted() {
                Platform.runLater(() -> {
                    statusLabel.setText("✅ 全部测试完成！");
                    updateStatus("就绪", false);
                });
            }

            @Override
            public void onError(String error) {
                Platform.runLater(() -> {
                    logController.append("❌ " + error);
                    statusLabel.setText("❌ 错误: " + error);
                    updateStatus("就绪", false);
                });
            }
        };

        testController.runTest(configA, originalCallback);
    }

    /**
     * 归档测试结果到 token-tests/ 目录
     */
    private void archiveTestResult(TestResult result, String projectName) {
        if (result == null || !result.isValid()) return;

        try {
            Path source = Paths.get("sandbox", projectName);
            if (!Files.exists(source)) {
                logController.append("⚠️ 项目目录不存在: " + projectName);
                return;
            }

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String label = result.testName().contains("启用压缩") ? "comp_on" : "comp_off";
            Path targetDir = Paths.get("token-tests");
            if (!Files.exists(targetDir)) {
                Files.createDirectories(targetDir);
            }

            Path target = targetDir.resolve(label + "_" + projectName + "_" + timestamp);
            Files.move(source, target);
            logController.append("📦 已归档至: " + target.getFileName());
        } catch (IOException e) {
            logController.append("❌ 归档失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void stopTests() {
        testController.stopTest();
        logController.append("⏹ 用户请求停止测试...");
        Platform.runLater(() -> {
            statusLabel.setText("⏹ 已停止（用户手动中断）");
            updateStatus("已停止", false);
        });
    }

    public static void main(String[] args) {
        launch(args);
    }
}