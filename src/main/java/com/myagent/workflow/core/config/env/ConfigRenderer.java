// @anchor: configRenderer_intro
// 配置渲染：properties 文本生成 + 探测摘要打印
package com.myagent.workflow.core.config.env;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

// @anchor: configRenderer_class
public final class ConfigRenderer {
    private ConfigRenderer() {}

    // @anchor: configRenderer_printSummary
    public static void printSummary(Map<String, String> env, long elapsedMs) {
        System.out.println();
        for (String key : new String[]{
                "javaHome", "mavenCommand", "pythonInterpreter",
                "nodeInterpreter", "cppCompilerType", "mingwCompiler", "msvcCompiler"}) {
            String v = env.get(key);
            System.out.printf("  %-18s : %s%n", key, v == null || v.isEmpty() ? "(未找到)" : v);
        }
        System.out.printf("  %-18s : %d ms%n", "耗时", elapsedMs);
        System.out.println();
    }

    // @anchor: configRenderer_render
    // 只渲染本机工具链路径；其余配置由 AgentConfig 内置默认值提供
    public static String render(Map<String, String> env) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        StringBuilder sb = new StringBuilder();
        sb.append("# ============================================================\n");
        sb.append("# Agent Workflow 本机环境配置\n");
        sb.append("# 由 EnvDetector 自动生成（").append(ts).append("）\n");
        sb.append("# 只记录本机工具链路径；API Key、模型、安全扫描等由代码内置默认值提供\n");
        sb.append("# ============================================================\n\n");

        sb.append("# ---------- 本机工具链路径 ----------\n");
        sb.append("env.mavenCommand=").append(esc(env.get("mavenCommand"))).append('\n');
        sb.append("env.javaHome=").append(esc(env.get("javaHome"))).append('\n');
        sb.append("env.pythonInterpreter=").append(esc(env.get("pythonInterpreter"))).append('\n');
        sb.append("env.nodeInterpreter=").append(esc(env.get("nodeInterpreter"))).append('\n');
        sb.append('\n');
        sb.append("env.cppCompilerType=").append(env.get("cppCompilerType")).append('\n');
        sb.append("env.mingwCompiler=").append(esc(env.get("mingwCompiler"))).append('\n');
        sb.append("env.msvcCompiler=").append(esc(env.get("msvcCompiler"))).append('\n');
        sb.append("env.msvcInclude=").append(esc(env.get("msvcInclude"))).append('\n');
        sb.append("env.msvcLib=").append(esc(env.get("msvcLib"))).append('\n');
        return sb.toString();
    }

    private static String esc(String v) {
        if (v == null || v.isEmpty()) return "";
        return v.replace("\\", "/").replace(":", "\\:").replace("=", "\\=");
    }
}