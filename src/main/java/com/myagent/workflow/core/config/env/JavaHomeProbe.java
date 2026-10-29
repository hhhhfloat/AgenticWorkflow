// @anchor: javaHomeProbe_intro
// JDK 根目录探测：环境变量 → 运行时 java.home → 平台常见安装位置 → 反推
package com.myagent.workflow.core.config.env;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

// @anchor: javaHomeProbe_class
public class JavaHomeProbe implements Probe {

    // @anchor: javaHomeProbe_detect
    @Override
    public Map<String, String> detect() {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("javaHome", findJavaHome());
        return r;
    }

    // @anchor: javaHomeProbe_findJavaHome
    private String findJavaHome() {
        // 1. 环境变量优先（用户显式指定）
        String jh = System.getenv("JAVA_HOME");
        if (jh != null && !jh.isBlank() && Files.isDirectory(Paths.get(jh)))
            return EnvUtil.normalize(jh);

        // 2. 运行时 java.home（最可靠，永远指向当前 JVM 的 JDK/JRE 根）
        String runtimeHome = System.getProperty("java.home");
        if (runtimeHome != null && !runtimeHome.isBlank()
                && Files.isDirectory(Paths.get(runtimeHome))) {
            Path p = Paths.get(runtimeHome);
            // JDK 8 及更早可能有 .../jre 子目录，回退到父级拿 JDK 根
            if ("jre".equals(p.getFileName().toString()) && p.getParent() != null) {
                p = p.getParent();
            }
            return EnvUtil.normalize(p.toString());
        }

        // 3. 平台相关常见安装位置
        if (Platform.isWindows()) {
            String pf    = EnvUtil.envOr("ProgramFiles", "C:\\Program Files");
            String pf86  = EnvUtil.envOr("ProgramFiles(x86)", "C:\\Program Files (x86)");
            String localApp = System.getenv("LOCALAPPDATA");
            String home = EnvUtil.userHome();

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
                String p = GlobUtil.latestDir(pat);
                if (p != null && Files.isDirectory(Paths.get(p))) return EnvUtil.normalize(p);
            }
        } else {
            // Linux / macOS 常见位置
            String userHome = System.getProperty("user.home", "");
            String[] patterns = {
                    "/usr/lib/jvm/*",
                    "/usr/lib64/jvm/*",
                    "/opt/jdk*",
                    "/opt/java*",
                    "/usr/local/lib/jvm/*",
                    userHome + "/.sdkman/candidates/java/*",
                    userHome + "/.gradle/jdks/*",
                    userHome + "/.asdf/installs/java/*",
            };
            for (String pat : patterns) {
                String p = GlobUtil.latestDir(pat);
                if (p != null && Files.isDirectory(Paths.get(p))) return EnvUtil.normalize(p);
            }
        }

        // 4. 最后兜底：反推 PATH 上的 java（仅 Windows 可靠；Linux 符号链接会得到 /usr 这种错值）
        if (Platform.isWindows()) {
            String java = ShellPathResolver.which("java.exe", "java");
            if (java != null) {
                Path bin = Paths.get(java).getParent();
                if (bin != null && bin.getParent() != null)
                    return EnvUtil.normalize(bin.getParent().toString());
            }
        }
        return "";
    }
}