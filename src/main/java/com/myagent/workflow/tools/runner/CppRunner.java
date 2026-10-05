// @anchor: cppRunner_tot_desc
// C++ 编译运行：支持 MinGW 与 MSVC 两条编译链
package com.myagent.workflow.tools.runner;

import com.myagent.workflow.core.config.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

// @anchor: cppRunner_class
// C++ 运行器：编译为 .exe 后启动
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
    // 编译并运行 C++ 源文件（MSVC/MinGW）
    public String compileAndRunCpp(Path filePath, String filename, boolean run) {
        logger.info("🔧 compileAndRunCpp 被调用: filename={}, run={}, filePath={}", filename, run, filePath);
        try {
            String fileNameStr = filePath.getFileName().toString();
            String exeName = fileNameStr.replaceFirst("\\.(cpp|cc|cxx)$", ".exe");
            Path exePath = filePath.getParent().resolve(exeName);

            ProcessBuilder compilePb;
            if ("mingw".equalsIgnoreCase(config.cppCompilerType())) {
                Path mingwBin = Paths.get(config.mingwCompiler()).getParent();
                String pathEnv = mingwBin + File.pathSeparator + System.getenv("PATH");
                compilePb = new ProcessBuilder(
                        config.mingwCompiler(), "-std=c++17",
                        "-o", exePath.toString(), filePath.toString());
                compilePb.directory(filePath.getParent().toFile());
                compilePb.redirectErrorStream(true);
                compilePb.environment().put("PATH", pathEnv);
            } else {
                compilePb = new ProcessBuilder(
                        config.msvcCompiler(), "/EHsc", "/std:c++17", "/utf-8", filePath.toString());
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
            if ("mingw".equalsIgnoreCase(config.cppCompilerType())) {
                Path mingwBin = Paths.get(config.mingwCompiler()).getParent();
                runPb.environment().put("PATH", mingwBin + File.pathSeparator + System.getenv("PATH"));
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
                return "✅ C++ 编译成功！但运行失败 (退出码 " + runResult.exitCode() + "):\n" +
                        "编译输出:\n" + compileResult.output() + "\n运行输出:\n" + runResult.output();
            }
            return "✅ C++ 编译运行成功！\n编译输出:\n" + compileResult.output() +
                    "\n运行输出:\n" + runResult.output();

        } catch (Exception e) {
            return "❌ C++ 执行异常: " + e.getMessage();
        }
    }
}