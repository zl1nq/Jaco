package com.jaco.session;

import java.util.List;
import java.util.function.BooleanSupplier;

/** 每次调用绑定所属会话，防止迟到的调用污染新会话。 */
public final class WorkflowService {
    private final Session owner;
    private final SessionStore store;
    private final BooleanSupplier active;

    public WorkflowService(Session owner, SessionStore store, BooleanSupplier active) {
        this.owner = owner;
        this.store = store;
        this.active = active;
    }
    private void checkActive() {
        if (!active.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IllegalStateException("工作流调用已过期或取消");
    }
    public String readTodos() { checkActive(); return owner.workflow().renderTodos(); }
    public String writeTodos(List<TodoItem> todos) {
        checkActive();
        owner.workflow().update(previous -> new SessionWorkflow.State(todos), () -> {
            checkActive();
            store.saveWorkflow(owner);
        });
        return owner.workflow().renderTodos();
    }
}
