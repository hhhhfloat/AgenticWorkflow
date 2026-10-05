// @anchor: envUtil_intro
// 环境探测通用工具：用户目录、环境变量、路径归一化
package com.myagent.workflow.core.config.env;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

// @anchor: envUtil_class
public final class EnvUtil {
    private EnvUtil() {}

    // @anchor: envUtil_userHome
    public static String userHome() {
        return System.getProperty("user.home");
    }

    // @anchor: envUtil_envOr
    public static String envOr(String name, String def) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? def : v;
    }

    // @anchor: envUtil_normalize
    public static String normalize(String p) {
        if (p == null || p.isEmpty()) return "";
        return p.replace('\\', '/');
    }

    // @anchor: envUtil_addDirIfExists
    public static void addDirIfExists(List<String> list, String p) {
        if (p != null && Files.isDirectory(Paths.get(p))) list.add(p);
    }
}