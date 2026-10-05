// @anchor: nodeRunner_tot_desc
// Node.js 脚本运行：走通用进程执行器
package com.myagent.workflow.tools.runner;

import com.myagent.workflow.core.config.AgentConfig;

import java.nio.file.Path;

// @anchor: nodeRunner_class
// Node.js 运行器
public class NodeRunner {

    private final AgentConfig config;
    private final ProcessRunner processRunner;

    // @anchor: nodeRunner_constructor
    public NodeRunner(AgentConfig config, ProcessRunner processRunner) {
        this.config = config;
        this.processRunner = processRunner;
    }

    // @anchor: nodeRunner_runNode
    // 运行 Node.js 脚本
    public String runNode(Path filePath, String filename, boolean run) {
        if (!run) {
            return "✅ Node.js 脚本已就绪（未运行）！\n文件: " + filename;
        }

        ProcessBuilder pb = new ProcessBuilder(
                config.nodeInterpreter(), filePath.toString());
        pb.directory(filePath.getParent().toFile());
        pb.redirectErrorStream(true);

        ProcessRunner.ProcessResult result = processRunner.executeProcess(pb, 30, true);

        if (result.stalledOnInput()) {
            return "⛔ Node.js 运行阻塞（疑似等待标准输入）: \n" + result.output() +
                    "\n💡 建议：请在代码中内置重定向输入（如使用文件流替代 process.stdin），或设置 run=false 仅检查语法。";
        }
        if (result.timedOut()) {
            return "⏱️ Node.js 运行超时（30秒），已强制终止。\n输出:\n" + result.output();
        }
        if (result.exitCode() != 0) {
            return "❌ Node.js 运行失败 (退出码 " + result.exitCode() + "):\n" + result.output();
        }
        return "✅ Node.js 运行成功！\n输出:\n" + result.output();
    }
}