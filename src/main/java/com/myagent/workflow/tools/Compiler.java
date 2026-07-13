package com.myagent.workflow.tools;

import com.myagent.workflow.core.AgentConfig;
import com.myagent.workflow.core.ConfigEditor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
    public String compileAuto(Path filePath, String filename) {
        try {
            if (filename.endsWith(".html") || filename.endsWith(".htm")) {
                return previewHtml(filePath, filename);
            }

            Path projectDir = filePath.getParent();
            boolean isMavenProject = Files.exists(projectDir.resolve("pom.xml"));

            if (isMavenProject) {
                return compileMaven(filePath);
            }

            if (filename.endsWith(".cpp") || filename.endsWith(".cc") || filename.endsWith(".cxx")) {
                return compileAndRunCpp(filePath, filename);
            }

            if (filename.endsWith(".py")) {
                return runPython(filePath, filename);
            }

            if (filename.endsWith(".js") && !filename.endsWith(".json")) {
                return runNode(filePath, filename);
            }

            return compileJava(filePath, filename);

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
    // ==================== 单文件 Java 编译运行 ====================
    public String compileJava(Path filePath, String filename) {
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

            // 4. 运行：从 classes 目录加载类
            String className = filename.replace(".java", "");
            ProcessBuilder runPb = new ProcessBuilder(
                    "java", "-cp", classesDir.toString(), className
            );
            runPb.directory(projectDir.toFile());
            runPb.redirectErrorStream(true);

            Process runProc = runPb.start();
            boolean finished = runProc.waitFor(30, TimeUnit.SECONDS);
            String runOutput = new String(runProc.getInputStream().readAllBytes());

            if (!finished) {
                runProc.destroyForcibly();
                return "运行超时（超过30秒），已强制终止。\n输出:\n" + runOutput;
            }

            int runExit = runProc.exitValue();
            if (runExit != 0) {
                return "运行失败 (退出码 " + runExit + "):\n" + runOutput;
            }
            return "运行成功！\n输出:\n" + runOutput;

        } catch (IOException | InterruptedException e) {
            return "单文件 Java 执行异常: " + e.getMessage();
        }
    }

    // ==================== Maven 编译（只编译，不运行）====================
    public String compileMaven(Path filePath) throws IOException {
        Path projectDir = filePath.toAbsolutePath().normalize();
        if (!Files.isDirectory(projectDir)) {
            projectDir = projectDir.getParent();
        }

        Path pomFile = projectDir.resolve("pom.xml");
        if (!Files.exists(pomFile)) {
            return "❌ 在 " + projectDir + " 下未找到 pom.xml，无法以 Maven 模式编译。";
        }

        try {
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

            // 编译成功后，尝试自动运行
            String mainClass = findMainClass(projectDir);
            if (mainClass == null) {
                return "✅ Maven 编译成功！但未找到包含 main 方法的类，无法运行。\n输出:\n" + compileOutput;
            }


            // 用 java -cp target/classes 运行
            try {
                Path classpath = projectDir.resolve("target/classes");
                ProcessBuilder runPb = new ProcessBuilder(
                        "java", "-cp", classpath.toString(), mainClass
                );
                runPb.directory(projectDir.toFile());
                runPb.redirectErrorStream(true);
                runPb.environment().put("JAVA_HOME", config.javaHome());

                Process runProc = runPb.start();
                finished = runProc.waitFor(30, TimeUnit.SECONDS);
                String runOutput = new String(runProc.getInputStream().readAllBytes());

                if (!finished) {
                    runProc.destroyForcibly();
                    return "✅ Maven 编译成功！但运行超时（30秒）。\n编译输出:\n" + compileOutput;
                }

                return "✅ Maven 编译运行成功！\n编译输出:\n" + compileOutput + "\n运行输出:\n" + runOutput;

            } catch (IOException | InterruptedException e) {
                return "✅ Maven 编译成功！但运行失败: " + e.getMessage();
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
    public String compileAndRunCpp(Path filePath, String filename) {
        try {
            String fileNameStr = filePath.getFileName().toString();
            String exeName = fileNameStr.replaceFirst("\\.(cpp|cc|cxx)$", ".exe");
            Path exePath = filePath.getParent().resolve(exeName);

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
                        filePath.toString()
                );
                compilePb.directory(filePath.getParent().toFile());
                compilePb.redirectErrorStream(true);
                Map<String, String> env = compilePb.environment();
                env.put("INCLUDE", config.msvcInclude());
                env.put("LIB", config.msvcLib());
            }

            Process compileProc = compilePb.start();
            int compileExit = compileProc.waitFor();
            String compileOutput = new String(compileProc.getInputStream().readAllBytes());

            if (compileExit != 0) {
                return "❌ C++ 编译失败 (退出码 " + compileExit + "):\n" + compileOutput;
            }

            // 运行
            ProcessBuilder runPb = new ProcessBuilder(exePath.toString());
            runPb.directory(filePath.getParent().toFile());
            runPb.redirectErrorStream(true);
            if ("mingw".equalsIgnoreCase(config.cppCompilerType())) {
                Path mingwBin = Paths.get(config.mingwCompiler()).getParent();
                runPb.environment().put("PATH", mingwBin.toString() + File.pathSeparator + System.getenv("PATH"));
            }

            Process runProc = runPb.start();
            boolean finished = runProc.waitFor(30, TimeUnit.SECONDS);
            String runOutput = new String(runProc.getInputStream().readAllBytes());

            if (!finished) {
                runProc.destroyForcibly();
                return "⏱️ C++ 运行超时（30秒）。\n编译输出:\n" + compileOutput;
            }

            int runExit = runProc.exitValue();
            if (runExit != 0) {
                return "✅ C++ 编译成功！但运行失败 (退出码 " + runExit + "):\n" + runOutput;
            }

            return "✅ C++ 编译运行成功！\n编译输出:\n" + compileOutput + "\n运行输出:\n" + runOutput;

        } catch (IOException | InterruptedException e) {
            return "❌ C++ 执行异常: " + e.getMessage();
        }
    }

    // ==================== Python 解释执行 ====================
    public String runPython(Path filePath, String filename) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    config.pythonInterpreter(),
                    filePath.toString()
            );
            pb.directory(filePath.getParent().toFile());
            pb.redirectErrorStream(true);

            Process p = pb.start();
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            String output = new String(p.getInputStream().readAllBytes());

            if (!finished) {
                p.destroyForcibly();
                return "⏱️ Python 运行超时（30秒）。\n输出:\n" + output;
            }

            int exitCode = p.exitValue();
            if (exitCode != 0) {
                return "❌ Python 运行失败 (退出码 " + exitCode + "):\n" + output;
            }

            return "✅ Python 运行成功！\n输出:\n" + output;

        } catch (IOException | InterruptedException e) {
            return "❌ Python 执行异常: " + e.getMessage();
        }
    }

    // ==================== Node.js 解释执行 ====================
    public String runNode(Path filePath, String filename) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    config.nodeInterpreter(),
                    filePath.toString()
            );
            pb.directory(filePath.getParent().toFile());
            pb.redirectErrorStream(true);

            Process p = pb.start();
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            String output = new String(p.getInputStream().readAllBytes());

            if (!finished) {
                p.destroyForcibly();
                return "⏱️ Node.js 运行超时（30秒）。\n输出:\n" + output;
            }

            int exitCode = p.exitValue();
            if (exitCode != 0) {
                return "❌ Node.js 运行失败 (退出码 " + exitCode + "):\n" + output;
            }

            return "✅ Node.js 运行成功！\n输出:\n" + output;

        } catch (IOException | InterruptedException e) {
            return "❌ Node.js 执行异常: " + e.getMessage();
        }
    }

}