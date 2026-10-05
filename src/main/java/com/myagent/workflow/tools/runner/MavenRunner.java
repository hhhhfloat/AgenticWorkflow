// @anchor: mavenRunner_intro
// Maven 项目编译运行：clean compile + 主类定位 + JavaFX 分支与进程清理
package com.myagent.workflow.tools.runner;

import com.myagent.workflow.core.ProcessRegistry;
import com.myagent.workflow.core.config.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

// @anchor: mavenRunner_class
// Maven 运行器：编译并运行 Maven 项目（含 JavaFX 分支）
public class MavenRunner {

    private static final Logger logger = LoggerFactory.getLogger(MavenRunner.class);
    private static final Charset PROCESS_CHARSET = StandardCharsets.UTF_8;

    private final AgentConfig config;
    private final ProcessRunner processRunner;

    // @anchor: mavenRunner_constructor
    public MavenRunner(AgentConfig config, ProcessRunner processRunner) {
        this.config = config;
        this.processRunner = processRunner;
    }

    // @anchor: mavenRunner_compileMaven
    // 编译并运行 Maven 项目
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

            if (!finished) {
                ProcessRunner.killTree(compileProc);
                String timeoutOutput = new String(compileProc.getInputStream().readAllBytes(), PROCESS_CHARSET);
                return "⏱️ Maven 编译超时（超过60秒）。\n输出:\n" + timeoutOutput;
            }

            String compileOutput = new String(compileProc.getInputStream().readAllBytes(), PROCESS_CHARSET);
            int compileExit = compileProc.exitValue();
            if (compileExit != 0) {
                return "❌ Maven 编译失败 (退出码 " + compileExit + "):\n" + compileOutput;
            }

            if (!run) {
                return "✅ Maven 编译成功（未运行）！\n输出:\n" + compileOutput;
            }

            // 运行阶段
            String pomContent = Files.readString(pomFile);
            boolean isJavaFX = pomContent.contains("javafx-maven-plugin");

            if (isJavaFX) {
                return runJavaFx(projectDir, compileOutput);
            }
            return runNormalMaven(projectDir, compileOutput);

        } catch (IOException e) {
            return "❌ 执行 Maven 失败，请确认已安装 Maven 并配置 PATH 环境变量。\n" + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "❌ Maven 编译被中断: " + e.getMessage();
        }
    }

    // @anchor: mavenRunner_runJavaFx
    // JavaFX 分支：mvn javafx:run，进程不退出即视为启动成功
    private String runJavaFx(Path projectDir, String compileOutput) throws IOException, InterruptedException {
        cleanupPreviousJavaFx(projectDir);

        ProcessBuilder runPb = new ProcessBuilder(config.mavenCommand(), "javafx:run");
        runPb.directory(projectDir.toFile());
        runPb.redirectErrorStream(true);
        runPb.environment().put("JAVA_HOME", config.javaHome());

        Process runProc = runPb.start();
        ProcessRegistry.register(runProc);

        boolean started = runProc.waitFor(10, TimeUnit.SECONDS);
        if (!started) {
            // 保留注册：主服务退出时由 shutdown hook 统一回收
            return "✅ JavaFX 应用已启动！\n" +
                    "编译输出:\n" + compileOutput + "\n" +
                    "窗口应该已弹出，请查看。\n" +
                    "注意：该进程仍在后台运行，如需关闭请手动终止（Ctrl+C 或任务管理器）。";
        } else {
            ProcessRegistry.unregister(runProc);
            String runOutput = new String(runProc.getInputStream().readAllBytes(), PROCESS_CHARSET);
            return "⚠️ JavaFX 应用启动后立即退出，可能有错误。\n输出:\n" + runOutput;
        }
    }

    // @anchor: mavenRunner_runNormalMaven
    // 普通 Maven 项目：定位主类后 java -cp target/classes 启动
    private String runNormalMaven(Path projectDir, String compileOutput) throws IOException, InterruptedException {
        String mainClass = findMainClass(projectDir);
        if (mainClass == null) {
            return "✅ Maven 编译成功！但未找到包含 main 方法的类，无法运行。\n输出:\n" + compileOutput;
        }

        Path classpath = projectDir.resolve("target/classes");
        ProcessBuilder runPb = new ProcessBuilder(
                "java", "-cp", classpath.toString(), mainClass);
        runPb.directory(projectDir.toFile());
        runPb.redirectErrorStream(true);
        runPb.environment().put("JAVA_HOME", config.javaHome());

        ProcessRunner.ProcessResult result = processRunner.executeProcess(runPb, 30, true);

        if (result.stalledOnInput()) {
            return "⛔ 运行阻塞（疑似等待标准输入）: \n" + result.output() +
                    "\n💡 建议：请在代码中内置重定向输入（如使用文件流替代 System.in），或设置 run=false 仅编译。\n" +
                    "编译输出:\n" + compileOutput;
        }
        if (result.timedOut()) {
            return "⏱️ 运行超时（超过30秒），已强制终止。\n编译输出:\n" + compileOutput + "\n运行输出:\n" + result.output();
        }
        if (result.exitCode() != 0) {
            return "✅ Maven 编译成功！但运行失败 (退出码 " + result.exitCode() + "):\n" +
                    "编译输出:\n" + compileOutput + "\n运行输出:\n" + result.output();
        }
        return "✅ Maven 编译运行成功！\n编译输出:\n" + compileOutput + "\n运行输出:\n" + result.output();
    }

    // @anchor: mavenRunner_findMainClass
    // 在 target/classes 中定位含 main 方法的主类
    private String findMainClass(Path projectDir) throws IOException, InterruptedException {
        Path classesDir = projectDir.resolve("target/classes");
        if (!Files.exists(classesDir) || !Files.isDirectory(classesDir)) {
            return null;
        }

        String javap = resolveJavap();

        try (Stream<Path> stream = Files.walk(classesDir)) {
            for (Path file : stream.toList()) {
                if (!Files.isRegularFile(file)) continue;
                if (!file.toString().endsWith(".class")) continue;
                if (file.getFileName().toString().contains("$")) continue;

                String relativePath = classesDir.relativize(file).toString();
                String className = relativePath.replace(File.separatorChar, '.')
                        .replace(".class", "");

                ProcessBuilder pb = new ProcessBuilder(javap, "-public", file.toString());
                pb.redirectErrorStream(true);
                Process p = pb.start();

                // 先 waitFor 再读输出：避免超时分支不可达（与 Z3 同源）
                boolean javapDone = p.waitFor(10, TimeUnit.SECONDS);
                if (!javapDone) {
                    ProcessRunner.killTree(p);
                    continue;
                }
                String output = new String(p.getInputStream().readAllBytes(), PROCESS_CHARSET);
                if (p.exitValue() != 0) continue;

                if (output.contains("public static void main(java.lang.String[])")) {
                    return className;
                }
            }
        }
        return null;
    }

    // @anchor: mavenRunner_resolveJavap
    // 优先用 config.javaHome() 下的绝对路径，回退到 PATH
    private String resolveJavap() {
        String home = config.javaHome();
        if (home != null && !home.isBlank()) {
            boolean win = System.getProperty("os.name").toLowerCase().contains("win");
            Path p = Paths.get(home, "bin", win ? "javap.exe" : "javap");
            if (Files.isRegularFile(p)) return p.toString();
        }
        return "javap";
    }

    // @anchor: mavenRunner_cleanupPreviousJavaFx
    // 清理指定项目下遗留的 JavaFX 进程（避免累积）
    private void cleanupPreviousJavaFx(Path projectDir) {
        String projectPath = projectDir.toAbsolutePath().normalize()
                .toString().replace('\\', '/');
        long selfPid = ProcessHandle.current().pid();
        try {
            ProcessHandle.allProcesses()
                    .filter(ph -> ph.pid() != selfPid)
                    .filter(ph -> ph.info().commandLine().isPresent())
                    .filter(ph -> {
                        String cmd = ph.info().commandLine().orElse("").replace('\\', '/');
                        if (cmd.isEmpty()) return false;
                        if (!cmd.contains(projectPath)) return false;
                        return cmd.contains("javafx:run")
                                || cmd.contains("javafx-maven-plugin")
                                || cmd.contains("org.openjfx");
                    })
                    .forEach(ph -> {
                        logger.info("🧹 清理遗留 JavaFX 进程: PID={}", ph.pid());
                        try { ph.destroyForcibly(); } catch (Exception ignored) {}
                    });
        } catch (Exception e) {
            logger.warn("清理遗留 JavaFX 进程失败: {}", e.getMessage());
        }
    }
}