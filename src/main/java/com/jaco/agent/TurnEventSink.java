package com.jaco.agent;

/**
 * loop 线程内的事件槽（ThreadLocal）。AgentHook 在 loop 线程里被回调，
 * 需要发事件（如 PermissionHook 的确认请求）或感知取消时从这里取当前 turn。
 */
public final class TurnEventSink {

    private static final ThreadLocal<TurnEventSink> CURRENT = new ThreadLocal<>();

    private final TurnHandle handle;

    private TurnEventSink(TurnHandle handle) {
        this.handle = handle;
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
        handle.emit(event);
    }

    public boolean cancelled() {
        return handle.isCancelled();
    }
}
