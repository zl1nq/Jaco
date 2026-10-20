package com.jaco.agent;

/**
 * loop 线程内的事件槽（ThreadLocal）。AgentHook 在 loop 线程里被回调，
 * 需要发事件（如 PermissionHook 的确认请求）或感知取消时从这里取当前 turn。
 */
public final class TurnEventSink {

    private static final ThreadLocal<TurnEventSink> CURRENT = new ThreadLocal<>();

    private final TurnHandle handle;
    private final String taskId;
    private final java.util.function.BooleanSupplier stopped;

    private TurnEventSink(TurnHandle handle) {
        this(handle, null, handle::isCancelled);
    }

    private TurnEventSink(TurnHandle handle, String taskId, java.util.function.BooleanSupplier stopped) {
        this.handle = handle;
        this.taskId = taskId;
        this.stopped = stopped;
    }

    static void install(TurnHandle handle) {
        CURRENT.set(new TurnEventSink(handle));
    }

    static void clear() {
        CURRENT.remove();
    }

    public static TurnEventSink current() {
        return CURRENT.get();
    }

    public void emit(TurnEvent event) {
        if (taskId != null) {
            if (cancelled()) return;
            if (event instanceof TurnEvent.ToolCallStart e) event = new TurnEvent.ToolCallStart(e.tool(), e.summary(), taskId);
            else if (event instanceof TurnEvent.ToolCallEnd e) event = new TurnEvent.ToolCallEnd(e.tool(), e.ok(), e.summary(), taskId);
            else if (event instanceof TurnEvent.ToolPreview e) event = new TurnEvent.ToolPreview(e.text(), taskId);
            else if (event instanceof TurnEvent.ApprovalRequest e) event = new TurnEvent.ApprovalRequest(e.tool(), e.detail(), e.resolver(), taskId);
        }
        handle.emit(event);
    }

    public boolean cancelled() {
        return handle.isCancelled() || stopped.getAsBoolean();
    }

    /** 只桥接到创建它的父轮，不继承其他线程/新会话的事件槽。 */
    public static Binding bindSubagent(TurnEventSink parent, String taskId,
                                        java.util.function.BooleanSupplier cancelled) {
        var binding = new Binding(CURRENT.get());
        if (parent == null) CURRENT.remove();
        else CURRENT.set(new TurnEventSink(parent.handle, taskId, cancelled));
        return binding;
    }

    public String taskId() { return taskId; }

    public static final class Binding implements AutoCloseable {
        private final TurnEventSink previous;
        private Binding(TurnEventSink previous) { this.previous = previous; }
        @Override public void close() {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
