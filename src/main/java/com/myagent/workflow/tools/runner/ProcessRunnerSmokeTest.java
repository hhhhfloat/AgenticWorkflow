package com.myagent.workflow.tools.runner;

import com.myagent.workflow.core.config.AgentConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ProcessRunnerSmokeTest {

    public static void main(String[] args) throws Exception {
        // 与 ProcessRunner 内部使用同一个 sandbox 根，避免路径不一致
        Path tmp = Paths.get(AgentConfig.getSandboxDir(), "tmp").toAbsolutePath().normalize();
        Files.createDirectories(tmp);

        Path src = tmp.resolve("BigOutput.java");
        Files.writeString(src, """
        public class BigOutput {
            public static void main(String[] args) {
                int n = args.length > 0 ? Integer.parseInt(args[0]) : 100;
                for (int i = 0; i < n; i++) {
                    System.out.println("line-" + i + "-ascii-only-padding-padding-padding");
                }
                System.err.println("=== STDERR_MARKER redirectErrorStream check ===");
                System.out.println("=== LAST_STDOUT_LINE tail check ===");
            }
        }
        """, StandardCharsets.UTF_8);

        // 准备：编译 BigOutput（这一步不走 ProcessRunner，只是造测试文件）
        ProcessBuilder javacPb = new ProcessBuilder("javac", "-d", tmp.toString(), src.toString());
        javacPb.directory(tmp.toFile());
        Process javac = javacPb.start();
        javac.waitFor();
        if (javac.exitValue() != 0) {
            System.out.println("❌ javac 失败，无法继续");
            return;
        }

        ProcessRunner runner = new ProcessRunner();

        System.out.println("========== 测试 1：小输出（100 行）==========");
        ProcessBuilder smallPb = new ProcessBuilder("java", "-cp", tmp.toString(), "BigOutput", "100");
        smallPb.directory(tmp.toFile());
        ProcessRunner.ProcessResult small = runner.executeProcess(smallPb, 30, false);
        report("小输出", small, false);

        System.out.println("========== 测试 2：大输出（10 万行，约 7MB）==========");
        ProcessBuilder bigPb = new ProcessBuilder("java", "-cp", tmp.toString(), "BigOutput", "100000");
        bigPb.directory(tmp.toFile());
        ProcessRunner.ProcessResult big = runner.executeProcess(bigPb, 60, false);
        report("大输出", big, true);
    }

    private static void report(String name, ProcessRunner.ProcessResult r, boolean expectTruncate) {
        String out = r.output();
        System.out.println("[" + name + "] exitCode=" + r.exitCode()
                + ", timedOut=" + r.timedOut()
                + ", stalled=" + r.stalledOnInput());
        System.out.println("[ " + name + "] output 长度 = " + out.length());
        System.out.println("[ " + name + "] 含乱码 U+FFFD = " + out.contains("\uFFFD"));
        System.out.println("[" + name + "] 含 stderr 标记 = " + out.contains("STDERR_MARKER"));
        System.out.println("[" + name + "] 含最后一行 stdout = " + out.contains("LAST_STDOUT_LINE"));
        System.out.println("[ " + name + "] 含截断标记 = " + out.contains("后续内容已截断"));
        System.out.println();
    }
}