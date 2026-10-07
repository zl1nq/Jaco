package com.jaco.agent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一个 turn 的拉取句柄：TUI 从这里消费事件；cancel() 中断本轮
 * （取消进行中的 LLM 流、杀掉进行中的工具子进程、唤醒等待确认的 loop）。
 */
public final class TurnHandle implements AutoCloseable {

    private static final TurnEvent CANCELLED_SENTINEL =
            new TurnEvent.Done(null, null, true, false, null, 0);

    private final BlockingQueue<TurnEvent> queue = new ArrayBlockingQueue<>(512);
    private final AtomicReference<com.jaco.llm.ChatStream> currentStream = new AtomicReference<>();
    /** 工具执行期间挂当前子进程，cancel() 杀之；与 ToolContext 共享同一引用。 */
    private final AtomicReference<Process> currentProcess = new AtomicReference<>();

    public AtomicReference<Process> processRef() {
        return currentProcess;
    }

    private volatile Thread loopThread;
    private volatile boolean cancelled;

    /** 供 loop 线程发布事件；队列满时阻塞（TUI 慢一点没关系）。 */
    void emit(TurnEvent event) {
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void setCurrentStream(com.jaco.llm.ChatStream stream) {
        currentStream.set(stream);
    }

    void setLoopThread(Thread thread) {
        this.loopThread = thread;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** 带超时拉取事件，超时返回 null。 */
    public TurnEvent poll(long timeoutMillis) throws InterruptedException {
        return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /** 中断本轮：立即生效于流消费、命令执行与权限等待。 */
    public void cancel() {
        cancelled = true;
        var stream = currentStream.get();
        if (stream != null) {
            stream.cancel();
        }
        var process = currentProcess.get();
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
        Thread loop = loopThread;
        if (loop != null) {
            loop.interrupt();
        }
        queue.clear();
        queue.offer(CANCELLED_SENTINEL);
    }

    @Override
    public void close() {
        cancel();
    }
}
