package com.jaco.agent;

/** 从终端测试发布真实轮次事件，不暴露生产代码的事件写入口。 */
public final class TestTurnEvents {
    private TestTurnEvents() { }
    public static void emit(TurnHandle handle, TurnEvent event) { handle.emit(event); }
}
