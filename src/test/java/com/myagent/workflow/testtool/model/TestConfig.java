package com.myagent.workflow.testtool.model;

/**
 * 测试配置 —— 单次测试的参数集合
 */
public record TestConfig(
        String prompt,              // 测试提示词
        int maxIterations,          // 最大迭代次数
        boolean compressionEnabled, // 是否启用压缩
        String label,                // 测试标签（如 "启用压缩" / "禁用压缩"）
        int minInterval,   // 🆕
        int maxInterval
) {
    // 默认构造函数
    public TestConfig {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt 不能为空");
        }
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations 必须 > 0");
        }
        if (minInterval < 2) {
            throw new IllegalArgumentException("minInterval 必须 >= 2");
        }
        if (maxInterval < minInterval) {
            throw new IllegalArgumentException("maxInterval 必须 >= minInterval");
        }
    }

    // 便捷构造（保持向后兼容，提供默认值）
    public TestConfig(String prompt, int maxIterations, boolean compressionEnabled, String label) {
        this(prompt, maxIterations, compressionEnabled, label, 5, 15);
    }

    @Override
    public String toString() {
        return String.format(
                "TestConfig{label='%s', maxIterations=%d, compression=%s}",
                label, maxIterations, compressionEnabled
        );
    }
}