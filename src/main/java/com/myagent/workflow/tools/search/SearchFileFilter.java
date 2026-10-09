// @anchor: searchFilter_intro
// 检索文件收集与过滤：扩展名匹配、默认排除目录
package com.myagent.workflow.tools.search;

import com.myagent.workflow.tools.ProjectLayout;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

// @anchor: searchFilter_class
/**
 * 搜索公共基础设施：排除目录 / 扩展名 / filePattern 解析 / 文件收集遍历。
 * 供 TextSearcher、ReferenceFinder 等全文搜索模块复用，避免重复实现。
 */
public class SearchFileFilter {

    // @anchor: searchFilter_defaultExcludes
    /** 默认排除目录名（引用 ProjectLayout 单一真源） */
    public static final List<String> DEFAULT_EXCLUDED_DIRS = ProjectLayout.EXCLUDED_DIRS;

    /** 默认排除文件名（引用 ProjectLayout 单一真源） */
    public static final List<String> DEFAULT_EXCLUDED_FILES =
                List.copyOf(ProjectLayout.EXCLUDED_FILES);

    // @anchor: searchFilter_parseFilePatterns
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
     * 判断路径中任意一段是否命中排除目录名。
     *
     * @param relPath      相对于搜索起始目录的路径
     * @param excludedDirs 排除目录名列表
     */
    public static boolean isExcludedDir(Path relPath, List<String> excludedDirs) {
        for (Path seg : relPath) {
            if (excludedDirs.contains(seg.toString())) {
                return true;
            }
        }
        return false;
    }

    // @anchor: searchFilter_matchesExtension
    /**
     * 判断文件名是否匹配过滤条件。
     *
     * @param fileName          文件名
     * @param patterns          文件模式列表（由 parseFilePatterns 产出）；非空时按该列表匹配
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
     * 遍历 startPath 收集所有普通文件。用 walkFileTree + preVisitDirectory 剪枝，
     * 命中排除目录时直接 SKIP_SUBTREE，不再对其后代做 walk 与逐文件过滤。
     *
     * @param startPath    搜索起始目录
     * @param excludedDirs 排除目录名列表
     * @return 文件路径列表
     * @throws IOException 遍历失败时抛出
     */
    public static List<Path> collectFiles(Path startPath,
                                          List<String> excludedDirs,
                                          List<String> excludedFiles) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(startPath)) return files;
        Files.walkFileTree(startPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(startPath)
                        && excludedDirs.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()
                        && !excludedFiles.contains(file.getFileName().toString())) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }
}
