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
import java.util.concurrent.atomic.AtomicLong;

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

    // @anchor: sseLogSink_drainFields
    // 排空标记：writer 每轮循环结束检查队列是否空，空则置 drained=true 并通知 flush
    private final Object drainLock = new Object();
    private boolean drained = true;   // 初始视为空队列

    // B1：丢弃计数。慢消费者下队列满导致丢弃，首次丢弃时打详细日志，
    // 后续静默计数，close() 时汇总。
    private final AtomicLong droppedCount = new AtomicLong(0);

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
            // 队列满：丢弃 + 计数，绝不阻塞调用方。
            // 只打一次详细日志避免刷屏，后续计数在 close() 时汇总。
            long dropped = droppedCount.incrementAndGet();
            if (dropped == 1) {
                System.err.println("[SseLogSink-" + tag + "] 队列已满，开始丢弃日志（后续静默计数）: "
                        + (message.length() > 80 ? message.substring(0, 80) + "..." : message));
            }
        }
        return true;
    }


    // @anchor: sseLogSink_sendControl
    // 投递一条控制事件（usage/done/error/end/context）。非阻塞：
    // 队列满时抢占式丢弃一条最老的日志（普通日志或旧控制事件），保证 Agent 收尾线程
    // 不被慢客户端阻塞。返回 false 仅表示 sink 已关闭或两次投递均失败。
    public boolean sendControl(String data) {
        if (!alive.get()) return false;
        if (data == null) return true;
        Event ev = new Event(data, true);
        if (queue.offer(ev)) return true;
        // 队列满：抢占式丢弃一条最老的日志腾位（常量时间，不阻塞）
        queue.poll();
        if (queue.offer(ev)) return true;
        long dropped = droppedCount.incrementAndGet();
        System.err.println("[SseLogSink-" + tag + "] 队列持续满，丢弃控制事件（累计丢弃 "
                + dropped + "）: "
                + (data.length() > 60 ? data.substring(0, 60) + "..." : data));
        return false;
    }

    // @anchor: sseLogSink_flush
    // 等待写线程处理完队列里的所有事件（含正在写的那一条）。
    // 返回时要么 drained=true（真的写出去了），要么超时。
    public void flush(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (drainLock) {
            drained = false;
            while (!drained && System.currentTimeMillis() < deadline) {
                try {
                    drainLock.wait(Math.max(1, deadline - System.currentTimeMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // @anchor: sseLogSink_runWriter
    // 写线程主循环：从队列 take，写 socket + 可选写文件；写失败即退出。每轮结束检查队列是否空，通知 flush
    private void runWriter() {
        try {
            while (alive.get()) {
                Event ev = queue.poll(500, TimeUnit.MILLISECONDS);
                if (ev != null) {
                    if (!writeEvent(ev)) {
                        break;   // socket 写失败：退出
                    }
                }
                if (queue.isEmpty()) {
                    synchronized (drainLock) {
                        drained = true;
                        drainLock.notifyAll();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            alive.set(false);
            synchronized (drainLock) {
                drained = true;
                drainLock.notifyAll();
            }
        }
    }

    // @anchor: sseLogSink_writeEvent
    // 写单条事件：先落文件（仅普通日志），再写 socket
    private boolean writeEvent(Event ev) {
        if (!ev.raw() && logWriter != null) {
            try {
                logWriter.write(ev.data());
            } catch (IOException ignored) {
            }
        }
        try {
            synchronized (outLock) {
                out.write(toSseFrame(ev).getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            return true;
        } catch (IOException e) {
            return false;
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
        // 先给 writer 一个短暂窗口把队列里的剩余事件写完（可能含 done/end）。
        // 此时 alive 仍为 true，runWriter 正常从队列 poll 并写 socket。
        // flush(3000) 已经等过一轮，这里 500ms 是"最后的机会"，不再等 3 秒。
        long deadline = System.currentTimeMillis() + 500;
        while (!queue.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // 统计剩余：慢连接下 writer 可能卡在 out.write，队列不减少
        int remaining = queue.size();
        if (remaining > 0) {
            System.err.println("[SseLogSink-" + tag + "] 关闭时仍有 " + remaining
                    + " 条事件未写出（慢连接），已放弃");
        }
        if (!alive.compareAndSet(true, false)) return;
        writerThread.interrupt();
        try {
            writerThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 本次任务因客户端慢累计丢弃的事件数（> 0 时汇总，便于排障）
        long totalDropped = droppedCount.get();
        if (totalDropped > 0) {
            System.err.println("[SseLogSink-" + tag + "] 本次任务因客户端慢累计丢弃 "
                    + totalDropped + " 条事件");
        }
    }
}