// @anchor: platform_intro
// 平台判定：收口 os.name 判断，避免散落各处
package com.myagent.workflow.core.config.env;

// @anchor: platform_class
// 平台工具类：提供 Windows / macOS / Linux 判定
public final class Platform {
    private Platform() {}

    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase();
    private static final boolean WINDOWS = OS_NAME.contains("win");
    private static final boolean MAC = OS_NAME.contains("mac");

    // @anchor: platform_isWindows
    // 是否 Windows
    public static boolean isWindows() { return WINDOWS; }

    // @anchor: platform_isMac
    // 是否 macOS：仅用于"外部打开动作"选择 open 命令，不参与进程组等 POSIX 分支
    public static boolean isMac()     { return MAC; }

    // @anchor: platform_isLinux
    // 是否非 Windows（Linux / WSL / macOS 归入此类，供 setsid、进程组、可执行位等 POSIX 分支使用）
    public static boolean isLinux()   { return !WINDOWS; }
}
