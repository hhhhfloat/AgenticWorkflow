// @anchor: globUtil_tot_desc
// 简易单级 glob 匹配 + 版本号排序
package com.myagent.workflow.core.config.env;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// @anchor: globUtil_class
public final class GlobUtil {
    private GlobUtil() {}

    // @anchor: globUtil_latestDir
    public static String latestDir(String glob)  { return lastOrNull(glob(glob, true)); }

    // @anchor: globUtil_latestFile
    public static String latestFile(String glob) { return lastOrNull(glob(glob, false)); }

    // @anchor: globUtil_lastOrNull
    public static String lastOrNull(List<Path> list) {
        return list.isEmpty() ? null : list.get(list.size() - 1).toString();
    }

    // @anchor: globUtil_firstFile
    public static String firstFile(String... paths) {
        for (String p : paths)
            if (p != null && Files.isRegularFile(Paths.get(p))) return p;
        return null;
    }

    // @anchor: globUtil_glob
    // 简易 glob：处理路径中的 *（只做目录名/文件名一级匹配）
    public static List<Path> glob(String pattern, boolean dirOnly) {
        List<Path> results = new ArrayList<>();
        int starIdx = pattern.indexOf('*');
        if (starIdx < 0) {
            Path p = Paths.get(pattern);
            if (dirOnly ? Files.isDirectory(p) : Files.isRegularFile(p)) results.add(p);
            return results;
        }
        int sepIdx = Math.max(pattern.lastIndexOf('\\', starIdx), pattern.lastIndexOf('/', starIdx));
        if (sepIdx < 0) return results;

        Path baseDir = Paths.get(pattern.substring(0, sepIdx));
        if (!Files.isDirectory(baseDir)) return results;

        String rest = pattern.substring(sepIdx + 1);
        int nextSep = Math.max(rest.indexOf('\\'), rest.indexOf('/'));
        String token = nextSep < 0 ? rest : rest.substring(0, nextSep);
        String tail  = nextSep < 0 ? null : rest.substring(nextSep + 1);
        String regex = token.replace(".", "\\.").replace("*", ".*");

        try (Stream<Path> s = Files.list(baseDir)) {
            List<Path> children = s
                    .filter(p -> p.getFileName().toString().matches(regex))
                    .sorted((a, b) -> compareVersions(
                            a.getFileName().toString(), b.getFileName().toString()))
                    .collect(Collectors.toList());
            for (Path child : children) {
                if (tail == null) {
                    if (dirOnly ? Files.isDirectory(child) : Files.isRegularFile(child))
                        results.add(child);
                } else {
                    results.addAll(glob(child + "\\" + tail, dirOnly));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return results;
    }

    // @anchor: globUtil_compareVersions
    private static int compareVersions(String a, String b) {
        int[] na = numbers(a), nb = numbers(b);
        int n = Math.max(na.length, nb.length);
        for (int i = 0; i < n; i++) {
            int x = i < na.length ? na[i] : 0;
            int y = i < nb.length ? nb[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return a.compareTo(b);
    }

    private static int[] numbers(String s) {
        return Arrays.stream(s.split("[^0-9]+"))
                .filter(t -> !t.isEmpty())
                .mapToInt(t -> { try { return Integer.parseInt(t); } catch (Exception e) { return 0; } })
                .toArray();
    }
}