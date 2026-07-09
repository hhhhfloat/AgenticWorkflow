package com.myagent.workflow;

/**
 * @anchor: agentConfig_constants
 * 集中管理 Agent 的所有配置常量。
 * 修改 API 地址、模型名、沙箱路径等只需改动此文件。
 */
public final class AgentConfig {

    private AgentConfig() {
        // 工具类，禁止实例化
    }

    // @anchor: agentConfig_api
    /** DeepSeek API 地址 */
    public static final String API_URL = "https://api.deepseek.com/chat/completions";

    /** 使用的模型（V4-Pro；如为 V4-Flash 改为 deepseek-v4-flash） */
    public static final String MODEL = "deepseek-v4-pro";

    // @anchor: agentConfig_sandbox
    /** 沙箱目录：所有生成的文件和执行都在此目录下进行 */
    public static final String SANDBOX_DIR = "./sandbox";

    /** 锚点索引文件路径（位于沙箱内） */
    public static final String ANCHOR_INDEX_FILE = "./sandbox/.anchor_index.json";

    // @anchor: agentConfig_iteration
    /** Agent 最大迭代次数，防止无限循环 */
    public static final int MAX_ITERATIONS = 15;
}
