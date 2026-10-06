package com.jaco.llm;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 一次流式调用的拉取句柄。pull 模型：调用方以 next()/poll() 拉取事件，
 * 用 poll(timeout) 的空档做 spinner / 按键检测；cancel() 中断底层 HTTP 请求。
 * Done/Error 是终结事件，其后队列不会再有新事件。
 */
public final class ChatStream implements AutoCloseable {

    private static final StreamChunk INTERRUPTED = new StreamChunk.Error(null, "cancelled by user");

    private final BlockingQueue<StreamChunk> queue;
    private final CompletableFuture<?> httpFuture;
    private volatile boolean cancelled;

    ChatStream(CompletableFuture<?> httpFuture) {
        this.queue = new ArrayBlockingQueue<>(512);
        this.httpFuture = httpFuture;
    }

    BlockingQueue<StreamChunk> queue() {
        return queue;
    }

    /** 阻塞拉取下一个事件。 */
    public StreamChunk next() throws InterruptedException {
        return queue.take();
    }

    /** 带超时拉取，超时返回 null（此时流未结束）。 */
    public StreamChunk poll(long timeoutMillis) throws InterruptedException {
        return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /** 中断本次生成：取消 HTTP 请求并唤醒所有等待者。 */
    public void cancel() {
        cancelled = true;
        httpFuture.cancel(true);
        queue.clear();
        queue.offer(INTERRUPTED);
    }

    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void close() {
        cancel();
    }
}
