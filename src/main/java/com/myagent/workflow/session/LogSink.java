// @anchor: logSink_intro
// 日志消费者接口：非阻塞投递 + 显式关闭，供 Session 多播使用
package com.myagent.workflow.session;

/**
 * 日志消费者。
 * <p>
 * 设计目标：让 {@link Session#log(String)} 在 Agent 主线程上只做微秒级的 offer，
 * 真正的 I/O（socket 写、文件写）由实现类的独立线程完成。
 * <p>
 * 契约：
 * - {@link #offer(String)} 必须非阻塞；返回 false 表示消费者已失效，Session 可将其摘除
 * - {@link #close()} 幂等，可从任意线程调用
 */
// @anchor: logSink_interface
public interface LogSink {

    /**
     * 非阻塞投递一条日志。
     *
     * @return true 表示已接收（可能入队或已丢弃），false 表示消费者已关闭，应从列表摘除
     */
    boolean offer(String message);

    /**
     * 关闭消费者，释放写线程与队列。幂等。
     */
    void close();
}