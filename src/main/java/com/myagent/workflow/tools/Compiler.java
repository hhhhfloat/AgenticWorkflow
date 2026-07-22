package com.myagent.workflow.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * @anchor: compiler_class
 * 编译运行引擎 —— 负责 HTML 预览、单文件 Java 编译运行、Maven 项目编译。
 * 从 ToolExecutor 中分离，专一管理所有“执行”逻辑。
 */
public class Compiler {
    private static final Logger logger = LoggerFactory.getLogger(Compiler.class);

    private final String sandboxDir;
    private final boolean autoOpenBrowser;

    private final AgentConfig config;

    public Compiler(AgentConfig config) {
        this.sandboxDir = AgentConfig.getSandboxDir();
        this.autoOpenBrowser = config.autoOpenBrowser();
        this.config = config;
    }

    // ==================== 自动检测调度器 ====================
    public String compileAuto(Path filePath, String filename, boolean run) {
        try {
            if (filename.endsWith(".html") || filename.endsWith(".htm")) {
                return previewHtml(filePath, filename);
            }

            Path projectDir = filePath.getParent();
            boolean isMavenProject = Files.exists(projectDir.resolve("pom.xml"));

            if (isMavenProject) {
                return compileMaven(filePath, run);
            }

            if (filename.endsWith(".cpp") || filename.endsWith(".cc") || filename.endsWith(".cxx")) {
                return compileAndRunCpp(filePath, filename, run);
            }

            if (filename.endsWith(".py")) {
                return runPython(filePath, filename, run);
            }

            if (filename.endsWith(".js") && !filename.endsWith(".json")) {
                return runNode(filePath, filename, run);
            }

            return compileJava(filePath, filename, run);

        } catch (IOException e) {
            logger.error("编译/运行过程异常", e);
            return "编译/运行异常: " + e.getMessage();
        }
    }

    // ==================== HTML 预览 ====================
    public String previewHtml(Path filePath, String filename) throws IOException {
        if (!Files.exists(filePath)) {
            return "HTML 文件不存在: " + filename;
        }

        String relativePath = filename.replace("\\", "/");
        if (relativePath.startsWith("sandbox/")) {
            relativePath = relativePath.substring("sandbox/".length());
        }
        if (relativePath.startsWith("/sandbox/")) {
            relativePath = relativePath.substring("/sandbox/".length());
        }
        String url = "http://localhost:8080/sandbox/" + relativePath;

        if (autoOpenBrowser && Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(filePath.toFile().toURI());
            return "✅ 已在浏览器中打开 " + filename + "\n🔗 访问地址: " + url;
        } else {
            return "✅ 预览就绪，请手动访问: " + url;
        }
    }

    // ==================== 单文件 Java 编译运行 ====================
    public String compileJava(Path filePath, String filename, boolean run) {
        try {
            // 1. 获取项目目录（源文件所在目录）
            Path projectDir = filePath.getParent();

            // 2. 在项目目录下创建 classes 子目录
            Path classesDir = projectDir.resolve("classes");
            Files.createDirectories(classesDir);

            // 3. 编译到项目目录下的 classes 子目录
            ProcessBuilder compilePb = new ProcessBuilder(
                    "javac", "-d", classesDir.toString(),
                    filePath.toString()
            );
            compilePb.directory(projectDir.toFile());
            compilePb.redirectErrorStream(true);

            Process compileProc = compilePb.start();
            int compileExit = compileProc.waitFor();
            String compileOutput = new String(compileProc.getInputStream().readAllBytes());

            if (compileExit != 0) {
                return "编译失败 (退出码 " + compileExit + "):\n" + compileOutput;
            }

            // 如果 run 为 false，只编译不运行
            if (!run) {
                return "✅ 编译成功（未运行）！\n输出:\n" + compileOutput;
            }

            // ============================================================
            // 4. 【替换点】运行：从 classes 目录加载类（旧代码全删，换成下面这个）
            // ============================================================
            String className = filename.replace(".java", "");
            ProcessBuilder runPb = new ProcessBuilder(
                    "java", "-cp", classesDir.toString(), className
            );
            runPb.directory(projectDir.toFile());
            runPb.redirectErrorStream(true);

            // ✨ 调用新的智能执行器（总超时30秒，开启输入阻塞检测）
            ProcessResult result = executeProcess(runPb, 30, true);

            if (result.stalledOnInput) {
                return "⛔ 运行阻塞（疑似等待标准输入）: \n" + result.output +
                        "\n💡 建议：请在代码中内置重定向输入（如使用文件流替代 System.in），或设置 run=false 仅编译。";
            }
            if (result.timedOut) {
                return "⏱️ 运行超时（超过30秒），已强制终止。\n输出:\n" + result.output;
            }
            if (result.exitCode != 0) {
                return "运行失败 (退出码 " + result.exitCode + "):\n" + result.output;
            }
            return "运行成功！\n输出:\n" + result.output;

        } catch (IOException | InterruptedException e) {
            return "单文件 Java 执行异常: " + e.getMessage();
        }
    }
    // ==================== Maven 编译（只编译，不运行）====================
    public String compileMaven(Path filePath, boolean run) throws IOException {
        Path projectDir = filePath.toAbsolutePath().normalize();
        if (!Files.isDirectory(projectDir)) {
            projectDir = projectDir.getParent();
        }

        Path pomFile = projectDir.resolve("pom.xml");
        if (!Files.exists(pomFile)) {
            return "❌ 在 " + projectDir + " 下未找到 pom.xml，无法以 Maven 模式编译。";
        }

        try {
            // 编译阶段
            ProcessBuilder compilePb = new ProcessBuilder(config.mavenCommand(), "clean", "compile");
            compilePb.environment().put("JAVA_HOME", config.javaHome());
            compilePb.directory(projectDir.toFile());
            compilePb.redirectErrorStream(true);

            Process compileProc = compilePb.start();
            boolean finished = compileProc.waitFor(60, TimeUnit.SECONDS);
            String compileOutput = new String(compileProc.getInputStream().readAllBytes());

            if (!finished) {
                compileProc.destroyForcibly();
                return "⏱️ Maven 编译超时（超过60秒）。\n输出:\n" + compileOutput;
            }

            int compileExit = compileProc.exitValue();
            if (compileExit != 0) {
                return "❌ Maven 编译失败 (退出码 " + compileExit + "):\n" + compileOutput;
            }

            // 如果 run 为 false，只编译不运行
            if (!run) {
                return "✅ Maven 编译成功（未运行）！\n输出:\n" + compileOutput;
            }

            // ===== 运行阶段 =====
            // 检查是否为 JavaFX 项目
            String pomContent = Files.readString(pomFile);
            boolean isJavaFX = pomContent.contains("javafx-maven-plugin");

            if (isJavaFX) {
                // JavaFX 项目：使用 mvn javafx:run
                ProcessBuilder runPb = new ProcessBuilder(config.mavenCommand(), "javafx:run");
                runPb.directory(projectDir.toFile());
                runPb.redirectErrorStream(true);
                runPb.environment().put("JAVA_HOME", config.javaHome());

                Process runProc = runPb.start();
                // 等待最多 5 秒检测是否启动成功（GUI 程序不会退出，所以超时即认为启动成功）
                boolean started = runProc.waitFor(5, TimeUnit.SECONDS);
                if (!started) {
                    // 程序还在运行，视为启动成功
                    // 注意：进程仍在后台运行，我们无法自动关闭它，但可以返回提示
                    return "✅ JavaFX 应用已启动！\n" +
                            "编译输出:\n" + compileOutput + "\n" +
                            "窗口应该已弹出，请查看。\n" +
                            "注意：该进程仍在后台运行，如需关闭请手动终止（Ctrl+C 或任务管理器）。";
                } else {
                    // 异常情况：程序退出了，可能有问题
                    String runOutput = new String(runProc.getInputStream().readAllBytes());
                    return "⚠️ JavaFX 应用启动后立即退出，可能有错误。\n输出:\n" + runOutput;
                }
            }
            else {
                // 普通 Maven 项目：用 java -cp target/classes 运行
                String mainClass = findMainClass(projectDir);
                if (mainClass == null) {
                    return "✅ Maven 编译成功！但未找到包含 main 方法的类，无法运行。\n输出:\n" + compileOutput;
                }

                Path classpath = projectDir.resolve("target/classes");
                ProcessBuilder runPb = new ProcessBuilder(
                        "java", "-cp", classpath.toString(), mainClass
                );
                runPb.directory(projectDir.toFile());
                runPb.redirectErrorStream(true);
                runPb.environment().put("JAVA_HOME", config.javaHome());

                // ✨ 替换点：调用新的智能执行器（总超时30秒，开启输入阻塞检测）
                ProcessResult result = executeProcess(runPb, 30, true);

                if (result.stalledOnInput) {
                    return "⛔ 运行阻塞（疑似等待标准输入）: \n" + result.output +
                            "\n💡 建议：请在代码中内置重定向输入（如使用文件流替代 System.in），或设置 run=false 仅编译。\n" +
                            "编译输出:\n" + compileOutput;
                }
                if (result.timedOut) {
                    return "⏱️ 运行超时（超过30秒），已强制终止。\n编译输出:\n" + compileOutput + "\n运行输出:\n" + result.output;
                }
                if (result.exitCode != 0) {
                    return "✅ Maven 编译成功！但运行失败 (退出码 " + result.exitCode + "):\n" +
                            "编译输出:\n" + compileOutput + "\n运行输出:\n" + result.output;
                }
                return "✅ Maven 编译运行成功！\n编译输出:\n" + compileOutput + "\n运行输出:\n" + result.output;
            }

        } catch (IOException e) {
            return "❌ 执行 Maven 失败，请确认已安装 Maven 并配置 PATH 环境变量。\n" + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "❌ Maven 编译被中断: " + e.getMessage();
        }
    }

    /**
     * 在 Maven 项目的 target/classes 中查找包含 main 方法的类
     * @return 全限定类名（如 com.test.Hello），如果没找到返回 null
     */
    private String findMainClass(Path projectDir) throws IOException, InterruptedException {
        Path classesDir = projectDir.resolve("target/classes");
        if (!Files.exists(classesDir) || !Files.isDirectory(classesDir)) {
            return null;
        }

        // 递归遍历所有 .class 文件
        try (Stream<Path> stream = Files.walk(classesDir)) {
            for (Path file : stream.toList()) {
                if (!Files.isRegularFile(file)) continue;
                if (!file.toString().endsWith(".class")) continue;
                // 跳过内部类（包含 $ 的）
                if (file.getFileName().toString().contains("$")) continue;

                // 将路径转换为全限定类名
                String relativePath = classesDir.relativize(file).toString();
                String className = relativePath.replace(File.separatorChar, '.')
                        .replace(".class", "");

                // 用 javap 检查是否包含 main 方法
                ProcessBuilder pb = new ProcessBuilder(
                        "javap", "-public", file.toString()
                );
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exitCode = p.waitFor();
                if (exitCode != 0) continue;

                // 检查输出中是否有 "public static void main(java.lang.String[])"
                if (output.contains("public static void main(java.lang.String[])")) {
                    return className;
                }
            }
        }
        return null;
    }

    // ==================== C++ 编译运行（MSVC）====================
    @SuppressWarnings("ConstantConditions")
    public String compileAndRunCpp(Path filePath, String filename, boolean run) {
        logger.info("🔧 compileAndRunCpp 被调用: filename={}, run={}, filePath={}", filename, run, filePath);
        try {
            String fileNameStr = filePath.getFileName().toString();
            String exeName = fileNameStr.replaceFirst("\\.(cpp|cc|cxx)$", ".exe");
            Path exePath = filePath.getParent().resolve(exeName);

            // ========== 1. 编译阶段 ==========
            ProcessBuilder compilePb;
            if ("mingw".equalsIgnoreCase(config.cppCompilerType())) {
                Path mingwBin = Paths.get(config.mingwCompiler()).getParent();
                String pathEnv = mingwBin.toString() + File.pathSeparator + System.getenv("PATH");
                compilePb = new ProcessBuilder(
                        config.mingwCompiler(),
                        "-std=c++17",
                        "-o", exePath.toString(),
                        filePath.toString()
                );
                compilePb.directory(filePath.getParent().toFile());
                compilePb.redirectErrorStream(true);
                compilePb.environment().put("PATH", pathEnv);
            } else {
                compilePb = new ProcessBuilder(
                        config.msvcCompiler(),
                        "/EHsc",
                        "/std:c++17",
                        "/utf-8",
                        filePath.toString()
                );
                compilePb.directory(filePath.getParent().toFile());
                compilePb.redirectErrorStream(true);
                Map<String, String> env = compilePb.environment();
                env.put("INCLUDE", config.msvcInclude());
                env.put("LIB", config.msvcLib());
            }

            // ✅ 使用 executeProcess 执行编译（超时 60 秒，关闭输入阻塞检测）
            ProcessResult compileResult = executeProcess(compilePb, 60, false);

            if (compileResult.timedOut) {
                return "⏱️ C++ 编译超时（60秒），已强制终止。\n输出:\n" + compileResult.output;
            }
            if (compileResult.exitCode != 0) {
                return "❌ C++ 编译失败 (退出码 " + compileResult.exitCode + "):\n" + compileResult.output;
            }

            // 编译成功，如果 run=false 则直接返回
            if (!run) {
                return "✅ C++ 编译成功（未运行）！\n输出:\n" + compileResult.output;
            }

            // ========== 2. 运行阶段 ==========
            ProcessBuilder runPb = new ProcessBuilder(exePath.toString());
            runPb.directory(filePath.getParent().toFile());
            runPb.redirectErrorStream(true);
            if ("mingw".equalsIgnoreCase(config.cppCompilerType())) {
                Path mingwBin = Paths.get(config.mingwCompiler()).getParent();
                runPb.environment().put("PATH", mingwBin.toString() + File.pathSeparator + System.getenv("PATH"));
            }

            // ✅ 使用 executeProcess 执行运行（超时 30 秒，开启输入阻塞检测）
            ProcessResult runResult = executeProcess(runPb, 30, true);

            if (runResult.stalledOnInput) {
                return "⛔ 运行阻塞（疑似等待标准输入）: \n" + runResult.output +
                        "\n💡 建议：请在代码中内置重定向输入（如使用文件流替代 std::cin），或设置 run=false 仅编译。\n" +
                        "编译输出:\n" + compileResult.output;
            }
            if (runResult.timedOut) {
                return "⏱️ C++ 运行超时（30秒），已强制终止。\n编译输出:\n" + compileResult.output + "\n运行输出:\n" + runResult.output;
            }
            if (runResult.exitCode != 0) {
                return "✅ C++ 编译成功！但运行失败 (退出码 " + runResult.exitCode + "):\n" +
                        "编译输出:\n" + compileResult.output + "\n运行输出:\n" + runResult.output;
            }
            return "✅ C++ 编译运行成功！\n编译输出:\n" + compileResult.output + "\n运行输出:\n" + runResult.output;

        } catch (Exception e) {
            return "❌ C++ 执行异常: " + e.getMessage();
        }
    }
    // ==================== Python 解释执行 ====================
// ==================== Python 解释执行 ====================
    public String runPython(Path filePath, String filename, boolean run) {
        if (!run) {
            return "✅ Python 脚本已就绪（未运行）！\n文件: " + filename;
        }

        // ----- 检测是否为 GUI 程序 -----
        boolean isGui = false;
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            // 常见 GUI 库关键字
            if (content.contains("import pygame") || content.contains("from pygame") ||
                    content.contains("import tkinter") || content.contains("from tkinter") ||
                    content.contains("import PyQt") || content.contains("from PyQt") ||
                    content.contains("import PySide") || content.contains("from PySide") ||
                    content.contains("import wx") || content.contains("from wx")) {
                isGui = true;
            }
        } catch (IOException e) {
            // 读取失败则默认非 GUI，继续正常流程
        }

        ProcessBuilder pb = new ProcessBuilder(
                config.pythonInterpreter(),
                filePath.toString()
        );
        pb.directory(filePath.getParent().toFile());
        pb.redirectErrorStream(true);

        // ----- GUI 分支 -----
        if (isGui) {
            try {
                Process p = pb.start();
                // 等待 2 秒让窗口出现（GUI 程序通常不会立即退出）
                boolean exited = p.waitFor(2, TimeUnit.SECONDS);
                if (!exited) {
                    // 进程仍在运行，视为启动成功
                    return "✅ Python GUI 程序已启动！\n窗口应该已弹出，请查看。\n注意：该进程仍在后台运行，如需关闭请手动终止。";
                } else {
                    // 进程提前退出，可能出错
                    String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    return "⚠️ GUI 程序启动后立即退出，可能有错误。\n输出:\n" + output;
                }
            } catch (IOException | InterruptedException e) {
                return "❌ 启动 GUI 程序异常: " + e.getMessage();
            }
        }

        // ----- 非 GUI 分支：正常执行，开启输入阻塞检测 -----
        ProcessResult result = executeProcess(pb, 30, true);

        if (result.stalledOnInput) {
            return "⛔ 运行阻塞（疑似等待标准输入）: \n" + result.output +
                    "\n💡 建议：请在代码中内置重定向输入（如使用文件流替代 input()），或设置 run=false 仅检查语法。";
        }
        if (result.timedOut) {
            return "⏱️ Python 运行超时（30秒），已强制终止。\n输出:\n" + result.output;
        }
        if (result.exitCode != 0) {
            return "❌ Python 运行失败 (退出码 " + result.exitCode + "):\n" + result.output;
        }
        return "✅ Python 运行成功！\n输出:\n" + result.output;
    }
    // ==================== Node.js 解释执行 ====================
    public String runNode(Path filePath, String filename, boolean run) {
        if (!run) {
            return "✅ Node.js 脚本已就绪（未运行）！\n文件: " + filename;
        }

        ProcessBuilder pb = new ProcessBuilder(
                config.nodeInterpreter(),
                filePath.toString()
        );
        pb.directory(filePath.getParent().toFile());
        pb.redirectErrorStream(true);

        // ✨ 替换点：调用智能执行器（总超时30秒，开启输入阻塞检测）
        ProcessResult result = executeProcess(pb, 30, true);

        if (result.stalledOnInput) {
            return "⛔ Node.js 运行阻塞（疑似等待标准输入）: \n" + result.output +
                    "\n💡 建议：请在代码中内置重定向输入（如使用文件流替代 process.stdin），或设置 run=false 仅检查语法。";
        }
        if (result.timedOut) {
            return "⏱️ Node.js 运行超时（30秒），已强制终止。\n输出:\n" + result.output;
        }
        if (result.exitCode != 0) {
            return "❌ Node.js 运行失败 (退出码 " + result.exitCode + "):\n" + result.output;
        }
        return "✅ Node.js 运行成功！\n输出:\n" + result.output;
    }

    // 放在 Compiler 类内部
    private static record ProcessResult(String output, int exitCode, boolean timedOut, boolean stalledOnInput) {}

    /**
     * 智能进程执行器
     * @param pb ProcessBuilder 实例
     * @param timeoutSeconds 总超时秒数
     * @param detectStdinStall 是否开启输入阻塞检测（GUI程序可关闭）
     * @return 执行结果
     */
    private ProcessResult executeProcess(ProcessBuilder pb, long timeoutSeconds, boolean detectStdinStall) {
        // ========== 新增：安全加固 ==========
        try {
            // 1. 设置安全环境变量
            secureEnvironment(pb);

            // 2. 校验工作目录是否在沙箱内
            File dir = pb.directory();
            if (dir != null) {
                Path workDir = dir.toPath().toAbsolutePath().normalize();
                Path sandboxRoot = Paths.get(sandboxDir).toAbsolutePath().normalize();
                if (!workDir.startsWith(sandboxRoot)) {
                    logger.error("❌ 工作目录 {} 不在沙箱内，拒绝启动", workDir);
                    return new ProcessResult("安全拒绝：工作目录 " + workDir + " 不在沙箱内", -1, false, false);
                }
            } else {
                // 如果未设置工作目录，强制设为沙箱根目录（防止默认使用系统目录）
                pb.directory(Paths.get(sandboxDir).toFile());
            }
        } catch (IOException e) {
            logger.error("❌ 安全加固设置失败: {}", e.getMessage(), e);
            return new ProcessResult("安全加固异常: " + e.getMessage(), -1, false, false);
        }

        // ========== 安全加固结束 ==========
        logger.info("🚀 启动进程: command={}, directory={}", pb.command(), pb.directory());
        StringBuilder output = new StringBuilder();
        long startTime = System.currentTimeMillis();
        AtomicLong lastOutputTime = new AtomicLong(startTime);
        long lastLogTime = startTime;

        try {
            Process process = pb.start();
            long pid = process.pid();
            logger.info("✅ 进程已启动, PID={}", pid);

            // 异步读取输出
            Thread reader = new Thread(() -> {
                try (var is = process.getInputStream()) {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        String chunk = new String(buffer, 0, len, StandardCharsets.UTF_8);
                        synchronized (output) {
                            output.append(chunk);
                        }
                        long now = System.currentTimeMillis();
                        lastOutputTime.set(now);
                        logger.trace("📝 读取到输出: {} 字符", chunk.length());
                    }
                } catch (IOException e) {
                    logger.warn("读取进程输出时发生异常: {}", e.getMessage());
                }
            });
            reader.setDaemon(true);
            reader.start();

            // 监控循环
            while (true) {
                long now = System.currentTimeMillis();
                long elapsed = now - startTime;
                long silentDuration = now - lastOutputTime.get();

                // 每 2 秒打印一次状态（避免刷屏）
                if (now - lastLogTime > 2000) {
                    lastLogTime = now;
                    logger.info("⏱️ 监控: elapsed={}s, silent={}s, alive={}",
                            elapsed/1000, silentDuration/1000, process.isAlive());
                }

                // 1. 总超时
                if (elapsed > timeoutSeconds * 1000L) {
                    logger.warn("⏱️ 总超时 ({}秒)，强制终止进程", timeoutSeconds);
                    process.destroyForcibly();
                    return new ProcessResult(output.toString(), -1, true, false);
                }

                // 2. 输入阻塞检测
                if (detectStdinStall && process.isAlive()) {
                    // 启动后至少给 10 秒宽容期，之后连续 10 秒无输出则判定阻塞
                    if (elapsed > 10000 && silentDuration > 10000) {
                        logger.warn("⛔ 检测到输入阻塞（{}秒无输出），强制终止进程", silentDuration/1000);
                        process.destroyForcibly();
                        return new ProcessResult(
                                output.toString() + "\n⚠️ 检测到程序在 10 秒内未输出任何内容，疑似在等待标准输入（stdin）。",
                                -1, false, true
                        );
                    }
                }

                // 检查线程是否被中断（点击停止时）
                if (Thread.interrupted()) {
                    logger.info("⏹️ Compiler : 收到中断信号，正在终止进程");
                    process.destroyForcibly();
                    return new ProcessResult(
                            output.toString() + "\n⏹️ 用户已停止任务",
                            -1, false, false
                    );
                }


                // 3. 检查进程是否结束
                try {
                    int exitCode = process.exitValue();
                    logger.info("🏁 进程正常结束，退出码: {}", exitCode);
                    return new ProcessResult(output.toString(), exitCode, false, false);
                } catch (IllegalThreadStateException e) {
                    // 进程仍在运行，继续等待
                    Thread.sleep(200);
                }
            }
        } catch (IOException e) {
            logger.error("❌ 启动进程异常: {}", e.getMessage(), e);
            return new ProcessResult("执行异常: " + e.getMessage(), -1, false, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("❌ 监控线程中断");
            return new ProcessResult("执行被中断", -1, false, false);
        }
    }

    /**
     * 为子进程设置安全的环境变量，将临时目录和用户主目录重定向到沙箱内。
     */
    private void secureEnvironment(ProcessBuilder pb) throws IOException {
        Map<String, String> env = pb.environment();
        Path sandboxRoot = Paths.get(sandboxDir).toAbsolutePath().normalize();

        // 创建沙箱内的临时目录
        Path sandboxTmp = sandboxRoot.resolve("tmp");
        Files.createDirectories(sandboxTmp);
        String tmpPath = sandboxTmp.toString();

        // Unix/Linux/macOS
        env.put("TMPDIR", tmpPath);
        // Windows
        env.put("TEMP", tmpPath);
        env.put("TMP", tmpPath);

        // 重定向用户主目录，防止程序读取 ~/.ssh, ~/.bashrc 等
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            env.put("USERPROFILE", sandboxRoot.toString());
        } else {
            env.put("HOME", sandboxRoot.toString());
        }
    }
}