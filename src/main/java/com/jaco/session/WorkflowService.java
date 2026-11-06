package com.jaco.session;

import java.util.List;
import java.util.function.BooleanSupplier;

/** 每次调用绑定所属会话，防止迟到的调用污染新会话。 */
public final class WorkflowService {
    private final Session owner;
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
