// @anchor: platform_intro
// 平台判定：收口 os.name 判断，避免散落各处
package com.myagent.workflow.core.config.env;

// @anchor: platform_class
// 平台工具类：提供 Windows / Linux 判定
public final class Platform {
    private Platform() {}

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    // @anchor: platform_isWindows
    public static boolean isWindows() { return WINDOWS; }

    // @anchor: platform_isLinux
    public static boolean isLinux()   { return !WINDOWS; }
}