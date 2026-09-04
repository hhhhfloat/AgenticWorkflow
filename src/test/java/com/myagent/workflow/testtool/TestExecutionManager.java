package com.myagent.workflow.testtool;

import com.myagent.workflow.testtool.controller.ChartController;
import com.myagent.workflow.testtool.controller.LogController;
import com.myagent.workflow.testtool.controller.TestController;
import com.myagent.workflow.testtool.model.DataStore;
import com.myagent.workflow.testtool.model.TestConfig;
import com.myagent.workflow.testtool.model.IterationData;
import com.myagent.workflow.testtool.model.TestResult;
import javafx.application.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 测试执行管理器 —— 负责测试流程控制
 */
public class TestExecutionManager {

    private final TestController testController;
    private final DataStore dataStore;
    private final LogController logController;
    private final ChartController chartController;

    // UI 引用（由 TestRunnerFX 注入）
    private MainView mainView;

    public TestExecutionManager(TestController testController, DataStore dataStore,
                                LogController logController, ChartController chartController) {
        this.testController = testController;
        this.dataStore = dataStore;
        this.logController = logController;
        this.chartController = chartController;
    }

    public void setMainView(MainView mainView) {
        this.mainView = mainView;
    }

    // 由 TestRunnerFX 调用
    public void runTests(String prompt, int maxIter, String projectName) {
        // 重置 UI
        dataStore.clearHistory();
        chartController.initLineCharts();
        if (mainView != null) {
            mainView.resetCharts();
        }

        String fullPrompt = prompt + "\n\n项目名称：" + projectName + "，所有代码放在 " + projectName + "/ 目录下。";
        TestConfig configA = new TestConfig(fullPrompt, maxIter, true, "启用压缩");
        TestConfig configB = new TestConfig(fullPrompt, maxIter, false, "禁用压缩");

        logController.append("🧪 开始压缩效果对比测试");
        runSingleTestSequentially(configA, configB, projectName);
    }

    private void runSingleTestSequentially(TestConfig configA, TestConfig configB, String projectName) {
        final String finalProjectName = projectName;
        final TestConfig finalConfigB = configB;
        final TestResult[] resultA = {null};
        final TestResult[] resultB = {null};

        TestController.TestCallback callback = new TestController.TestCallback() {
            @Override
            public void onLog(String message) {
                logController.append(message);
            }

            @Override
            public void onTestStarted(String label) {
                // 由 UI 层处理
            }

            @Override
            public void onIterationUpdate(boolean isCompressionOn, IterationData data) {
                // 由 ChartController 处理
                if (mainView != null) {
                    // 更新折线图，由 UI 层处理
                }
            }

            @Override
            public void onTestCompleted(TestResult result) {
                if (result.testName().contains("启用压缩")) {
                    resultA[0] = result;
                    logController.append("✅ 测试 A 完成");
                    Platform.runLater(() -> {
                        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                        testController.runTest(finalConfigB, this);
                    });
                } else {
                    resultB[0] = result;
                    logController.append("✅ 测试 B 完成");
                    archiveTestResult(result, finalProjectName);
                    // 更新表格
                    if (mainView != null) {
                        mainView.updateTable(resultA[0], resultB[0]);
                    }
                    if (resultA[0] != null && resultB[0] != null &&
                            resultA[0].isValid() && resultB[0].isValid()) {
                        chartController.showComparison(resultA[0], resultB[0]);
                    }
                }
            }

            @Override
            public void onAllTestsCompleted() {
                // 由 UI 层处理
            }

            @Override
            public void onError(String error) {
                logController.append("❌ " + error);
            }
        };

        testController.runTest(configA, callback);
    }

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

    public void stopTests() {
        testController.stopTest();
        logController.append("⏹ 用户请求停止测试...");
    }
}