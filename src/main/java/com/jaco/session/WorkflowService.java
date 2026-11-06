package com.jaco.session;

import java.util.List;
import java.util.function.BooleanSupplier;

/** 每次调用绑定所属会话，防止迟到的调用污染新会话。 */
public final class WorkflowService {
    private final Session owner;
    private boolean stopTurn;
    private final SessionStore store;
    private final BooleanSupplier active;
    private final java.util.function.Supplier<List<String>> blockers;

    public WorkflowService(Session owner, SessionStore store, BooleanSupplier active) {
        this(owner, store, active, List::of);
    }
    public WorkflowService(Session owner, SessionStore store, BooleanSupplier active,
                           java.util.function.Supplier<List<String>> blockers) {
        this.blockers = blockers;
        this.owner = owner;
        this.store = store;
        this.active = active;
    }
    private void checkActive() {
        if (!active.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IllegalStateException("工作流调用已过期或取消");
    }
    public String sessionId() { return owner.id(); }
    public void stopTurn() { stopTurn = true; }
    public boolean shouldStopTurn() { return stopTurn; }
    public long saveDraft(String markdown) {
        checkActive();
        if (markdown == null || markdown.isBlank() || markdown.length() > 20000)
            throw new IllegalArgumentException("计划必须为非空 Markdown，最多 20000 个 UTF-16 字符");
        synchronized (owner.workflow()) {
            owner.workflow().update(s -> {
                if (s.mode() != SessionWorkflow.Mode.PLAN) throw new IllegalStateException("请先进入计划模式");
                return new SessionWorkflow.State(s.todos(), s.mode(), markdown, s.revision() + 1, "draft");
            }, () -> { checkActive(); store.saveWorkflow(owner); });
            return owner.workflow().state().revision();
        }
    }
    public boolean currentDraft(long revision) {
        var state = owner.workflow().state();
        return active.getAsBoolean() && state.mode() == SessionWorkflow.Mode.PLAN && state.revision() == revision;
    }
    public String decidePlan(long revision, boolean approved) {
        checkActive();
        owner.workflow().update(s -> {
            if (s.mode() != SessionWorkflow.Mode.PLAN || s.revision() != revision)
                throw new IllegalStateException("计划确认已过期");
            return new SessionWorkflow.State(s.todos(), approved ? SessionWorkflow.Mode.NORMAL : s.mode(),
                    s.draft(), s.revision() + 1, approved ? "approved" : "rejected");
        }, () -> { checkActive(); store.saveWorkflow(owner); });
        if (!approved) stopTurn();
        return approved ? "用户已批准计划，继续执行；文件和命令仍需独立授权。" : "用户拒绝计划；保留草稿与计划模式，等待补充意见。";
    }
    public String cancelPlan() {
        checkActive();
        if (!planning()) return owner.workflow().renderPlan();
        owner.workflow().update(s -> new SessionWorkflow.State(s.todos(), SessionWorkflow.Mode.NORMAL,
                s.draft(), s.revision() + 1, "cancelled"), () -> { checkActive(); store.saveWorkflow(owner); });
        return owner.workflow().renderPlan();
    }
    public boolean planning() { return owner.workflow().planning(); }
    public String enterPlan() {
        checkActive();
        if (planning()) return owner.workflow().renderPlan();
        List<String> running = blockers.get();
        if (!running.isEmpty()) throw new IllegalStateException("请先等待或处理后台任务：" + String.join(", ", running));
        owner.workflow().update(s -> new SessionWorkflow.State(s.todos(), SessionWorkflow.Mode.PLAN,
                s.draft(), s.revision() + 1, "draft"), () -> { checkActive(); store.saveWorkflow(owner); });
        return owner.workflow().renderPlan();
    }
    public String readTodos() { checkActive(); return owner.workflow().renderTodos(); }
    public String writeTodos(List<TodoItem> todos) {
        checkActive();
        owner.workflow().update(previous -> new SessionWorkflow.State(todos, previous.mode(), previous.draft(), previous.revision(), previous.decision()), () -> {
            checkActive();
            store.saveWorkflow(owner);
        });
        return owner.workflow().renderTodos();
    }
}
