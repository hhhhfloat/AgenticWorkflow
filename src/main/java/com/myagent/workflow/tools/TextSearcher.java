package com.myagent.workflow.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

// @anchor: textSearcher_class
/**
 * 全文正则搜索模块：负责 searchText 工具的逻辑。
 * 文件收集 / 排除目录 / 扩展名过滤复用 SearchFileFilter 基础设施。
 */
public class TextSearcher {
    private static final Logger logger = LoggerFactory.getLogger(TextSearcher.class);

    private static final List<String> EXCLUDED_DIRS =
            Arrays.asList("target", "build", ".git", ".idea", "node_modules");
    private static final List<String> TEXT_EXTENSIONS = Arrays.asList(
            ".java", ".html", ".htm", ".css", ".js", ".jsx", ".ts", ".tsx",
            ".txt", ".xml", ".json", ".md", ".properties", ".yml", ".yaml",
            ".sh", ".bat", ".gradle", ".sql");

    // @anchor: textSearcher_searchText
    String searchText(String keyword, String filePattern, String path) {
        try {
            Path startPath = PathUtils.safeResolve(path);
            if (!Files.exists(startPath) || !Files.isDirectory(startPath)) {
                return "路径不存在或不是目录: " + path;
            }

            Pattern pattern;
            try {
                pattern = Pattern.compile(keyword);
            } catch (PatternSyntaxException e) {
                return "关键词正则表达式错误: " + e.getMessage() + "。如需普通文本搜索，请转义特殊字符。";
            }

            List<String> patterns = SearchFileFilter.parseFilePatterns(filePattern);

            AtomicInteger matchCount = new AtomicInteger(0);
            AtomicInteger resultCount = new AtomicInteger(0);
            List<String> results = Collections.synchronizedList(new ArrayList<>());
            final int MAX_RESULTS = 30;

            List<Path> files = SearchFileFilter.collectFiles(startPath, EXCLUDED_DIRS);
            for (Path file : files) {
                if (resultCount.get() >= MAX_RESULTS) break;
                try {
                    String fileName = file.getFileName().toString();
                    if (!SearchFileFilter.matchesExtension(fileName, patterns, TEXT_EXTENSIONS)) continue;

                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    String relPathStr = startPath.relativize(file).toString().replace('\\', '/');
                    int lineNum = 0;
                    for (String line : lines) {
                        lineNum++;
                        if (resultCount.get() >= MAX_RESULTS) break;
                        if (pattern.matcher(line).find()) {
                            String preview = line.trim();
                            if (preview.length() > 80) preview = preview.substring(0, 80) + "...";
                            results.add(relPathStr + ":" + lineNum + ":" + preview);
                            matchCount.incrementAndGet();
                            resultCount.incrementAndGet();
                        }
                    }
                } catch (IOException ignored) {}
            }

            if (results.isEmpty()) {
                return "🔍 未找到匹配 \"" + keyword + "\" 的内容。";
            }

            StringBuilder sb = new StringBuilder();
            int total = matchCount.get();
            int shown = results.size();
            sb.append("🔍 找到 ").append(total).append(" 条匹配结果");
            if (total > shown) sb.append("（仅显示前 ").append(MAX_RESULTS).append(" 条）");
            sb.append("：\n");
            for (String r : results) sb.append(r).append("\n");
            return sb.toString();

        } catch (IOException e) {
            logger.error("搜索失败", e);
            return "搜索失败: " + e.getMessage();
        }
    }
}
