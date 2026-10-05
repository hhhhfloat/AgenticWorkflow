// @anchor: windowsRegistryReader_tot_desc
// Windows 卸载注册表读取：筛选含关键词的安装目录
package com.myagent.workflow.core.config.env;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// @anchor: windowsRegistryReader_class
public final class WindowsRegistryReader {
    private WindowsRegistryReader() {}

    // @anchor: windowsRegistryReader_findUninstallLocations
    // 扫描三个卸载注册表根，按关键词筛选出安装目录
    public static List<Path> findUninstallLocations(String[] keywords) {
        List<Path> out = new ArrayList<>();
        String[] roots = {
                "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
                "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
                "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
        };
        for (String root : roots) {
            String text = runRegQuery(root);
            if (text == null || text.isEmpty()) continue;
            parseUninstallReg(text, keywords, out);
        }
        return out;
    }

    // @anchor: windowsRegistryReader_runRegQuery
    // 调用 reg query 递归读取注册表键；带 5 秒超时（原实现无超时，可能启动期挂起）
    private static String runRegQuery(String key) {
        try {
            return CommandRunner.run(5, "reg", "query", key, "/s");
        } catch (Exception e) {
            return null;
        }
    }

    // @anchor: windowsRegistryReader_parseUninstallReg
    private static void parseUninstallReg(String text, String[] keywords, List<Path> out) {
        String[] blocks = text.split("(?=HKEY_)");
        for (String block : blocks) {
            String lower = block.toLowerCase(Locale.ROOT);
            boolean matched = false;
            for (String kw : keywords) {
                if (lower.contains(kw)) { matched = true; break; }
            }
            if (!matched) continue;

            String loc = regValue(block, "InstallLocation");
            if (loc == null || loc.isBlank()) {
                String un = regValue(block, "UninstallString");
                if (un != null && !un.isBlank()) {
                    loc = un.replace("\"", "").trim();
                    int idx = loc.lastIndexOf('\\');
                    if (idx > 0) loc = loc.substring(0, idx);
                }
            }
            if (loc == null || loc.isBlank()) continue;

            Path base = Paths.get(loc);
            out.add(base);
            for (String sub : new String[]{
                    "mingw64", "mingw32", "ucrt64", "clang64", "clangarm64"}) {
                Path p = base.resolve(sub);
                if (Files.isDirectory(p)) out.add(p);
            }
        }
    }

    private static String regValue(String block, String name) {
        String prefix = name.toLowerCase(Locale.ROOT);
        for (String line : block.split("\\r?\\n")) {
            String t = line.trim();
            if (t.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                String[] parts = t.split("\\s{2,}", 3);
                if (parts.length >= 3) return parts[2].trim();
            }
        }
        return null;
    }
}