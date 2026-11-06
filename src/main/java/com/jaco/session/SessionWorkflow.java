package com.jaco.session;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashSet;
import java.util.List;
import java.util.function.UnaryOperator;

/** 标题更新共享同一工作流容器；快照不可变，持久化失败时回滚。 */
public final class SessionWorkflow {
    public enum Mode { NORMAL, PLAN }
    public record State(List<TodoItem> todos, Mode mode, String draft, long revision, String decision) {
        public State(List<TodoItem> todos) { this(todos, Mode.NORMAL, "", 0, "draft"); }
        public State {
            mode = mode == null ? Mode.NORMAL : mode;
            draft = draft == null ? "" : draft;
            decision = decision == null ? "draft" : decision;
            todos = todos == null ? List.of() : List.copyOf(todos);
            if (todos.size() > 50) throw new IllegalArgumentException("清单最多 50 项");
            var ids = new HashSet<String>();
            for (TodoItem todo : todos) if (!ids.add(todo.id())) throw new IllegalArgumentException("清单 ID 重复: " + todo.id());
        }
    }
    private State state;
    @JsonCreator public SessionWorkflow(@JsonProperty("state") State state) {
        this.state = state == null ? new State(List.of()) : state;
    }
    @JsonProperty("state") public synchronized State state() { return state; }

    public synchronized void update(UnaryOperator<State> change, Runnable save) {
        State previous = state;
        state = change.apply(previous);
        try { save.run(); }
        catch (RuntimeException e) { state = previous; throw e; }
    }

    public synchronized boolean planning() { return state.mode() == Mode.PLAN; }
    public synchronized String renderPlan() {
        return "当前模式：" + (planning() ? "计划" : "普通") + "；计划状态：" + state.decision()
                + (state.draft().isEmpty() ? "（暂无草稿）" : "\n" + state.draft());
    }
    public synchronized String renderTodos() {
        long done = state.todos().stream().filter(t -> t.status() == TodoItem.Status.completed).count();
        StringBuilder text = new StringBuilder("任务清单（已完成 " + done + "/" + state.todos().size() + "）");
        for (int i = 0; i < state.todos().size(); i++) {
            TodoItem todo = state.todos().get(i);
            text.append("\n").append(i + 1).append(". [").append(todo.status()).append("] ")
                    .append(todo.id()).append(" — ").append(todo.content().replaceAll("\\p{Cntrl}", " "));
        }
        return text.toString();
    }
}
