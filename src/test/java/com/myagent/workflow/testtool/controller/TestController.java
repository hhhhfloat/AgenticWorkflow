package com.myagent.workflow.testtool.controller;

import com.myagent.workflow.core.AgentConfig;
import com.myagent.workflow.core.ConfigEditor;
import com.myagent.workflow.core.Main;
import com.myagent.workflow.testtool.model.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * 测试执行控制器 —— 管理测试的生命周期
 */
public class TestController {

    private volatile Thread currentThread = null;
    private volatile Main currentAgent = null;
    private volatile boolean stopRequested = false;

    private final DataStore dataStore;

    private TestCallback callback = null;  // ← 新增

    public TestController(DataStore dataStore) {
        this.dataStore = dataStore;
    }

    /**
     * 设置回调（由 View 层调用）
     */
    public void setCallback(TestCallback callback) {
        this.callback = callback;
    }

    /**
     * 测试回调接口 —— View 层实现此接口以接收更新
     */
    public interface TestCallback {
        void onLog(String message);
        void onTestStarted(String label);

        void onIterationUpdate(boolean isCompressionOn, IterationData data);

        void onTestCompleted(TestResult result);
        void onAllTestsCompleted();
        void onError(String error);

    }


    /**
     * 运行单个测试（使用预设回调）
     */
    public void runTest(TestConfig config) {
        if (callback == null) {
            System.err.println("⚠️ 未设置 TestCallback，无法运行测试");
            return;
        }
        runTest(config, callback);
    }

    /**
     * 运行单个测试
     */
    public void runTest(TestConfig config, TestCallback callback) {
        if (isRunning()) {
            callback.onError("测试已在运行中");
            return;
        }

        stopRequested = false;
        currentThread = new Thread(() -> {
            try {
                callback.onLog("▶ " + config.label() + " 开始...");
                callback.onTestStarted(config.label());

                TestResult result = executeSingleTest(config, callback);

                if (stopRequested) {
                    callback.onLog("⏹ 测试已被用户中断");
                    return;
                }

                if (result.isValid()) {
                    dataStore.addResult(result);
                    callback.onTestCompleted(result);

                    // System.out.println("[1] 测试 B 完成，准备显示图表 --- " + "result: "+ result.toString().length());

                    callback.onLog("✅ " + config.label() + " 完成");
                } else {
                    callback.onError("测试结果无效");
                }

            } catch (InterruptedException e) {
                callback.onLog("⏹ 测试已中断");
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                callback.onError("错误: " + e.getMessage());
                e.printStackTrace();
            } finally {
                currentThread = null;
                currentAgent = null;
            }
        });
        currentThread.start();
    }

    /**
     * 执行单次测试的内部方法
     */
    private TestResult executeSingleTest(TestConfig config, TestCallback callback) throws InterruptedException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);

        try {
            AgentConfig baseConfig = ConfigEditor.buildDefault();
            AgentConfig testConfig = new AgentConfig(
                    baseConfig.apiKey(),
                    baseConfig.model(),
                    baseConfig.autoOpenBrowser(),
                    baseConfig.mavenCommand(),
                    baseConfig.javaHome(),
                    baseConfig.pythonInterpreter(),
                    baseConfig.nodeInterpreter(),
                    baseConfig.cppCompilerType(),
                    baseConfig.msvcCompiler(),
                    baseConfig.msvcInclude(),
                    baseConfig.msvcLib(),
                    baseConfig.mingwCompiler(),
                    baseConfig.enableSecurityScan(),
                    config.compressionEnabled(),
                    config.minInterval(),    // 🆕 第 15 个参数
                    config.maxInterval()
            );

            Main agent = new Main(testConfig);
            currentAgent = agent;

            agent.setLogConsumer(msg -> {
                ps.println(msg);
                ps.flush();
                callback.onLog(msg);
            });

            // 🆕 设置迭代监听器
            agent.setIterationListener((iteration, prompt, cached, completion, cost) -> {
                // System.out.println("[DEBUG] iterationListener 被触发！轮次=" + iteration);

                // 🆕 本轮命中率（直接用本轮数据计算）
                double currentRoundHitRate = prompt > 0 ? (double) cached / prompt * 100 : 0.0;

                // 🆕 累计命中率：从 DataStore 获取（DataStore 需要维护累计值）
                double cumulativeHitRate = dataStore.getCumulativeHitRate(config.compressionEnabled());

                // 🆕 传入两个命中率：累计 + 本轮
                IterationData data = new IterationData(
                        iteration,
                        prompt,
                        cached,
                        completion,
                        cumulativeHitRate,      // 累计命中率（橙色线）
                        currentRoundHitRate,    // 本轮命中率（绿色线）
                        cost
                );

                dataStore.addIterationData(config.compressionEnabled(), data);
                callback.onIterationUpdate(config.compressionEnabled(), data);
            });

            // System.out.println("[DEBUG] iterationListener 已设置: " + (agent.iterationListenerIsNull()));

            agent.run(config.prompt(), config.maxIterations());

            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }

            String log = baos.toString(StandardCharsets.UTF_8);
            return ResultCollector.extractFromLog(log, config.label());

        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                throw (InterruptedException) e;
            }
            e.printStackTrace();
            return TestResult.EMPTY;
        } finally {
            currentAgent = null;
        }
    }

    /**
     * 停止当前测试
     */
    public void stopTest() {
        if (currentThread == null) return;
        stopRequested = true;
        if (currentAgent != null) {
            currentAgent.stop();
        }
        currentThread.interrupt();
    }

    /**
     * 是否正在运行
     */
    public boolean isRunning() {
        return currentThread != null && currentThread.isAlive();
    }

    /**
     * 获取 DataStore（供 View 读取数据）
     */
    public DataStore getDataStore() {
        return dataStore;
    }
}