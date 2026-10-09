package com.jaco.tool;

/** 只读准备阶段生成预览，确认后执行同一份计划。 */
public record PreparedToolCall(String preview, Action action) {
    @FunctionalInterface
    public interface Action {
        String execute() throws Exception;
    }

    public String execute() throws Exception {
        return action.execute();
    }
}
