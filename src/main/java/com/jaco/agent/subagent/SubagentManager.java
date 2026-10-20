package com.jaco.agent.subagent;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** 第一阶段仅准入一个活动任务；监督线程保留卡在取消中的任务及其槽位。 */
public final class SubagentManager implements AutoCloseable {
    @FunctionalInterface
    public interface Work { Completion run(SubagentControl control) throws Exception; }

    public record Completion(ExitReason reason, String report, SubagentResult.ErrorInfo error) {
        public Completion { java.util.Objects.requireNonNull(reason); }
        public static Completion succeeded(String report) { return new Completion(ExitReason.SUCCEEDED, report, null); }
        public static Completion failed(String message) {
            return new Completion(ExitReason.FAILED, "", new SubagentResult.ErrorInfo("TaskFailure", SubagentControl.limit(message, 512)));
        }
    }

    private final Duration cancelGrace;
    private final int retainedLimit;
    private final Map<String, SubagentHandle> handles = new LinkedHashMap<>();
    private final Map<String, SubagentControl> active = new LinkedHashMap<>();
    private boolean closed;

    public SubagentManager() { this(Duration.ofSeconds(2), 32); }

    public SubagentManager(Duration cancelGrace, int retainedLimit) {
        if (cancelGrace.isNegative() || retainedLimit < 1) throw new IllegalArgumentException("无效的子任务保留策略");
        this.cancelGrace = cancelGrace;
        this.retainedLimit = retainedLimit;
    }

    public Duration cancelGrace() { return cancelGrace; }

    public synchronized SubagentHandle start(SubagentSpec spec, BooleanSupplier parentCancelled, Work work) {
        java.util.Objects.requireNonNull(spec);
        java.util.Objects.requireNonNull(parentCancelled);
        java.util.Objects.requireNonNull(work);
        if (closed) throw new IllegalStateException("子任务管理器已关闭");
        if (!active.isEmpty()) {
            var running = active.values().iterator().next().snapshot();
            throw new IllegalStateException("已有活动子任务 " + running.id() + "（会话 "
                    + running.parentSessionId() + "，" + running.state() + "），请等待其退出");
        }
        boolean alreadyCancelled = parentCancelled.getAsBoolean();
        var control = new SubagentControl(spec, parentCancelled);
        var handle = new SubagentHandle(control);
        control.queue();
        handles.put(handle.id(), handle);
        active.put(handle.id(), control);
        Thread worker = Thread.ofVirtual().name("jaco-subagent-" + handle.id()).unstarted(() -> {
            try {
                if (!control.isStopped() && control.begin()) control.accept(work.run(control));
            } catch (Throwable e) {
                control.accept(Completion.failed(e.getClass().getSimpleName() + ": " + e.getMessage()));
            } finally {
                control.cleanup();
            }
        });
        control.attach(worker);
        if (alreadyCancelled) control.requestStop(StopReason.PARENT_CANCELLED);
        worker.start();
        Thread.ofVirtual().name("jaco-subagent-supervisor-" + handle.id()).start(() -> {
            while (worker.isAlive() || control.stoppingResources()) {
                try {
                    if (parentCancelled.getAsBoolean()) control.requestStop(StopReason.PARENT_CANCELLED);
                    if (control.expired()) control.requestStop(StopReason.TIMEOUT);
                    control.checkCleanupOverdue(cancelGrace.toNanos());
                    if (worker.isAlive()) worker.join(25);
                    else control.joinStopper(25);
                } catch (InterruptedException e) {
                    control.requestStop(StopReason.SHUTDOWN);
                } catch (RuntimeException e) {
                    control.requestStop(StopReason.PARENT_CANCELLED);
                }
            }
            synchronized (SubagentManager.this) {
                var result = control.exit();
                active.remove(handle.id());
                handle.publish(result);
                // 按实际完成顺序淘汰最早退出的记录。
                handles.remove(handle.id());
                handles.put(handle.id(), handle);
                while (handles.size() > retainedLimit) {
                    String oldest = handles.entrySet().stream()
                            .filter(e -> e.getValue().result() != null).map(Map.Entry::getKey).findFirst().orElse(null);
                    if (oldest == null) break;
                    handles.remove(oldest);
                }
                SubagentManager.this.notifyAll();
            }
        });
        return handle;
    }

    public synchronized List<SubagentHandle.Snapshot> snapshots() {
        return handles.values().stream().map(SubagentHandle::snapshot).toList();
    }

    public synchronized SubagentHandle find(String id) { return handles.get(id); }

    public void cancelTurn(String turnId, StopReason reason) {
        cancelMatching(c -> c.spec().parentTurnId().equals(turnId), reason);
    }

    public void cancelSession(String sessionId) {
        cancelMatching(c -> c.spec().parentSessionId().equals(sessionId), StopReason.SESSION_CHANGED);
    }

    private void cancelMatching(java.util.function.Predicate<SubagentControl> predicate, StopReason reason) {
        List<SubagentControl> targets;
        synchronized (this) { targets = active.values().stream().filter(predicate).toList(); }
        targets.forEach(c -> c.requestStop(reason));
    }

    @Override public void close() {
        synchronized (this) { closed = true; }
        cancelMatching(c -> true, StopReason.SHUTDOWN);
        long start = System.nanoTime();
        boolean interrupted = Thread.interrupted();
        synchronized (this) {
            while (!active.isEmpty()) {
                long remaining = cancelGrace.toNanos() - (System.nanoTime() - start);
                if (remaining <= 0) break;
                try { TimeUnit.NANOSECONDS.timedWait(this, remaining); }
                catch (InterruptedException e) { interrupted = true; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
