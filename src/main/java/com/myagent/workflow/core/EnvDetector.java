package com.myagent.workflow.core;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 本机开发环境探测器。
 * <p>可独立运行：{@code java -cp app.jar com.myagent.workflow.core.EnvDetector out.properties}
 * <p>也可被 {@link AgentConfig} 在缺少配置文件时自动调用。
 */
public final class EnvDetector {

    public static final String CONFIG_FILE_NAME = "agent-config.properties";

    /** 全盘扫描最大深度（够覆盖 {@code I:\dev\toolchains\mingw64\bin} 之类） */
    private static final int MAX_SCAN_DEPTH = 5;

    /** 扫描时直接跳过的目录名（小写） */
    private static final Set<String> SKIP_DIRS = Set.of(
            "windows", "$recycle.bin", "system volume information",
            "winsxs", "assembly", "installer", "msocache",
            "node_modules", ".git", ".svn", ".hg",
            "temp", "tmp", "cache", ".cache",
            "onedrive", "dropbox", "appdata"
    );

    private EnvDetector() {}

    // =================================================================
    // 入口
    // =================================================================

    public static void main(String[] args) {
        Path out = Paths.get(args.length > 0 ? args[0] : CONFIG_FILE_NAME);
        try {
            Path written = detectAndWrite(out);
            System.out.println();
            System.out.println("✅ 已生成配置：" + written.toAbsolutePath());
            System.out.println("   可按需编辑该文件后重新启动。");
        } catch (Exception e) {
            System.err.println("❌ 环境探测失败：" + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** 探测并写出配置文件，返回写入路径 */
    public static Path detectAndWrite(Path out) throws IOException {
        System.out.println("🔍 正在探测本机开发环境 ...");
        long t0 = System.currentTimeMillis();
        Map<String, String> env = collect();
        printSummary(env, System.currentTimeMillis() - t0);

        String text = render(env);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.writeString(out, text,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return out;
    }

    // =================================================================
    // 收集
    // =================================================================

    public static Map<String, String> collect() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("javaHome",          findJavaHome());
        m.put("mavenCommand",      findMaven());
        m.put("pythonInterpreter", findPython());
        m.put("nodeInterpreter",   findNode());
        m.put("mingwCompiler",     findMinGW());

        MsvcPaths msvc = findMsvc();
        m.put("msvcCompiler", msvc.compiler());
        m.put("msvcInclude",  msvc.include());
        m.put("msvcLib",      msvc.lib());

        String cppType;
        if (!m.get("mingwCompiler").isEmpty() && !msvc.compiler().isEmpty()) cppType = "mingw";
        else if (!msvc.compiler().isEmpty()) cppType = "msvc";
        else if (!m.get("mingwCompiler").isEmpty()) cppType = "mingw";
        else cppType = "mingw";
        m.put("cppCompilerType", cppType);
        return m;
    }

    private static void printSummary(Map<String, String> env, long elapsedMs) {
        System.out.println();
        for (String key : new String[]{
                "javaHome", "mavenCommand", "pythonInterpreter",
                "nodeInterpreter", "cppCompilerType", "mingwCompiler", "msvcCompiler"}) {
            String v = env.get(key);
            System.out.printf("  %-18s : %s%n", key, v == null || v.isEmpty() ? "(未找到)" : v);
        }
        System.out.printf("  %-18s : %d ms%n", "耗时", elapsedMs);
        System.out.println();
    }

    // =================================================================
    // Java Home
    // =================================================================

    private static String findJavaHome() {
        String jh = System.getenv("JAVA_HOME");
        if (jh != null && !jh.isBlank() && Files.isDirectory(Paths.get(jh))) return normalize(jh);

        String pf  = envOr("ProgramFiles", "C:\\Program Files");
        String pf86 = envOr("ProgramFiles(x86)", "C:\\Program Files (x86)");
        String localApp = System.getenv("LOCALAPPDATA");
        String home = userHome();

        String[] patterns = {
                pf  + "\\Java\\jdk*",
                pf  + "\\Eclipse Adoptium\\jdk*",
                pf  + "\\Microsoft\\jdk*",
                pf  + "\\Amazon Corretto\\jdk*",
                pf  + "\\Zulu\\zulu*",
                pf  + "\\BellSoft\\LibericaJDK*",
                pf  + "\\Semeru\\jdk*",
                pf  + "\\SapMachine\\jdk*",
                pf  + "\\RedHat\\java-*",
                pf  + "\\GraalVM\\graalvm-*",
                pf86 + "\\Java\\jdk*",
                localApp == null ? null : localApp + "\\Programs\\Eclipse Adoptium\\jdk*",
                home + "\\scoop\\apps\\openjdk*\\current",
                home + "\\scoop\\apps\\temurin*\\current",
                "C:\\tools\\jdk*",
        };
        for (String pat : patterns) {
            if (pat == null) continue;
            String p = latestDir(pat);
            if (p != null && Files.isDirectory(Paths.get(p))) return normalize(p);
        }

        // 反推：java.exe 在 <HOME>\bin\java.exe
        String java = which("java.exe", "java");
        if (java != null) {
            Path bin = Paths.get(java).getParent();
            if (bin != null && bin.getParent() != null) return normalize(bin.getParent().toString());
        }
        return "";
    }

    // =================================================================
    // Maven
    // =================================================================

    private static String findMaven() {
        // 1) PATH
        String p = which("mvn.cmd", "mvn.bat", "mvn");
        if (p != null) return normalize(p);

        // 2) Maven 官方装法：%MAVEN_HOME%\bin\mvn.cmd
        String mh = System.getenv("MAVEN_HOME");
        if (mh == null) mh = System.getenv("M2_HOME");
        if (mh != null && !mh.isBlank()) {
            for (String exe : new String[]{"mvn.cmd", "mvn.bat", "mvn"}) {
                Path c = Paths.get(mh, "bin", exe);
                if (Files.isRegularFile(c)) return normalize(c.toString());
            }
        }

        // 3) 常见安装位置
        String pf = envOr("ProgramFiles", "C:\\Program Files");
        String localApp = System.getenv("LOCALAPPDATA");
        String home = userHome();
        String[] patterns = {
                // IDEA 自带
                pf + "\\JetBrains\\*\\plugins\\maven\\lib\\maven3\\bin\\mvn.cmd",
                localApp == null ? null : localApp + "\\JetBrains\\*\\plugins\\maven\\lib\\maven3\\bin\\mvn.cmd",
                home + "\\AppData\\Local\\JetBrains\\*\\plugins\\maven\\lib\\maven3\\bin\\mvn.cmd",
                // Eclipse 自带
                pf + "\\Eclipse Adoptium\\*\\plugins\\*\\maven*\\bin\\mvn.cmd",
                // 独立 Maven
                pf + "\\apache-maven-*\\bin\\mvn.cmd",
                "C:\\apache-maven-*\\bin\\mvn.cmd",
                "C:\\tools\\apache-maven-*\\bin\\mvn.cmd",
                home + "\\scoop\\apps\\maven\\current\\bin\\mvn.cmd",
                home + "\\scoop\\apps\\maven\\*\\bin\\mvn.cmd",
                // Chocolatey
                "C:\\ProgramData\\chocolatey\\bin\\mvn.cmd",
                "C:\\ProgramData\\chocolatey\\lib\\maven\\apache-maven-*\\bin\\mvn.cmd",
        };
        for (String pat : patterns) {
            if (pat == null) continue;
            String f = latestFile(pat);
            if (f != null) return normalize(f);
        }
        return "";
    }

    // =================================================================
    // Python
    // =================================================================

    private static String findPython() {
        // 1) PATH
        String p = which("python.exe", "python3.exe", "python", "python3");
        if (p != null) return normalize(p);

        // 2) py launcher 能告诉我们路径
        String viaPy = pythonViaPyLauncher();
        if (viaPy != null) return viaPy;

        // 3) 常见安装位置
        String home = userHome();
        String localApp = System.getenv("LOCALAPPDATA");
        String programData = System.getenv("ProgramData");
        String[] patterns = {
                localApp == null ? null : localApp + "\\Programs\\Python\\Python*\\python.exe",
                localApp == null ? null : localApp + "\\Python\\bin\\python.exe",
                localApp == null ? null : localApp + "\\Microsoft\\WindowsApps\\python.exe",
                home + "\\AppData\\Local\\Programs\\Python\\Python*\\python.exe",
                home + "\\scoop\\apps\\python\\current\\python.exe",
                home + "\\scoop\\apps\\python*\\current\\python.exe",
                "C:\\Python*\\python.exe",
                "C:\\Python*\\python.exe",
                "C:\\Program Files\\Python*\\python.exe",
                programData == null ? null : programData + "\\Anaconda3\\python.exe",
                home + "\\Anaconda3\\python.exe",
                home + "\\miniconda3\\python.exe",
        };
        for (String pat : patterns) {
            if (pat == null) continue;
            String f = latestFile(pat);
            if (f != null) return normalize(f);
        }
        return "";
    }

    /** 通过 Windows 的 py launcher 找到真正的 python 路径 */
    private static String pythonViaPyLauncher() {
        String py = which("py.exe", "py");
        if (py == null) return null;
        try {
            Process proc = new ProcessBuilder(py, "-c",
                    "import sys; print(sys.executable)")
                    .redirectErrorStream(true).start();
            String out = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor();
            if (!out.isEmpty() && Files.isRegularFile(Paths.get(out))) return normalize(out);
        } catch (Exception ignored) {}
        return null;
    }

    // =================================================================
    // Node
    // =================================================================

    private static String findNode() {
        String p = which("node.exe", "node");
        if (p != null) return normalize(p);

        String pf  = envOr("ProgramFiles", "C:\\Program Files");
        String pf86 = envOr("ProgramFiles(x86)", "C:\\Program Files (x86)");
        String home = userHome();
        String localApp = System.getenv("LOCALAPPDATA");

        String[] candidates = {
                pf + "\\nodejs\\node.exe",
                pf86 + "\\nodejs\\node.exe",
                localApp == null ? null : localApp + "\\Programs\\nodejs\\node.exe",
                home + "\\scoop\\apps\\nodejs\\current\\node.exe",
                "C:\\ProgramData\\chocolatey\\bin\\node.exe",
                "C:\\ProgramData\\chocolatey\\lib\\nodejs\\tools\\node.exe",
                home + "\\AppData\\Roaming\\nvm\\*\\node.exe",
                "C:\\nvm4w\\nodejs\\node.exe",
        };
        for (String c : candidates) {
            if (c == null) continue;
            String f = latestFile(c);
            if (f != null) return normalize(f);
        }
        return "";
    }

    // =================================================================
    // MinGW —— 分层探测
    // =================================================================

    private static String findMinGW() {
        // 1) PATH
        String p = which(
                "g++.exe", "g++",
                "x86_64-w64-mingw32-g++.exe",
                "i686-w64-mingw32-g++.exe",
                "aarch64-w64-mingw32-g++.exe");
        if (p != null) return normalize(p);

        // 2) 由 gcc 反推
        String gcc = which(
                "gcc.exe", "gcc",
                "x86_64-w64-mingw32-gcc.exe",
                "i686-w64-mingw32-gcc.exe",
                "aarch64-w64-mingw32-gcc.exe");
        if (gcc != null) {
            String gpp = gcc.replaceAll("gcc(\\.exe)?$", "g++$1");
            if (Files.isRegularFile(Paths.get(gpp))) return normalize(gpp);
        }

        // 3) 注册表
        for (Path dir : registryCandidates()) {
            String hit = probeBin(dir);
            if (hit != null) return hit;
        }

        // 4) 全盘扫描
        for (Path root : scanRoots()) {
            String hit = scanForGpp(root, MAX_SCAN_DEPTH);
            if (hit != null) return hit;
        }
        return "";
    }

    /** 在 dir/bin/ 里找 g++ */
    private static String probeBin(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return null;
        Path bin = dir.resolve("bin");
        if (!Files.isDirectory(bin)) return null;
        return findGppIn(bin);
    }

    /** 在给定 bin 目录里匹配 g++（含带前缀的变体） */
    private static String findGppIn(Path binDir) {
        String[] exact = {
                "g++.exe",
                "x86_64-w64-mingw32-g++.exe",
                "i686-w64-mingw32-g++.exe",
                "aarch64-w64-mingw32-g++.exe",
        };
        for (String name : exact) {
            Path f = binDir.resolve(name);
            if (Files.isRegularFile(f)) return normalize(f.toString());
        }
        try (Stream<Path> s = Files.list(binDir)) {
            return s.filter(p -> p.getFileName().toString().matches(".*g\\+\\+\\.exe"))
                    .findFirst()
                    .map(p -> normalize(p.toString()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    // =================================================================
    // 注册表探测
    // =================================================================

    private static List<Path> registryCandidates() {
        List<Path> out = new ArrayList<>();
        String[] roots = {
                "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
                "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
                "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
        };
        String[] keywords = {
                "mingw", "msys2", "tdm-gcc", "cygwin",
                "strawberry", "w64devkit", "llvm-mingw",
                "winlibs", "mingw-w64"
        };

        for (String root : roots) {
            String text = runRegQuery(root);
            if (text == null || text.isEmpty()) continue;
            parseUninstallReg(text, keywords, out);
        }
        return out;
    }

    private static String runRegQuery(String key) {
        try {
            Process p = new ProcessBuilder("reg", "query", key, "/s")
                    .redirectErrorStream(true).start();
            byte[] bytes = p.getInputStream().readAllBytes();
            p.waitFor();
            // 中文 Windows 的 reg 输出是 GBK/CP936，用默认编码最稳
            return new String(bytes, Charset.defaultCharset());
        } catch (Exception e) {
            return null;
        }
    }

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
            // MSYS2 类：C:\msys64 -> C:\msys64\mingw64 等
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

    // =================================================================
    // 全盘扫描
    // =================================================================

    private static List<Path> scanRoots() {
        List<Path> roots = new ArrayList<>();
        // 所有盘符
        for (File f : File.listRoots()) {
            Path p = f.toPath();
            if (Files.exists(p)) roots.add(p);
        }
        // 常见开发目录（即便在盘符根之外也补上，虽然上面已覆盖）
        String home = userHome();
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

    private static String scanForGpp(Path root, int maxDepth) {
        final String[] result = {null};
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), maxDepth,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir,
                                                                 BasicFileAttributes attrs) {
                            Path nameP = dir.getFileName();
                            String name = nameP == null ? ""
                                    : nameP.toString().toLowerCase(Locale.ROOT);
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

    // =================================================================
    // MSVC
    // =================================================================

    private record MsvcPaths(String compiler, String include, String lib) {}

    private static MsvcPaths findMsvc() {
        String vsRoot = findVsRoot();
        if (vsRoot == null) return new MsvcPaths("", "", "");

        String msvc = latestDir(vsRoot + "\\VC\\Tools\\MSVC\\*");
        if (msvc == null) return new MsvcPaths("", "", "");

        String cl = firstFile(
                msvc + "\\bin\\Hostx86\\x86\\cl.exe",
                msvc + "\\bin\\Hostx64\\x64\\cl.exe");
        if (cl == null) cl = latestFile(msvc + "\\bin\\Host*\\*\\cl.exe");

        List<String> inc = new ArrayList<>();
        List<String> lib = new ArrayList<>();
        addDirIfExists(inc, msvc + "\\include");
        addDirIfExists(inc, msvc + "\\ATLMFC\\include");
        addDirIfExists(inc, vsRoot + "\\VC\\Auxiliary\\VS\\include");
        addDirIfExists(lib, msvc + "\\ATLMFC\\lib\\x86");
        addDirIfExists(lib, msvc + "\\lib\\x86");

        for (String sdkBase : new String[]{
                "C:\\Program Files (x86)\\Windows Kits\\10",
                "C:\\Program Files\\Windows Kits\\10"}) {
            if (!Files.isDirectory(Paths.get(sdkBase))) continue;

            String sdkInc = latestDir(sdkBase + "\\Include\\*");
            String sdkLib = latestDir(sdkBase + "\\Lib\\*");
            if (sdkInc != null) {
                for (String sub : new String[]{"ucrt", "um", "shared", "winrt", "cppwinrt"})
                    addDirIfExists(inc, sdkInc + "\\" + sub);
            }
            if (sdkLib != null) {
                String netfx = latestDir(sdkBase + "\\NETFXSDK\\*\\lib\\um\\x86");
                if (netfx != null) addDirIfExists(lib, netfx);
                addDirIfExists(lib, sdkLib + "\\ucrt\\x86");
                addDirIfExists(lib, sdkLib + "\\um\\x86");
            }
            String netfxInc = latestDir(sdkBase + "\\NETFXSDK\\*\\include\\um");
            if (netfxInc != null) addDirIfExists(inc, netfxInc);
            break;
        }

        return new MsvcPaths(
                normalize(cl),
                normalize(String.join(";", inc)),
                normalize(String.join(";", lib)));
    }

    private static String findVsRoot() {
        String vswhere = "C:\\Program Files (x86)\\Microsoft Visual Studio\\Installer\\vswhere.exe";
        if (Files.isRegularFile(Paths.get(vswhere))) {
            try {
                Process proc = new ProcessBuilder(
                        vswhere, "-latest", "-products", "*",
                        "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
                        "-property", "installationPath")
                        .redirectErrorStream(true).start();
                String out = new String(proc.getInputStream().readAllBytes()).trim();
                proc.waitFor();
                if (!out.isEmpty() && Files.isDirectory(Paths.get(out))) return out;
            } catch (Exception ignored) {}
        }
        for (String base : new String[]{
                "C:\\Program Files\\Microsoft Visual Studio",
                "C:\\Program Files (x86)\\Microsoft Visual Studio"}) {
            String cand = latestDir(base + "\\*\\*\\*");
            if (cand != null && Files.isDirectory(Paths.get(cand, "VC", "Tools", "MSVC")))
                return cand;
        }
        return null;
    }

    // =================================================================
    // 工具方法
    // =================================================================

    private static String userHome() { return System.getProperty("user.home"); }

    private static String envOr(String name, String def) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? def : v;
    }

    private static String normalize(String p) {
        if (p == null || p.isEmpty()) return "";
        return p.replace('\\', '/');
    }

    private static void addDirIfExists(List<String> list, String p) {
        if (p != null && Files.isDirectory(Paths.get(p))) list.add(p);
    }

    /** PATH 探测：按 PATHEXT 依次尝试可执行文件后缀 */
    private static String which(String... names) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        String[] exts = extList();

        for (String dir : pathEnv.split(";")) {
            if (dir.isBlank()) continue;
            String dirTrim = dir.trim().replace("\"", "");
            Path base = Paths.get(dirTrim);
            if (!Files.isDirectory(base)) continue;

            for (String n : names) {
                // 完整名字直接试
                Path direct = base.resolve(n);
                if (Files.isRegularFile(direct)) return direct.toString();
                // 无扩展名时补 PATHEXT
                if (!n.contains(".")) {
                    for (String ext : exts) {
                        Path c = base.resolve(n + ext);
                        if (Files.isRegularFile(c)) return c.toString();
                    }
                }
            }
        }
        return null;
    }

    private static String[] extList() {
        String ext = System.getenv("PATHEXT");
        if (ext == null || ext.isBlank()) return new String[]{".exe", ".cmd", ".bat"};
        return Arrays.stream(ext.split(";"))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    private static String firstFile(String... paths) {
        for (String p : paths) if (p != null && Files.isRegularFile(Paths.get(p))) return p;
        return null;
    }

    private static String latestDir(String glob)  { return lastOrNull(glob(glob, true)); }
    private static String latestFile(String glob) { return lastOrNull(glob(glob, false)); }

    private static String lastOrNull(List<Path> list) {
        return list.isEmpty() ? null : list.get(list.size() - 1).toString();
    }

    /** 简易 glob：处理路径中的 {@code *}（只做目录名/文件名一级匹配） */
    private static List<Path> glob(String pattern, boolean dirOnly) {
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

    // =================================================================
    // 渲染 properties
    // =================================================================

    private static String esc(String v) {
        if (v == null || v.isEmpty()) return "";
        return v.replace("\\", "/").replace(":", "\\:").replace("=", "\\=");
    }

    private static String render(Map<String, String> env) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        StringBuilder sb = new StringBuilder();
        sb.append("# ============================================================\n");
        sb.append("# Agent Workflow 本机环境配置\n");
        sb.append("# 由 EnvDetector 自动生成（").append(ts).append("）\n");
        sb.append("# 修改后重启程序即可生效\n");
        sb.append("# ============================================================\n\n");

        sb.append("# ---------- LLM ----------\n");
        sb.append("# 留空则读取环境变量 DEEPSEEK_API_KEY\n");
        sb.append("agent.apiKey=\n");
        sb.append("agent.model=deepseek-v4-flash\n");
        sb.append("agent.autoOpenBrowser=false\n\n");

        sb.append("# ---------- 安全 / 压缩 / 检查点 ----------\n");
        sb.append("agent.enableSecurityScan=true\n");
        sb.append("agent.enableCompression=true\n");
        sb.append("agent.checkpointMinInterval=5\n");
        sb.append("agent.checkpointMaxInterval=15\n\n");

        sb.append("# ---------- 本机工具链路径 ----------\n");
        sb.append("env.mavenCommand=").append(esc(env.get("mavenCommand"))).append('\n');
        sb.append("env.javaHome=").append(esc(env.get("javaHome"))).append('\n');
        sb.append("env.pythonInterpreter=").append(esc(env.get("pythonInterpreter"))).append('\n');
        sb.append("env.nodeInterpreter=").append(esc(env.get("nodeInterpreter"))).append('\n');
        sb.append('\n');
        sb.append("env.cppCompilerType=").append(env.get("cppCompilerType")).append('\n');
        sb.append("env.mingwCompiler=").append(esc(env.get("mingwCompiler"))).append('\n');
        sb.append("env.msvcCompiler=").append(esc(env.get("msvcCompiler"))).append('\n');
        sb.append("env.msvcInclude=").append(esc(env.get("msvcInclude"))).append('\n');
        sb.append("env.msvcLib=").append(esc(env.get("msvcLib"))).append('\n');
        return sb.toString();
    }
}