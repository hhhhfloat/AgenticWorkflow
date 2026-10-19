// @anchor: configEditor_intro
// 运行配置装配器：把前端请求中的 config 字段覆盖到默认配置之上
package com.myagent.workflow.core.config;

import com.fasterxml.jackson.databind.JsonNode;

// @anchor: configEditor_class
// 配置编辑器：从 HTTP 请求构建运行配置，或返回默认配置
public class ConfigEditor {

    // @anchor: configEditor_buildFromRequest
// 用请求中的 config 字段覆盖默认配置，但只允许"无害字段"和"安全可执行路径"覆盖
    public static AgentConfig buildFromRequest(JsonNode root) {
        AgentConfig baseConfig = AgentConfig.buildDefaultConfig();

        if (!root.has("config")) {
            return baseConfig;
        }

        JsonNode cfg = root.get("config");

        return new AgentConfig(
                baseConfig.apiKey(),
                getString(cfg, "model", baseConfig.model()),
                getBool(cfg, "autoOpenBrowser", baseConfig.autoOpenBrowser()),
                safeExecutablePath(cfg, "mavenCommand", baseConfig.mavenCommand()),
                safeExecutablePath(cfg, "javaHome", baseConfig.javaHome()),
                safeExecutablePath(cfg, "pythonInterpreter", baseConfig.pythonInterpreter()),
                safeExecutablePath(cfg, "nodeInterpreter", baseConfig.nodeInterpreter()),
                safeCppType(cfg, baseConfig.cppCompilerType()),
                safeExecutablePath(cfg, "msvcCompiler", baseConfig.msvcCompiler()),
                // msvcInclude / msvcLib 是环境变量，只能影响编译器查找头文件/库的位置，
                // 不构成代码执行面，保留覆盖（配置面板需要调整 MSVC 环境）
                getString(cfg, "msvcInclude", baseConfig.msvcInclude()),
                getString(cfg, "msvcLib", baseConfig.msvcLib()),
                safeExecutablePath(cfg, "mingwCompiler", baseConfig.mingwCompiler()),
                // A3 残留：安全扫描开关只能由服务端配置决定，请求不能关闭
                baseConfig.enableSecurityScan()
        );
    }

    // @anchor: configEditor_buildDefault
    // 构建默认配置（完全基于系统常量和环境变量）
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

    private static boolean getBool(JsonNode node, String key, boolean defaultValue) {
        return node.has(key) ? node.get(key).asBoolean() : defaultValue;
    }

    // @anchor: configEditor_safeExecutablePath
    /**
     * 安全校验可执行路径覆盖。
     * <p>
     * 威胁模型：请求可控的 mavenCommand / javaHome / pythonInterpreter 等字段最终会被
     * 原样塞进 ProcessBuilder 命令首位。结合 /upload 写入 sandbox、/runProject 触发执行，
     * 可构成"上传 → 指定为解释器 → 执行"的 RCE 链路。
     * <p>
     * 规则：
     * <ul>
     *   <li>空值 / 与 baseValue 相同 → 直接返回 baseValue；</li>
     *   <li>不含路径分隔符的简单命令名（如 "mvn"、"python"）→ 放行（依赖 PATH）；</li>
     *   <li>含分隔符 → 必须是绝对路径，且 normalize 后不在沙箱内；</li>
     *   <li>其余情况 → 回退到 baseValue 并打印警告。</li>
     * </ul>
     */
    private static String safeExecutablePath(JsonNode cfg, String key, String baseValue) {
        if (!cfg.has(key) || cfg.get(key).isNull()) {
            return baseValue;
        }
        String userValue = cfg.get(key).asText().trim();
        if (userValue.isEmpty() || userValue.equals(baseValue)) {
            return baseValue;
        }
        if (isSafeExecutable(userValue)) {
            return userValue;
        }
        System.err.println("⚠️ [ConfigEditor] 字段 " + key + " 的请求值不安全，已回退默认："
                + userValue);
        return baseValue;
    }

    // @anchor: configEditor_isSafeExecutable
    // 判断请求覆盖的可执行路径是否安全
    private static boolean isSafeExecutable(String value) {
        if (value == null || value.isBlank()) return false;

        // 防御性拒绝 shell 元字符（ProcessBuilder 不经过 shell，但阻止意外拼接）
        if (value.matches(".*[&|;<>`$].*")) return false;

        // 不含路径分隔符：视为 PATH 中的命令名
        if (!value.contains("/") && !value.contains("\\")) {
            return true;
        }

        // 含分隔符：必须是绝对路径，且不在沙箱内
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(value);
            if (!p.isAbsolute()) {
                return false;
            }
            java.nio.file.Path normalized = p.normalize();
            // 处理 \\?\ 前缀（Windows 长路径），剥前缀后再 normalize 检查
            String pathStr = normalized.toString();
            if (pathStr.startsWith("\\\\?\\")) {
                pathStr = pathStr.substring(4);
                normalized = java.nio.file.Paths.get(pathStr).normalize();
            }
            java.nio.file.Path sandbox = java.nio.file.Paths
                    .get(AgentConfig.getSandboxDir()).toAbsolutePath().normalize();
            return !normalized.startsWith(sandbox);
        } catch (Exception e) {
            return false;
        }
    }

    // @anchor: configEditor_safeCppType
    // cppCompilerType 只允许 "mingw" / "msvc"，其余回退默认
    private static String safeCppType(JsonNode cfg, String baseValue) {
        if (!cfg.has("cppCompilerType") || cfg.get("cppCompilerType").isNull()) {
            return baseValue;
        }
        String v = cfg.get("cppCompilerType").asText().trim().toLowerCase();
        if ("mingw".equals(v) || "msvc".equals(v)) {
            return v;
        }
        return baseValue;
    }

}
