package com.jaco.agent.subagent;

import com.jaco.llm.ChatStream;
import com.jaco.llm.Usage;
import com.jaco.tool.ProcessTree;

import java.util.UUID;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** 内部控制块。所有状态和完成竞争在同一监视器下裁定；资源关闭在锁外执行。 */
public final class SubagentControl {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SubagentControl.class);
    private final String id = UUID.randomUUID().toString();
    private SubagentSpec spec;
    private final String parentSessionId;
    private final String parentTurnId;
    private final SubagentProfile profile;
    private final Set<String> tools;
    private final long timeoutNanos;
    private final long deadline;
    private java.util.function.BooleanSupplier parentCancelled;
    private final long createdAt = System.currentTimeMillis();
    private final long createdNanos = System.nanoTime();
    private final AtomicReference<Process> process = new AtomicReference<>();
    private SubagentState state = SubagentState.CREATED;
    private SubagentPhase phase = SubagentPhase.IDLE;
    private StopReason stopReason;
    private long stopNanos;
    private Long startedAt;
    private Long exitedAt;
    private Thread worker;
    private Thread stopper;
    private ChatStream stream;
    private boolean completionAccepted;
    private boolean completionWon;
    private boolean cleanupOverdue;
    private SubagentManager.Completion completion;
    private String cleanupError;
    private String partialFindings = "";
    private int iterations;
    private long promptTokens, completionTokens, totalTokens;
    private boolean usageAvailable;

    SubagentControl(SubagentSpec spec, java.util.function.BooleanSupplier parentCancelled) {
        this.spec = spec;
        this.parentSessionId = spec.parentSessionId();
        this.parentTurnId = spec.parentTurnId();
        this.profile = spec.profile();
        this.tools = spec.tools();
        this.timeoutNanos = spec.timeout().toNanos();
        this.deadline = createdAt + spec.timeout().toMillis();
        this.parentCancelled = parentCancelled;
    }

    public String id() { return id; }
    public SubagentSpec spec() { return spec; }
    public AtomicReference<Process> processRef() { return process; }

    synchronized void queue() { state = SubagentState.QUEUED; }

    synchronized void attach(Thread thread) { worker = thread; }

    synchronized boolean begin() {
        if (stopReason != null) return false;
        state = SubagentState.RUNNING;
        startedAt = System.currentTimeMillis();
        return true;
    }

    public boolean isStopped() {
        java.util.function.BooleanSupplier parent;
        synchronized (this) { parent = parentCancelled; }
        if (parent != null && parent.getAsBoolean()) requestStop(StopReason.PARENT_CANCELLED);
        if (expired()) requestStop(StopReason.TIMEOUT);
        synchronized (this) { return stopReason != null; }
    }
    public synchronized void phase(SubagentPhase value) { phase = value; }
    public synchronized void iteration(int value) { iterations = value; }

    public synchronized void addUsage(Usage usage) {
        if (usage == null) return;
        usageAvailable = true;
        promptTokens += usage.promptTokens();
        completionTokens += usage.completionTokens();
        totalTokens += usage.totalTokens();
    }

    public synchronized void progress(String text) { partialFindings = limit(text, 4096); }

    public void registerStream(ChatStream value) {
        boolean stop;
        synchronized (this) {
            stream = value;
            stop = stopReason != null;
        }
        if (stop) closeStream(value);
    }

    public synchronized void clearStream(ChatStream value) {
        if (stream == value) stream = null;
    }

    public void requestStop(StopReason reason) {
        java.util.Objects.requireNonNull(reason);
        ChatStream active;
        Thread thread;
        synchronized (this) {
            if (state == SubagentState.EXITED || stopReason != null) return;
            stopReason = reason;
            stopNanos = System.nanoTime();
            state = SubagentState.CANCELLING;
            active = stream;
            thread = worker;
            // 关闭响应体也可能阻塞，不能阻塞调用取消的 TUI 或管理器关闭线程。
            stopper = Thread.ofVirtual().name("jaco-subagent-stop-" + id).unstarted(() -> {
                closeStream(active);
                closeProcess();
            });
            stopper.start();
        }
        // 即使资源关闭阻塞，也能中断执行线程并让取消调用及时返回。
        if (thread != null) thread.interrupt();
    }

    synchronized boolean stoppingResources() { return stopper != null && stopper.isAlive(); }

    void joinStopper(long millis) throws InterruptedException {
        Thread thread;
        synchronized (this) { thread = stopper; }
        if (thread != null) thread.join(millis);
    }

    synchronized void accept(SubagentManager.Completion value) {
        if (completionAccepted) return;
        // 截止时间不依赖监督线程恰好被调度的时刻。
        if (stopReason == null && expired()) {
            stopReason = StopReason.TIMEOUT;
            stopNanos = System.nanoTime();
            state = SubagentState.CANCELLING;
        }
        completionAccepted = true;
        completionWon = stopReason == null;
        completion = value;
    }

    void cleanup() {
        ChatStream active;
        synchronized (this) {
            phase = SubagentPhase.CLEANING_UP;
            active = stream;
        }
        closeStream(active);
        closeProcess();
        synchronized (this) { stream = null; }
    }

    private void closeStream(ChatStream value) {
        if (value == null) return;
        try { value.cancel(); }
        catch (RuntimeException e) { recordCleanupError(e); }
    }

    private void closeProcess() {
        Process active = process.get();
        if (active == null) return;
        try { ProcessTree.terminate(active); }
        catch (RuntimeException e) { recordCleanupError(e); }
        if (!active.isAlive()) process.compareAndSet(active, null);
    }

    private synchronized void recordCleanupError(RuntimeException e) {
        cleanupError = limit(e.getClass().getSimpleName() + ": " + e.getMessage(), 512);
        LOG.warn("子任务 {} 资源清理失败", id, e);
    }

    boolean expired() { return System.nanoTime() - createdNanos >= timeoutNanos; }

    synchronized void checkCleanupOverdue(long graceNanos) {
        if (stopReason != null && !cleanupOverdue && System.nanoTime() - stopNanos >= graceNanos) {
            cleanupOverdue = true;
            LOG.warn("子任务 {} 仍在取消中，尚未确认线程退出", id);
        }
    }

    /** 仅由监督线程在 join 确认工作线程退出后调用。 */
    synchronized SubagentResult exit() {
        if (completion == null) completion = stopReason == null
                ? SubagentManager.Completion.failed("执行线程未返回结果")
                : new SubagentManager.Completion(stopReason.exitReason(), "", null);
        ExitReason reason = completionWon || stopReason == null ? completion.reason() : stopReason.exitReason();
        var error = completion.error();
        if (cleanupError != null) {
            error = new SubagentResult.ErrorInfo("CleanupFailure", cleanupError);
            if (reason == ExitReason.SUCCEEDED) reason = ExitReason.FAILED;
        }
        state = SubagentState.EXITED;
        phase = SubagentPhase.IDLE;
        exitedAt = System.currentTimeMillis();
        worker = null;
        stopper = null;
        stream = null;
        process.set(null);
        spec = null; // 完成记录不保留原始 prompt。
        parentCancelled = null;
        var stats = new SubagentResult.RunStats((System.nanoTime() - createdNanos) / 1_000_000,
                iterations, promptTokens, completionTokens, totalTokens, usageAvailable);
        String report = completion.report();
        if (report != null && report.length() > 16000) report = limit(report, 15980) + "\n（报告已截断）";
        var result = new SubagentResult(id, reason, report == null ? "" : report, partialFindings, error, stats);
        completion = null;
        partialFindings = "";
        return result;
    }

    public synchronized SubagentHandle.Snapshot snapshot() {
        return new SubagentHandle.Snapshot(id, parentSessionId, parentTurnId, profile.id(), tools, state, phase,
                stopReason, createdAt, startedAt, exitedAt, deadline,
                iterations, totalTokens, usageAvailable, cleanupOverdue);
    }

    static String limit(String text, int length) {
        if (text == null) return "";
        if (text.length() <= length) return text;
        int end = length;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }
}
