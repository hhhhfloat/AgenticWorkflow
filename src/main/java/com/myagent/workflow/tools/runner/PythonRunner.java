// @anchor: pythonRunner_intro
// Python 脚本运行：自动识别 GUI 程序，非 GUI 走通用进程执行器
package com.myagent.workflow.tools.runner;

import com.myagent.workflow.core.ProcessRegistry;
import com.myagent.workflow.core.config.AgentConfig;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

// @anchor: pythonRunner_class
// Python 运行器：检测 GUI 库后选择启动策略
public class PythonRunner {

    private static final Charset PROCESS_CHARSET = StandardCharsets.UTF_8;

    private final AgentConfig config;
    private final ProcessRunner processRunner;

    // @anchor: pythonRunner_constructor
    public PythonRunner(AgentConfig config, ProcessRunner processRunner) {
        this.config = config;
        this.processRunner = processRunner;
    }

    // @anchor: pythonRunner_runPython
    // 运行 Python 脚本
    public String runPython(Path filePath, String filename, boolean run) {
        if (!run) {
            return "✅ Python 脚本已就绪（未运行）！\n文件: " + filename;
        }

        boolean isGui = detectGui(filePath);

        ProcessBuilder pb = new ProcessBuilder(
                config.pythonInterpreter(), filePath.toString());
        pb.directory(filePath.getParent().toFile());
        pb.redirectErrorStream(true);

        if (isGui) {
            return runGuiProcess(pb);
        }

        ProcessRunner.ProcessResult result = processRunner.executeProcess(pb, 30, true);

        if (result.stalledOnInput()) {
            return "⛔ 运行阻塞（疑似等待标准输入）: \n" + result.output() +
                    "\n💡 建议：请在代码中内置重定向输入（如使用文件流替代 input()），或设置 run=false 仅检查语法。";
        }
        if (result.timedOut()) {
            return "⏱️ Python 运行超时（30秒），已强制终止。\n输出:\n" + result.output();
        }
        if (result.exitCode() != 0) {
            return "❌ Python 运行失败 (退出码 " + result.exitCode() + "):\n" + result.output();
        }
        return "✅ Python 运行成功！\n输出:\n" + result.output();
    }

    // @anchor: pythonRunner_detectGui
    // 检测脚本是否引用常见 GUI 库
    private boolean detectGui(Path filePath) {
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            return content.contains("import pygame") || content.contains("from pygame")
                    || content.contains("import tkinter") || content.contains("from tkinter")
                    || content.contains("import PyQt") || content.contains("from PyQt")
                    || content.contains("import PySide") || content.contains("from PySide")
                    || content.contains("import wx") || content.contains("from wx");
        } catch (IOException e) {
            return false;
        }
    }

    // @anchor: pythonRunner_runGuiProcess
    // GUI 分支：登记进程 + 异步 drain stdout + 2 秒检测窗口
    private String runGuiProcess(ProcessBuilder pb) {
        try {
            Process p = pb.start();
            ProcessRegistry.register(p);

            // 异步 drain stdout：GUI 程序可能持续打印，同步读会阻塞
            StringBuilder guiOut = new StringBuilder();
            Thread drainer = new Thread(() -> {
                try (var is = p.getInputStream()) {
                    byte[] buf = new byte[1024];
                    int len;
                    while ((len = is.read(buf)) != -1) {
                        synchronized (guiOut) {
                            guiOut.append(new String(buf, 0, len, PROCESS_CHARSET));
                        }
                    }
                } catch (IOException ignored) {}
            });
            drainer.setDaemon(true);
            drainer.start();

            boolean exited = p.waitFor(2, TimeUnit.SECONDS);
            if (!exited) {
                // 保留注册：主服务退出时由 shutdown hook 统一回收
                return "✅ Python GUI 程序已启动！\n窗口应该已弹出，请查看。\n注意：该进程仍在后台运行，如需关闭请手动终止。";
            } else {
                ProcessRegistry.unregister(p);
                drainer.join(500);
                String output;
                synchronized (guiOut) { output = guiOut.toString(); }
                return "⚠️ GUI 程序启动后立即退出，可能有错误。\n输出:\n" + output;
            }
        } catch (IOException | InterruptedException e) {
            return "❌ 启动 GUI 程序异常: " + e.getMessage();
        }
    }
}