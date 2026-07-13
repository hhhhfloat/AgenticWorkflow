package com.myagent.workflow.core;

import com.fasterxml.jackson.databind.JsonNode;

public class ConfigEditor {

    // 从请求根节点构建运行配置
    public static AgentConfig buildFromRequest(JsonNode root) {
        // 1. 先构建一个完全基于系统默认值的配置
        AgentConfig baseConfig = AgentConfig.buildDefaultConfig();

        // 2. 如果请求中没有 config 字段，直接返回默认配置
        if (!root.has("config")) {
            return baseConfig;
        }

        JsonNode cfg = root.get("config");

        // 3. 用前端传入的值覆盖默认配置
        return new AgentConfig(
                System.getenv("DEEPSEEK_API_KEY"),
                getString(cfg, "model", baseConfig.model()),
                getBool(cfg, "autoOpenBrowser", baseConfig.autoOpenBrowser()),
                getString(cfg, "mavenCommand", baseConfig.mavenCommand()),
                getString(cfg, "javaHome", baseConfig.javaHome()),
                getString(cfg, "pythonInterpreter", baseConfig.pythonInterpreter()),
                getString(cfg, "nodeInterpreter", baseConfig.nodeInterpreter()),
                getString(cfg, "cppCompilerType", baseConfig.cppCompilerType()),
                getString(cfg, "msvcCompiler", baseConfig.msvcCompiler()),
                getString(cfg, "msvcInclude", baseConfig.msvcInclude()),
                getString(cfg, "msvcLib", baseConfig.msvcLib()),
                getString(cfg, "mingwCompiler", baseConfig.mingwCompiler())
        );
    }

    // 构建默认配置（完全基于系统常量和环境变量）
    public static AgentConfig buildDefault() {
        return AgentConfig.buildDefaultConfig();
    }

    // ===== 辅助工具方法 =====
    private static String getString(JsonNode node, String key, String defaultValue) {
        if (node.has(key) && !node.get(key).isNull()) {
            String value = node.get(key).asText().trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return defaultValue;
    }

    private static int getInt(JsonNode node, String key, int defaultValue) {
        return node.has(key) ? node.get(key).asInt() : defaultValue;
    }

    private static boolean getBool(JsonNode node, String key, boolean defaultValue) {
        return node.has(key) ? node.get(key).asBoolean() : defaultValue;
    }
}