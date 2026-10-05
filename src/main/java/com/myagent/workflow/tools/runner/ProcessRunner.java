// @anchor: processRunner_tot_desc
// 进程执行器：安全环境 + 工作目录校验 + 输出异步 drain + 超时/阻塞检测 + 进程树回收
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

// @anchor: processRunner_class
// 进程执行器：统一处理外部进程的启动、监控、超时与回收
public class ProcessRunner {

    private static final Logger logger = LoggerFactory.getLogger(ProcessRunner.class);
    private static final Charset PROCESS_CHARSET = StandardCharsets.UTF_8;

    private final String sandboxDir;

    // @anchor: processRunner_constructor
    public ProcessRunner() {
        this.sandboxDir = AgentConfig.getSandboxDir();
    }

    // @anchor: processRunner_killTree
    // 递归杀掉进程及其全部后代（Windows 上 mvn.cmd 会启动 java，只杀父进程不管用）
    public static void killTree(Process p) {
        if (p == null) return;
        try {
            p.descendants().forEach(ph -> {
                try { ph.destroyForcibly(); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
        try { p.destroyForcibly(); } catch (Exception ignored) {}
    }

    // @anchor: processRunner_executeProcess
    // 执行外部进程并捕获输出（含超时/输入阻塞检测，finally 兜底回收）
    public ProcessResult executeProcess(ProcessBuilder pb, long timeoutSeconds, boolean detectStdinStall) {
        // ========== 安全加固 ==========
        try {
            secureEnvironment(pb);

            File dir = pb.directory();
            if (dir != null) {
                Path workDir = dir.toPath().toAbsolutePath().normalize();
                Path sandboxRoot = Paths.get(sandboxDir).toAbsolutePath().normalize();
                if (!workDir.startsWith(sandboxRoot)) {
                    logger.error("❌ 工作目录 {} 不在沙箱内，拒绝启动", workDir);
                    return new ProcessResult("安全拒绝：工作目录 " + workDir + " 不在沙箱内", -1, false, false);
                }
            } else {
                pb.directory(Paths.get(sandboxDir).toFile());
            }
        } catch (IOException e) {
            logger.error("❌ 安全加固设置失败: {}", e.getMessage(), e);
            return new ProcessResult("安全加固异常: " + e.getMessage(), -1, false, false);
        }

        logger.info("🚀 启动进程: command={}, directory={}", pb.command(), pb.directory());
        StringBuilder output = new StringBuilder();
        long startTime = System.currentTimeMillis();
        AtomicLong lastOutputTime = new AtomicLong(startTime);
        long lastLogTime = startTime;

        Process process = null;
        try {
            process = pb.start();
            ProcessRegistry.register(process);
            long pid = process.pid();
            logger.info("✅ 进程已启动, PID={}", pid);

            Process finalProcess = process;
            Thread reader = new Thread(() -> {
                try (var is = finalProcess.getInputStream()) {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        String chunk = new String(buffer, 0, len, PROCESS_CHARSET);
                        synchronized (output) {
                            output.append(chunk);
                        }
                        lastOutputTime.set(System.currentTimeMillis());
                        logger.trace("📝 读取到输出: {} 字符", chunk.length());
                    }
                } catch (IOException e) {
                    logger.warn("读取进程输出时发生异常: {}", e.getMessage());
                }
            });
            reader.setDaemon(true);
            reader.start();

            while (true) {
                long now = System.currentTimeMillis();
                long elapsed = now - startTime;
                long silentDuration = now - lastOutputTime.get();

                if (now - lastLogTime > 2000) {
                    lastLogTime = now;
                    logger.info("⏱️ 监控: elapsed={}s, silent={}s, alive={}",
                            elapsed / 1000, silentDuration / 1000, process.isAlive());
                }

                // 1. 总超时
                if (elapsed > timeoutSeconds * 1000L) {
                    logger.warn("⏱️ 总超时 ({}秒)，强制终止进程", timeoutSeconds);
                    killTree(process);
                    return new ProcessResult(output.toString(), -1, true, false);
                }

                // 2. 输入阻塞检测
                if (detectStdinStall && process.isAlive()) {
                    if (elapsed > 10000 && silentDuration > 10000) {
                        logger.warn("⛔ 检测到输入阻塞（{}秒无输出），强制终止进程", silentDuration / 1000);
                        killTree(process);
                        return new ProcessResult(
                                output.toString() + "\n⚠️ 检测到程序在 10 秒内未输出任何内容，疑似在等待标准输入（stdin）。",
                                -1, false, true);
                    }
                }

                // 3. 中断信号
                if (Thread.interrupted()) {
                    logger.info("⏹️ 收到中断信号，正在终止进程");
                    killTree(process);
                    return new ProcessResult(output.toString() + "\n⏹️ 用户已停止任务", -1, false, false);
                }

                // 4. 检查进程是否结束
                try {
                    int exitCode = process.exitValue();
                    logger.info("🏁 进程正常结束，退出码: {}", exitCode);
                    return new ProcessResult(output.toString(), exitCode, false, false);
                } catch (IllegalThreadStateException e) {
                    Thread.sleep(200);
                }
            }
        } catch (IOException e) {
            logger.error("❌ 启动进程异常: {}", e.getMessage(), e);
            return new ProcessResult("执行异常: " + e.getMessage(), -1, false, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("❌ 监控线程中断，将在 finally 清理子进程");
            return new ProcessResult("执行被中断", -1, false, false);
        } finally {
            if (process != null && process.isAlive()) {
                logger.info("🧹 兜底清理存活子进程, PID={}", process.pid());
                killTree(process);
            }
            ProcessRegistry.unregister(process);
        }
    }

    // @anchor: processRunner_secureEnvironment
    // 为子进程设置安全的环境变量（重定向临时目录与用户主目录到沙箱内）
    private void secureEnvironment(ProcessBuilder pb) throws IOException {
        Map<String, String> env = pb.environment();
        Path sandboxRoot = Paths.get(sandboxDir).toAbsolutePath().normalize();

        Path sandboxTmp = sandboxRoot.resolve("tmp");
        Files.createDirectories(sandboxTmp);
        String tmpPath = sandboxTmp.toString();

        env.put("TMPDIR", tmpPath);
        env.put("TEMP", tmpPath);
        env.put("TMP", tmpPath);

        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            env.put("USERPROFILE", sandboxRoot.toString());
        } else {
            env.put("HOME", sandboxRoot.toString());
        }
    }

    // @anchor: processRunner_result
    // 进程执行结果：输出、退出码、超时标记、输入阻塞标记
    public record ProcessResult(String output, int exitCode, boolean timedOut, boolean stalledOnInput) {}
}