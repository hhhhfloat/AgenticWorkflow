package com.myagent.workflow.testtool.model;

/**
 * 测试配置 —— 单次测试的参数集合
 */
public record TestConfig(
        String prompt,              // 测试提示词
        int maxIterations,          // 最大迭代次数
        boolean compressionEnabled, // 是否启用压缩
        String label                // 测试标签（如 "启用压缩" / "禁用压缩"）
) {
    // 默认构造函数
    public TestConfig {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt 不能为空");
        }
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations 必须 > 0");
        }
    }

    // 便捷构造：只传必要参数，使用默认值
    public TestConfig(String prompt, boolean compressionEnabled, String label) {
        this(prompt, 15, compressionEnabled, label);
    }

    @Override
    public String toString() {
        return String.format(
                "TestConfig{label='%s', maxIterations=%d, compression=%s}",
                label, maxIterations, compressionEnabled
        );
    }
}