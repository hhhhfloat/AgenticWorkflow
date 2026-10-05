// @anchor: mingwProbe_tot_desc
// MinGW 探测：PATH → 由 gcc 反推 → 注册表 → 全盘限深扫描
package com.myagent.workflow.core.config.env;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

// @anchor: mingwProbe_class
public class MinGwProbe implements Probe {

    private static final int MAX_SCAN_DEPTH = 5;

    private static final Set<String> SKIP_DIRS = Set.of(
            "windows", "$recycle.bin", "system volume information",
            "winsxs", "assembly", "installer", "msocache",
            "node_modules", ".git", ".svn", ".hg",
            "temp", "tmp", "cache", ".cache",
            "onedrive", "dropbox", "appdata"
    );

    @Override
    public Map<String, String> detect() {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("mingwCompiler", findMinGW());
        return r;
    }

    // @anchor: mingwProbe_findMinGW
    private String findMinGW() {
        String p = ShellPathResolver.which(
                "g++.exe", "g++",
                "x86_64-w64-mingw32-g++.exe",
                "i686-w64-mingw32-g++.exe",
                "aarch64-w64-mingw32-g++.exe");
        if (p != null) return EnvUtil.normalize(p);

        String gcc = ShellPathResolver.which(
                "gcc.exe", "gcc",
                "x86_64-w64-mingw32-gcc.exe",
                "i686-w64-mingw32-gcc.exe",
                "aarch64-w64-mingw32-gcc.exe");
        if (gcc != null) {
            String gpp = gcc.replaceAll("gcc(\\.exe)?$", "g++$1");
            if (Files.isRegularFile(Paths.get(gpp))) return EnvUtil.normalize(gpp);
        }

        String[] keywords = {
                "mingw", "msys2", "tdm-gcc", "cygwin",
                "strawberry", "w64devkit", "llvm-mingw",
                "winlibs", "mingw-w64"
        };
        for (Path dir : WindowsRegistryReader.findUninstallLocations(keywords)) {
            String hit = probeBin(dir);
            if (hit != null) return hit;
        }

        for (Path root : scanRoots()) {
            String hit = scanForGpp(root, MAX_SCAN_DEPTH);
            if (hit != null) return hit;
        }
        return "";
    }

    // @anchor: mingwProbe_probeBin
    private String probeBin(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return null;
        Path bin = dir.resolve("bin");
        if (!Files.isDirectory(bin)) return null;
        return findGppIn(bin);
    }

    // @anchor: mingwProbe_findGppIn
    private String findGppIn(Path binDir) {
        String[] exact = {
                "g++.exe",
                "x86_64-w64-mingw32-g++.exe",
                "i686-w64-mingw32-g++.exe",
                "aarch64-w64-mingw32-g++.exe",
        };
        for (String name : exact) {
            Path f = binDir.resolve(name);
            if (Files.isRegularFile(f)) return EnvUtil.normalize(f.toString());
        }
        try (var s = Files.list(binDir)) {
            return s.filter(p -> p.getFileName().toString().matches(".*g\\+\\+\\.exe"))
                    .findFirst()
                    .map(p -> EnvUtil.normalize(p.toString()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    // @anchor: mingwProbe_scanRoots
    private List<Path> scanRoots() {
        List<Path> roots = new ArrayList<>();
        for (File f : File.listRoots()) {
            Path p = f.toPath();
            if (Files.exists(p)) roots.add(p);
        }
        String home = EnvUtil.userHome();
        String localApp = System.getenv("LOCALAPPDATA");
        String programData = System.getenv("ProgramData");
        for (String s : new String[]{
                localApp == null ? null : localApp + "\\Programs",
                localApp == null ? null : localApp + "\\Microsoft\\WinGet\\Packages",
                home + "\\scoop\\apps",
                programData == null ? null : programData + "\\chocolatey",
        }) {
            if (s == null) continue;
            Path p = Paths.get(s);
            if (Files.isDirectory(p)) roots.add(p);
        }
        return roots;
    }

    // @anchor: mingwProbe_scanForGpp
    private String scanForGpp(Path root, int maxDepth) {
        final String[] result = {null};
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), maxDepth,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            Path nameP = dir.getFileName();
                            String name = nameP == null ? "" : nameP.toString().toLowerCase(Locale.ROOT);
                            if (SKIP_DIRS.contains(name)) return FileVisitResult.SKIP_SUBTREE;

                            Path bin = dir.resolve("bin");
                            if (Files.isDirectory(bin)) {
                                String hit = findGppIn(bin);
                                if (hit != null) {
                                    result[0] = hit;
                                    return FileVisitResult.TERMINATE;
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (Exception ignored) {}
        return result[0];
    }
}