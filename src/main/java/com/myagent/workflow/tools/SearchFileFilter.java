package com.myagent.workflow.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// @anchor: searchFilter_class
/**
 * 搜索公共基础设施：排除目录 / 扩展名 / filePattern 解析 / 文件收集遍历。
 * 供 TextSearcher、ReferenceFinder 等全文搜索模块复用，避免重复实现。
 */
public class SearchFileFilter {

    // @anchor: searchFilter_parsePatterns
    /**
     * 解析 filePattern（逗号分隔，支持 *.ext 前缀），返回规范化后的模式列表。
     * - "*.java" → "java"
     * - ".js"    → ".js"
     * - "*.py"   → "py"
     * - null / 空白 / ".*" → 空列表（表示不限制）
     */
    public static List<String> parseFilePatterns(String filePattern) {
        List<String> patterns = new ArrayList<>();
        if (filePattern == null || filePattern.trim().isEmpty() || ".*".equals(filePattern.trim())) {
            return patterns;
        }
        String[] parts = filePattern.split(",");
        for (String p : parts) {
            p = p.trim();
            if (p.startsWith("*.")) {
                patterns.add(p.substring(1));
            } else {
                patterns.add(p);
            }
        }
        return patterns;
    }

    // @anchor: searchFilter_isExcluded
    /**
     * 判断相对路径是否位于排除目录之下。
     * @param relPath 相对于搜索起始目录的路径
     * @param excludedDirs 排除目录名列表（如 "target"、"node_modules"）
     */
    public static boolean isExcludedDir(Path relPath, List<String> excludedDirs) {
        String rel = relPath.toString().replace('\\', '/');
        for (String excluded : excludedDirs) {
            if (rel.startsWith(excluded + "/") || rel.startsWith(excluded + "\\")) {
                return true;
            }
        }
        return false;
    }

    // @anchor: searchFilter_matchesExtension
    /**
     * 判断文件名是否匹配过滤条件。
     * @param fileName 文件名
     * @param patterns 文件模式列表（由 parseFilePatterns 产出）；非空时按该列表匹配
     * @param defaultExtensions 默认扩展名列表（patterns 为空时使用），如 ".java"
     * @return 匹配返回 true
     */
    public static boolean matchesExtension(String fileName, List<String> patterns, List<String> defaultExtensions) {
        String ext = "";
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx > 0) ext = fileName.substring(dotIdx).toLowerCase();

        if (!patterns.isEmpty()) {
            for (String pat : patterns) {
                if (pat.startsWith(".") && ext.equals(pat)) return true;
                else if (ext.equals("." + pat)) return true;
                else if (fileName.endsWith(pat)) return true;
            }
            return false;
        }
        return defaultExtensions.contains(ext);
    }

    // @anchor: searchFilter_collectFiles
    /**
     * 遍历 startPath 收集所有普通文件（跳过排除目录）。
     * @param startPath 搜索起始目录
     * @param excludedDirs 排除目录名列表
     * @return 文件路径列表
     * @throws IOException 遍历失败时抛出
     */
    public static List<Path> collectFiles(Path startPath, List<String> excludedDirs) throws IOException {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(startPath)) {
            stream.filter(Files::isRegularFile)
                    .forEach(file -> {
                        Path rel = startPath.relativize(file);
                        if (isExcludedDir(rel, excludedDirs)) return;
                        files.add(file);
                    });
        }
        return files;
    }
}
