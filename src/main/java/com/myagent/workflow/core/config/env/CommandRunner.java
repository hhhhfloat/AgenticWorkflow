// @anchor: commandRunner_intro
// 带超时的命令执行器：用于环境探测期的 reg query / vswhere / py 等短命令
package com.myagent.workflow.core.config.env;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

// @anchor: commandRunner_class
// 命令执行器：waitFor 带超时 + 超时强杀 + 异步读输出
public final class CommandRunner {
    private CommandRunner() {}

    // @anchor: commandRunner_run
    // 执行命令并返回输出（默认字符集）；超时强杀并抛 IOException
    public static String run(long timeoutSeconds, String... command) throws IOException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();

        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (var is = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = is.read(buf)) != -1) {
                    synchronized (output) {
                        output.append(new String(buf, 0, len, Charset.defaultCharset()));
                    }
                }
            } catch (IOException ignored) {}
        });
        reader.setDaemon(true);
        reader.start();

        try {
            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.descendants().forEach(ph -> { try { ph.destroyForcibly(); } catch (Exception ignored) {} });
                p.destroyForcibly();
                p.waitFor(2, TimeUnit.SECONDS);
                reader.join(500);
                throw new IOException("命令超时: " + String.join(" ", command));
            }
            reader.join(1000);
            synchronized (output) {
                return output.toString();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("命令被中断: " + String.join(" ", command), e);
        }
    }
}