// @anchor: cppRunner_intro
// C++ 编译运行：支持 MinGW / GCC / Clang 与 MSVC 编译链
package com.myagent.workflow.tools.runner;

import com.myagent.workflow.core.config.AgentConfig;
import com.myagent.workflow.core.config.env.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

// @anchor: cppRunner_class
// C++ 运行器：编译为平台对应的可执行文件后启动
public class CppRunner {

    private static final Logger logger = LoggerFactory.getLogger(CppRunner.class);

    private final AgentConfig config;
    private final ProcessRunner processRunner;

    // @anchor: cppRunner_constructor
    public CppRunner(AgentConfig config, ProcessRunner processRunner) {
        this.config = config;
        this.processRunner = processRunner;
    }

    // @anchor: cppRunner_compileAndRunCpp
    // 编译并运行 C++ 源文件（MinGW / GCC / Clang / MSVC）
    public String compileAndRunCpp(Path filePath, String filename, boolean run) {
        logger.info("🔧 compileAndRunCpp 被调用: filename={}, run={}, filePath={}", filename, run, filePath);
        try {
            String fileNameStr = filePath.getFileName().toString();
            // Linux 产物无后缀，Windows 补 .exe
            String exeName = fileNameStr.replaceFirst("\\.(cpp|cc|cxx)$",
                    Platform.isWindows() ? ".exe" : "");
            Path exePath = filePath.getParent().resolve(exeName);

            String type = config.cppCompilerType() == null ? "" : config.cppCompilerType().toLowerCase();
            boolean gccLike = "mingw".equals(type) || "gcc".equals(type) || "clang".equals(type);

            ProcessBuilder compilePb;
            if (gccLike) {
                String compiler = config.mingwCompiler();
                if (compiler == null || compiler.isBlank()) {
                    return "❌ 未配置 C++ 编译器路径（env.mingwCompiler 为空）";
                }
                compilePb = new ProcessBuilder(
                        compiler, "-std=c++17",
                        "-o", exePath.toString(), filePath.toString());
                compilePb.directory(filePath.getParent().toFile());
                compilePb.redirectErrorStream(true);
                // MinGW 需要把 bin 目录塞进 PATH 找 DLL；Linux 的 g++/clang++ 无需
                if ("mingw".equals(type)) {
                    injectBinToPath(compilePb, compiler);
                }
            } else {
                String msvc = config.msvcCompiler();
                if (msvc == null || msvc.isBlank()) {
                    return "❌ 未配置 MSVC 编译器路径（env.msvcCompiler 为空）";
                }
                compilePb = new ProcessBuilder(
                        msvc, "/EHsc", "/std:c++17", "/utf-8", filePath.toString());
                compilePb.directory(filePath.getParent().toFile());
                compilePb.redirectErrorStream(true);
                Map<String, String> env = compilePb.environment();
                env.put("INCLUDE", config.msvcInclude());
                env.put("LIB", config.msvcLib());
            }

            ProcessRunner.ProcessResult compileResult = processRunner.executeProcess(compilePb, 60, false);

            if (compileResult.timedOut()) {
                return "⏱️ C++ 编译超时（60秒），已强制终止。\n输出:\n" + compileResult.output();
            }
            if (compileResult.exitCode() != 0) {
                return "❌ C++ 编译失败 (退出码 " + compileResult.exitCode() + "):\n" + compileResult.output();
            }

            if (!run) {
                return "✅ C++ 编译成功（未运行）！\n输出:\n" + compileResult.output();
            }

            ProcessBuilder runPb = new ProcessBuilder(exePath.toString());
            runPb.directory(filePath.getParent().toFile());
            runPb.redirectErrorStream(true);
            if ("mingw".equals(type)) {
                injectBinToPath(runPb, config.mingwCompiler());
            }

            ProcessRunner.ProcessResult runResult = processRunner.executeProcess(runPb, 30, true);

            if (runResult.stalledOnInput()) {
                return "⛔ 运行阻塞（疑似等待标准输入）: \n" + runResult.output() +
                        "\n💡 建议：请在代码中内置重定向输入（如使用文件流替代 std::cin），或设置 run=false 仅编译。\n" +
                        "编译输出:\n" + compileResult.output();
            }
            if (runResult.timedOut()) {
                return "⏱️ C++ 运行超时（30秒），已强制终止。\n编译输出:\n" + compileResult.output() +
                        "\n运行输出:\n" + runResult.output();
            }
            if (runResult.exitCode() != 0) {
                return "❌ C++ 编译成功但运行失败 (退出码 " + runResult.exitCode() + "):\n" +
                        "编译输出:\n" + compileResult.output() + "\n运行输出:\n" + runResult.output();
            }
            return "✅ C++ 编译运行成功！\n编译输出:\n" + compileResult.output() +
                    "\n运行输出:\n" + runResult.output();

        } catch (Exception e) {
            return "❌ C++ 执行异常: " + e.getMessage();
        }
    }

    // @anchor: cppRunner_injectBinToPath
    // 将编译器所在 bin 目录加入子进程 PATH（MinGW 运行时找 DLL 用）
    private static void injectBinToPath(ProcessBuilder pb, String compiler) {
        if (compiler == null || compiler.isBlank()) return;
        Path bin = Paths.get(compiler).getParent();
        if (bin == null) return;
        String old = pb.environment().get("PATH");
        String sep = File.pathSeparator;
        pb.environment().put("PATH", bin + sep + (old == null ? "" : old));
    }
}