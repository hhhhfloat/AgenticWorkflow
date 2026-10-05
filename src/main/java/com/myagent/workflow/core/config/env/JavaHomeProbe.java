// @anchor: javaHomeProbe_intro
// JDK 根目录探测：环境变量 → 常见发行版安装位置 → 由 java.exe 反推
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
        String jh = System.getenv("JAVA_HOME");
        if (jh != null && !jh.isBlank() && Files.isDirectory(Paths.get(jh)))
            return EnvUtil.normalize(jh);

        String pf   = EnvUtil.envOr("ProgramFiles", "C:\\Program Files");
        String pf86 = EnvUtil.envOr("ProgramFiles(x86)", "C:\\Program Files (x86)");
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

        String java = ShellPathResolver.which("java.exe", "java");
        if (java != null) {
            Path bin = Paths.get(java).getParent();
            if (bin != null && bin.getParent() != null) return EnvUtil.normalize(bin.getParent().toString());
        }
        return "";
    }
}