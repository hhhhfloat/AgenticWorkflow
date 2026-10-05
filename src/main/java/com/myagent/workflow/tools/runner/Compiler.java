// @anchor: compiler_tot_desc
// 编译运行门面：按扩展名调度到对应语言运行器
package com.myagent.workflow.tools.runner;

import com.myagent.workflow.core.config.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

// @anchor: compiler_class
// 编译运行门面：对外暴露 7 个入口，内部按语言委托
public class Compiler {

    private static final Logger logger = LoggerFactory.getLogger(Compiler.class);

    private final HtmlPreviewer htmlPreviewer;
    private final JavaRunner javaRunner;
    private final MavenRunner mavenRunner;
    private final CppRunner cppRunner;
    private final PythonRunner pythonRunner;
    private final NodeRunner nodeRunner;

    // @anchor: compiler_constructor
    public Compiler(AgentConfig config) {
        ProcessRunner processRunner = new ProcessRunner();
        this.htmlPreviewer = new HtmlPreviewer(config);
        this.javaRunner = new JavaRunner(config, processRunner);
        this.mavenRunner = new MavenRunner(config, processRunner);
        this.cppRunner = new CppRunner(config, processRunner);
        this.pythonRunner = new PythonRunner(config, processRunner);
        this.nodeRunner = new NodeRunner(config, processRunner);
    }

    // @anchor: compiler_compileAuto
    // 自动识别语言并编译运行（auto 模式）
    public String compileAuto(Path filePath, String filename, boolean run) {
        try {
            if (filename.endsWith(".html") || filename.endsWith(".htm")) {
                return htmlPreviewer.previewHtml(filePath, filename);
            }

            Path projectDir = filePath.getParent();
            if (Files.exists(projectDir.resolve("pom.xml"))) {
                return mavenRunner.compileMaven(filePath, run);
            }
            if (filename.endsWith(".cpp") || filename.endsWith(".cc") || filename.endsWith(".cxx")) {
                return cppRunner.compileAndRunCpp(filePath, filename, run);
            }
            if (filename.endsWith(".py")) {
                return pythonRunner.runPython(filePath, filename, run);
            }
            if (filename.endsWith(".js")) {
                return nodeRunner.runNode(filePath, filename, run);
            }
            return javaRunner.compileJava(filePath, filename, run);

        } catch (IOException e) {
            logger.error("编译/运行过程异常", e);
            return "编译/运行异常: " + e.getMessage();
        }
    }

    // @anchor: compiler_previewHtml
    public String previewHtml(Path filePath, String filename) throws IOException {
        return htmlPreviewer.previewHtml(filePath, filename);
    }

    // @anchor: compiler_compileJava
    public String compileJava(Path filePath, String filename, boolean run) {
        return javaRunner.compileJava(filePath, filename, run);
    }

    // @anchor: compiler_compileMaven
    public String compileMaven(Path filePath, boolean run) throws IOException {
        return mavenRunner.compileMaven(filePath, run);
    }

    // @anchor: compiler_compileAndRunCpp
    public String compileAndRunCpp(Path filePath, String filename, boolean run) {
        return cppRunner.compileAndRunCpp(filePath, filename, run);
    }

    // @anchor: compiler_runPython
    public String runPython(Path filePath, String filename, boolean run) {
        return pythonRunner.runPython(filePath, filename, run);
    }

    // @anchor: compiler_runNode
    public String runNode(Path filePath, String filename, boolean run) {
        return nodeRunner.runNode(filePath, filename, run);
    }
}