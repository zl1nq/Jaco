package com.jaco.agent.subagent;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 等待超时只结束本次等待，不改变任务状态；不暴露可修改的完成 Future。 */
public final class SubagentHandle {
    public record Snapshot(String id, String parentSessionId, String parentTurnId, String profile, Set<String> tools,
                           SubagentState state, SubagentPhase phase, StopReason stopReason,
                           long createdAt, Long startedAt, Long exitedAt, long deadline,
                           int iterations, long totalTokens, boolean usageAvailable, boolean cleanupOverdue,
                           String auditFile, boolean runInBackground) { }

    private final SubagentControl control;
    private final CompletableFuture<SubagentResult> completion = new CompletableFuture<>();

    SubagentHandle(SubagentControl control) { this.control = control; }

    public String id() { return control.id(); }
    public Snapshot snapshot() { return control.snapshot(); }
    public void cancel() { control.requestStop(StopReason.USER_CANCELLED); }
    public void requestStop(StopReason reason) { control.requestStop(reason); }

    public SubagentResult await() throws InterruptedException {
        try {
            return completion.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("子任务结果发布失败", e.getCause());
        }
    }

    public SubagentResult await(Duration timeout) throws InterruptedException, TimeoutException {
        try {
            return completion.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("子任务结果发布失败", e.getCause());
        }
    }

    public SubagentResult result() { return completion.getNow(null); }

    public void onComplete(java.util.function.Consumer<SubagentResult> listener) {
        completion.thenAccept(listener);
    }

    void publish(SubagentResult result) { completion.complete(result); }
}
