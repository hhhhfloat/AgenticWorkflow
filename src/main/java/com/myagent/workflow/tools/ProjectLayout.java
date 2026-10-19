// @anchor: projectLayout_intro
// 项目布局约定：排除目录/文件/隐藏名的单一真源，供索引、搜索、目录展示共用
package com.myagent.workflow.tools;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

// @anchor: projectLayout_class
// 项目布局工具类：统一"什么该被跳过"的判定口径
public final class ProjectLayout {

    private ProjectLayout() {
    }

    // @anchor: projectLayout_excludedDirs
    // 默认排除目录名（任意段匹配）
    public static final List<String> EXCLUDED_DIRS = List.of(
            // 编译产物
            "target", "build", "out", "dist", "bin", "obj", "classes",
            // 依赖与缓存
            "node_modules", ".gradle", ".mvn", ".cache", "vendor",
            // 版本控制 / IDE
            ".git", ".svn", ".hg", ".idea", ".vscode", ".settings",
            // Python
            "__pycache__", ".pytest_cache", "venv", ".venv",
            // 覆盖率产物
            "coverage", ".nyc_output"
    );

    // @anchor: projectLayout_excludedFiles
    // 默认排除文件名（程序自动维护的元数据）
    public static final Set<String> EXCLUDED_FILES = Set.of(
            ".anchors.json",
            ".project_index.json",
            ".agent_entry.json",
            ".anchor_index.json",
            ".anchor_index.json.bak",
            ".DS_Store"
    );

    // @anchor: projectLayout_isHiddenBasename
    // 文件名/目录名是否以 "." 开头（Unix 隐藏）
    public static boolean isHiddenBasename(String name) {
        return name != null && name.startsWith(".");
    }

    // @anchor: projectLayout_isExcludedDirName
    // 目录 basename 是否在排除集合内
    public static boolean isExcludedDirName(String name) {
        return name != null && EXCLUDED_DIRS.contains(name);
    }

    // @anchor: projectLayout_isExcludedFile
    // 文件 basename 是否在排除集合内
    public static boolean isExcludedFile(String name) {
        return name != null && EXCLUDED_FILES.contains(name);
    }

    // @anchor: projectLayout_resolveProjectRoot
    // 从沙箱内任意路径回溯到"项目根"（沙箱下一级目录）。
    // 返回 null 表示：target 不在 sandboxRoot 内，或 target 就是 sandboxRoot 本身。
    // 例：
    //   sandbox/proj/src/Main.java → sandbox/proj
    //   sandbox/proj              → sandbox/proj
    //   sandbox                   → null
    public static Path resolveProjectRoot(Path sandboxRoot, Path target) {
        Path abs = target.toAbsolutePath().normalize();
        Path root = sandboxRoot.toAbsolutePath().normalize();
        if (!abs.startsWith(root)) return null;
        Path relative = root.relativize(abs);
        if (relative.getNameCount() == 0) return null;
        return root.resolve(relative.getName(0));
    }
}