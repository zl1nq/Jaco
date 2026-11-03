package com.jaco.tool;

import java.util.Objects;

/** 明确执行状态与模型可见文本，状态不依赖展示文案。 */
public record ToolResult(ToolExecution.Status status, String text) {
    public ToolResult { Objects.requireNonNull(status); Objects.requireNonNull(text); }
    public static ToolResult success(String text) { return new ToolResult(ToolExecution.Status.SUCCEEDED, text); }
    public static ToolResult failure(String text) { return new ToolResult(ToolExecution.Status.FAILED, text); }
    public static ToolResult cancelled(String text) { return new ToolResult(ToolExecution.Status.CANCELLED, text); }
}
