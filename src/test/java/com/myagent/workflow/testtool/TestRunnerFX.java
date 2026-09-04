package com.myagent.workflow.testtool;

import com.myagent.workflow.testtool.controller.ChartController;
import com.myagent.workflow.testtool.controller.LogController;
import com.myagent.workflow.testtool.controller.TestController;
import com.myagent.workflow.testtool.model.DataStore;
import com.myagent.workflow.testtool.model.IterationData;
import com.myagent.workflow.testtool.model.TestConfig;
import com.myagent.workflow.testtool.model.TestResult;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class TestRunnerFX extends Application {

    // ===== Controllers =====
    private final DataStore dataStore = new DataStore();
    private final TestController testController = new TestController(dataStore);
    private final LogController logController = new LogController();
    private final ChartController chartController = new ChartController();

    // ===== UI Components（引用，由 MainView 创建） =====
    private TextArea promptArea;
    private TextArea logArea;
    private Button runBtn;
    private Button stopBtn;
    private Label statusLabel;
    private TextField maxIterField;
    private TextField projectNameField;
    private VBox chartContainer;
    private VBox lineChartBoxA;
    private VBox lineChartBoxB;
    private Label costA, costB, tokensA, tokensB, hitA, hitB;
    private Label statusALabel, statusBLabel;

    // ===== 测试状态 =====
    private TestResult resultA;
    private TestResult resultB;

    private final MainView mainView = new MainView();

    @Override
    public void start(Stage primaryStage) {
        // 1. 创建 UI
        mainView.buildUI();
        captureUIReferences();

        // 2. 设置回调
        setupControllers();

        // 🆕 3. 绑定按钮事件
        runBtn.setOnAction(e -> runTests());
        stopBtn.setOnAction(e -> stopTests());

        // 4. 组装并显示
        VBox root = mainView.getRoot();
        Scene scene = new Scene(root, 1350, 800);
        primaryStage.setScene(scene);
        primaryStage.setTitle("🧪 压缩效果对比测试");
        primaryStage.show();

        updateStatus("就绪。点击「运行测试」开始。", false);
    }

    private void captureUIReferences() {
        this.promptArea = mainView.promptArea;
        this.logArea = mainView.logArea;
        this.runBtn = mainView.runBtn;
        this.stopBtn = mainView.stopBtn;
        this.statusLabel = mainView.statusLabel;
        this.maxIterField = mainView.maxIterField;
        this.projectNameField = mainView.projectNameField;
        this.chartContainer = mainView.chartContainer;
        this.lineChartBoxA = mainView.lineChartBoxA;
        this.lineChartBoxB = mainView.lineChartBoxB;
        this.costA = mainView.costA;
        this.costB = mainView.costB;
        this.tokensA = mainView.tokensA;
        this.tokensB = mainView.tokensB;
        this.hitA = mainView.hitA;
        this.hitB = mainView.hitB;
        this.statusALabel = mainView.statusALabel;
        this.statusBLabel = mainView.statusBLabel;
    }

    private void setupControllers() {
        logController.setLogConsumer(this::appendLog);

        testController.setCallback(new TestController.TestCallback() {
            @Override
            public void onLog(String message) { logController.append(message); }

            @Override
            public void onTestStarted(String label) {
                Platform.runLater(() -> {
                    if (label.contains("启用压缩")) statusALabel.setText("⏳ 运行中...");
                    else statusBLabel.setText("⏳ 运行中...");
                });
            }

            @Override
            public void onIterationUpdate(boolean isCompressionOn, IterationData data) {
                Platform.runLater(() -> chartController.updateLineChart(isCompressionOn, data));
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

        // 图表回调由 MainView 中的 MainView 负责设置
        chartController.setOnChartChanged(mainView::updateChart);
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
        // 表格更新逻辑（由 MainView 提供）
        mainView.updateTable(resultA, resultB);
    }

    // ===== 测试控制 =====
    private void runTests() {
        String prompt = promptArea.getText().trim();
        if (prompt.isEmpty()) {
            statusLabel.setText("⚠️ 请填写测试提示词。");
            return;
        }

        String projectName = projectNameField.getText().trim();
        if (projectName.isEmpty()) projectName = "project";

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
        mainView.resetCharts();
        dataStore.clearHistory();
        chartController.initLineCharts();

        int maxIter = parseMaxIterations();

        String fullPrompt = prompt + "\n\n项目名称：" + projectName + "，所有代码放在 " + projectName + "/ 目录下。";



        int minInt = 5;   // 后面从 UI 读取
        int maxInt = 15;

        TestConfig configA = new TestConfig(fullPrompt, maxIter, true, "启用压缩", minInt, maxInt);
        TestConfig configB = new TestConfig(fullPrompt, maxIter, false, "禁用压缩", minInt, maxInt);

        updateStatus("运行中...", true);
        logController.append("🧪 开始压缩效果对比测试");
        runSingleTestSequentially(configA, configB, projectName);
    }

    private int parseMaxIterations() {
        try {
            String text = maxIterField.getText().trim();
            if (!text.isEmpty()) {
                int val = Integer.parseInt(text);
                return Math.min(200, Math.max(3, val));
            }
        } catch (NumberFormatException ignored) {}
        return 30;
    }

    private void runSingleTestSequentially(TestConfig configA, TestConfig configB, String projectName) {
        final String finalProjectName = projectName;
        final TestConfig finalConfigB = configB;

        TestController.TestCallback originalCallback = new TestController.TestCallback() {
            @Override
            public void onLog(String message) { logController.append(message); }

            @Override
            public void onTestStarted(String label) { /* handled above */ }

            @Override
            public void onIterationUpdate(boolean isCompressionOn, IterationData data) {
                // System.out.println("[DEBUG] onIterationUpdate 被调用! isCompressionOn=" + isCompressionOn + ", iteration=" + data.iteration());
                Platform.runLater(() -> chartController.updateLineChart(isCompressionOn, data));
            }

            @Override
            public void onTestCompleted(TestResult result) {
                Platform.runLater(() -> {
                    if (result.testName().contains("启用压缩")) {
                        resultA = result;
                        statusALabel.setText("✅ 完成");
                        updatePanelData(costA, tokensA, hitA, result);
                        logController.append("✅ 测试 A 完成");
                        Platform.runLater(() -> {
                            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                            testController.runTest(finalConfigB, this);
                        });
                    } else {
                        resultB = result;
                        statusBLabel.setText("✅ 完成");
                        updatePanelData(costB, tokensB, hitB, result);
                        logController.append("✅ 测试 B 完成");
                        archiveTestResult(result, finalProjectName);
                        mainView.updateTable(resultA, resultB);
                        if (resultA != null && resultB != null && resultA.isValid() && resultB.isValid()) {
                            chartController.showComparison(resultA, resultB);
                        }
                        statusLabel.setText("✅ 全部测试完成！");
                        updateStatus("就绪", false);
                    }
                });
            }

            @Override
            public void onAllTestsCompleted() { /* handled above */ }

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

    private void archiveTestResult(TestResult result, String projectName) {
        // 原有归档逻辑，可保留或略作精简
        // ...
    }

    private void stopTests() {
        testController.stopTest();
        logController.append("⏹ 用户请求停止测试...");
        Platform.runLater(() -> {
            statusLabel.setText("⏹ 已停止");
            updateStatus("已停止", false);
        });
    }

    public static void main(String[] args) {
        launch(args);
    }
}