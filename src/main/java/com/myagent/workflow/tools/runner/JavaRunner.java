// @anchor: javaRunner_tot_desc
// 单文件 Java 编译运行：javac 编译到 classes/，再 java -cp 启动
package com.myagent.workflow.tools.runner;

import com.myagent.workflow.core.config.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

// @anchor: javaRunner_class
// Java 单文件运行器：编译到 classes/ 后从 classpath 启动
public class JavaRunner {

    private static final Logger logger = LoggerFactory.getLogger(JavaRunner.class);
    private static final Charset PROCESS_CHARSET = StandardCharsets.UTF_8;

    private final ProcessRunner processRunner;

    // @anchor: javaRunner_constructor
    public JavaRunner(AgentConfig config, ProcessRunner processRunner) {
        this.processRunner = processRunner;
    }

    // @anchor: javaRunner_compileJava
    // 编译并运行单个 Java 文件
    public String compileJava(Path filePath, String filename, boolean run) {
        try {
            Path projectDir = filePath.getParent();
            Path classesDir = projectDir.resolve("classes");
            Files.createDirectories(classesDir);

            ProcessBuilder compilePb = new ProcessBuilder(
                    "javac", "-d", classesDir.toString(), filePath.toString());
            compilePb.directory(projectDir.toFile());
            compilePb.redirectErrorStream(true);

            Process compileProc = compilePb.start();
            boolean compileFinished = compileProc.waitFor(60, TimeUnit.SECONDS);
            if (!compileFinished) {
                ProcessRunner.killTree(compileProc);
            }
            String compileOutput = new String(compileProc.getInputStream().readAllBytes(), PROCESS_CHARSET);
            if (!compileFinished) {
                return "⏱️ Java 编译超时（60秒），已强制终止。\n输出:\n" + compileOutput;
            }
            int compileExit = compileProc.exitValue();
            if (compileExit != 0) {
                return "编译失败 (退出码 " + compileExit + "):\n" + compileOutput;
            }

            if (!run) {
                return "✅ 编译成功（未运行）！\n输出:\n" + compileOutput;
            }

            String className = resolveClassName(filePath);
            ProcessBuilder runPb = new ProcessBuilder(
                    "java", "-cp", classesDir.toString(), className);
            runPb.directory(projectDir.toFile());
            runPb.redirectErrorStream(true);

            ProcessRunner.ProcessResult result = processRunner.executeProcess(runPb, 30, true);

            if (result.stalledOnInput()) {
                return "⛔ 运行阻塞（疑似等待标准输入）: \n" + result.output() +
                        "\n💡 建议：请在代码中内置重定向输入（如使用文件流替代 System.in），或设置 run=false 仅编译。";
            }
            if (result.timedOut()) {
                return "⏱️ 运行超时（超过30秒），已强制终止。\n输出:\n" + result.output();
            }
            if (result.exitCode() != 0) {
                return "运行失败 (退出码 " + result.exitCode() + "):\n" + result.output();
            }
            return "运行成功！\n输出:\n" + result.output();

        } catch (IOException | InterruptedException e) {
            return "单文件 Java 执行异常: " + e.getMessage();
        }
    }

    // @anchor: javaRunner_resolveClassName
    // 从源文件推导全限定类名：优先读 package 声明，无则用文件名
    private String resolveClassName(Path filePath) throws IOException {
        String baseName = filePath.getFileName().toString();
        if (baseName.endsWith(".java")) {
            baseName = baseName.substring(0, baseName.length() - ".java".length());
        }

        List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) continue;
            if (t.startsWith("package ")) {
                String pkg = t.substring("package ".length()).replace(";", "").trim();
                if (!pkg.isEmpty()) return pkg + "." + baseName;
            }
            break;
        }
        return baseName;
    }
}