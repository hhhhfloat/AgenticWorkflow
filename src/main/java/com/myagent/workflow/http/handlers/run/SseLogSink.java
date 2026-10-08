// @anchor: sseLogSink_intro
// SSE 日志消费者：有界队列 + 独立写线程，把日志序列化到 HTTP 输出流（可选同时落文件）
package com.myagent.workflow.http.handlers.run;

import com.myagent.workflow.http.LogFileWriter;
import com.myagent.workflow.session.LogSink;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 日志消费者。
 * <p>
 * 职责：
 * 1. 为每个订阅端维护一个有界队列（默认容量 1000）
 * 2. 由一个 daemon 写线程串行地从队列取日志，写到 HTTP 输出流（可选同时写日志文件）
 * 3. 队列满时丢弃新日志并打印警告，绝不阻塞调用方（Agent 主线程）
 * 4. 写失败或 {@link #close()} 被调用后，线程退出、offer 返回 false
 * <p>
 * 线程模型：
 * - {@link #offer(String)}：由 Agent 线程调用，只做 queue.offer
 * - 写线程：从队列 take，实际写 socket / 文件
 */
// @anchor: sseLogSink_class
public class SseLogSink implements LogSink {

    private static final int QUEUE_CAPACITY = 1000;

    private final OutputStream out;
    private final Object outLock;
    private final LogFileWriter logWriter;   // 可为 null（/stream 只读订阅不落文件）
    private final String tag;

    // 队列元素：普通日志（转义后写）与控制事件（原样写）
    private record Event(String data, boolean raw) {
    }

    private final LinkedBlockingQueue<Event> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final Thread writerThread;

    // @anchor: sseLogSink_constructor
    public SseLogSink(OutputStream out, Object outLock, String tag, LogFileWriter logWriter) {
        this.out = out;
        this.outLock = outLock != null ? outLock : new Object();
        this.tag = tag;
        this.logWriter = logWriter;
        this.writerThread = new Thread(this::runWriter, "SseLogSink-" + tag);
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    // @anchor: sseLogSink_offer
    @Override
    public boolean offer(String message) {
        if (!alive.get()) return false;
        if (message == null) return true;
        boolean accepted = queue.offer(new Event(message, false));
        if (!accepted) {
            // 队列满：丢弃 + 打警告，绝不阻塞调用方
            System.err.println("[SseLogSink-" + tag + "] 队列已满，丢弃日志: "
                    + (message.length() > 80 ? message.substring(0, 80) + "..." : message));
        }
        return true;
    }


    // @anchor: sseLogSink_sendControl
    // 投递一条控制事件（usage/done/error/end）。走同一队列，保证顺序。
    // 队列持续满时短暂等待，避免控制事件被静默丢弃。
    public boolean sendControl(String data) {
        if (!alive.get()) return false;
        if (data == null) return true;
        try {
            boolean accepted = queue.offer(new Event(data, true), 5, TimeUnit.SECONDS);
            if (!accepted) {
                System.err.println("[SseLogSink-" + tag + "] 队列持续满，丢弃控制事件");
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // @anchor: sseLogSink_flush
    // 等待队列排空。返回时只是"投递完成"，不保证最后一条已彻底写出去。
    public void flush(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!queue.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // @anchor: sseLogSink_runWriter
    // 写线程主循环：从队列 take，写 socket + 可选写文件；写失败即退出
    private void runWriter() {
        try {
            while (alive.get()) {
                Event ev = queue.poll(500, TimeUnit.MILLISECONDS);
                if (ev == null) continue;   // 空闲轮询，用于响应 close
                // 1. 写日志文件（只对普通日志；控制事件不落文件）
                if (!ev.raw() && logWriter != null) {
                    try {
                        logWriter.write(ev.data());
                    } catch (IOException ignored) {
                    }
                }

                // 2. 写 SSE（socket I/O，可能阻塞或失败）
                try {
                    synchronized (outLock) {
                        out.write(toSseFrame(ev).getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                } catch (IOException e) {
                    alive.set(false);
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            alive.set(false);
        }
    }

    // @anchor: sseLogSink_toSseFrame
    // 构造一条 SSE 帧：控制事件为单行；普通日志走原生多行 data，换行规范化
    private static String toSseFrame(Event ev) {
        if (ev.raw()) {
            return "data: " + ev.data() + "\n\n";
        }
        // 规范化换行为 \n，避免 \r\n / \r 残留在行尾
        String normalized = ev.data().replace("\r\n", "\n").replace("\r", "\n");
        StringBuilder sb = new StringBuilder();
        for (String line : normalized.split("\n", -1)) {
            sb.append("data: ").append(line).append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    // @anchor: sseLogSink_close
    @Override
    public void close() {
        if (!alive.compareAndSet(true, false)) return;
        writerThread.interrupt();
        try {
            writerThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}