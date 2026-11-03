package com.jaco.tool;

/** 只读准备阶段生成预览，确认后执行同一份计划。 */
public record PreparedToolCall(String preview, Action action) {
    @FunctionalInterface
    public interface Action {
        ToolResult execute() throws Exception;
    }

    @FunctionalInterface
    public interface TextAction { String execute() throws Exception; }
    public static PreparedToolCall text(String preview, TextAction action) {
        return new PreparedToolCall(preview, () -> ToolResult.success(action.execute()));
    }
    public ToolResult execute() throws Exception {
        return action.execute();
    }
}
