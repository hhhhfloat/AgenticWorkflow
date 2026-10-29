// @anchor: javaRunner_intro
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
import java.nio.file.Paths;
import java.util.List;

// @anchor: javaRunner_class
// Java 单文件运行器：编译到 classes/ 后从 classpath 启动
public class JavaRunner {

    private static final Logger logger = LoggerFactory.getLogger(JavaRunner.class);
    private static final Charset PROCESS_CHARSET = StandardCharsets.UTF_8;

    private final AgentConfig config;
    private final ProcessRunner processRunner;

    // @anchor: javaRunner_constructor
    public JavaRunner(AgentConfig config, ProcessRunner processRunner) {
        this.config = config;
        this.processRunner = processRunner;
    }

    // @anchor: javaRunner_compileJava
    // 编译并运行单个 Java 文件
    public String compileJava(Path filePath, String filename, boolean run) {
        try {
            Path projectDir = filePath.getParent();
            Path classesDir = projectDir.resolve("classes");
            Files.createDirectories(classesDir);

            String javacCmd = resolveJavaTool("javac");
            ProcessBuilder compilePb = new ProcessBuilder(
                    javacCmd,
                    "-J-Dstdout.encoding=UTF-8",
                    "-J-Dstderr.encoding=UTF-8",
                    "-encoding", "UTF-8",
                    "-d", classesDir.toString(), filePath.toString());
            compilePb.directory(projectDir.toFile());

            ProcessRunner.ProcessResult compileResult = processRunner.executeProcess(compilePb, 60, false);
            String compileOutput = compileResult.output();
            if (compileResult.timedOut()) {
                return "⏱️ Java 编译超时（60秒），已强制终止。\n输出:\n" + compileOutput;
            }
            if (compileResult.exitCode() != 0) {
                return "❌ 编译失败 (退出码 " + compileResult.exitCode() + "):\n" + compileOutput;
            }

            if (!run) {
                return "✅ 编译成功（未运行）！\n输出:\n" + compileOutput;
            }

            String className = resolveClassName(filePath);
            String javaCmd = resolveJavaTool("java");
            ProcessBuilder runPb = new ProcessBuilder(
                javaCmd,
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8",
                "-Dfile.encoding=UTF-8",
                "-cp", classesDir.toString(), className);
            runPb.directory(projectDir.toFile());

            ProcessRunner.ProcessResult result = processRunner.executeProcess(runPb, 30, true);

            if (result.stalledOnInput()) {
                return "⛔ 运行阻塞（疑似等待标准输入）: \n" + result.output() +
                        "\n💡 建议：请在代码中内置重定向输入（如使用文件流替代 System.in），或设置 run=false 仅编译。";
            }
            if (result.timedOut()) {
                return "⏱️ 运行超时（超过30秒），已强制终止。\n输出:\n" + result.output();
            }
            if (result.exitCode() != 0) {
                return "❌ 运行失败 (退出码 " + result.exitCode() + "):\n" + result.output();
            }
            return "✅ 运行成功！\n输出:\n" + result.output();

        } catch (IOException e) {
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

    // @anchor: javaRunner_resolveJavaTool
    // 优先使用 config.javaHome() 下的可执行文件，回退到 PATH
    private String resolveJavaTool(String tool) {
        String home = config.javaHome();
        if (home != null && !home.isBlank()) {
            boolean win = System.getProperty("os.name").toLowerCase().contains("win");
            Path p = Paths.get(home, "bin", win ? tool + ".exe" : tool);
            if (Files.isRegularFile(p)) return p.toString();
        }
        return tool;
    }
}