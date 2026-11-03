package com.jaco.agent.subagent;

/** 只保留有界文本和标量，不持有异常堆栈、上下文消息或执行资源。 */
public record SubagentResult(String id, ExitReason exitReason, String report, String partialFindings,
                             ErrorInfo error, RunStats stats) {
    public record ErrorInfo(String type, String message) { }
    public record RunStats(long elapsedMillis, int iterations, long promptTokens,
                           long completionTokens, long totalTokens, boolean usageAvailable) { }

    public com.jaco.tool.ToolResult executionResult() {
        var status = switch (exitReason) {
            case SUCCEEDED -> com.jaco.tool.ToolExecution.Status.SUCCEEDED;
            case CANCELLED, SHUTDOWN -> com.jaco.tool.ToolExecution.Status.CANCELLED;
            default -> com.jaco.tool.ToolExecution.Status.FAILED;
        };
        return new com.jaco.tool.ToolResult(status, toToolResult());
    }

    public String toToolResult() {
        if (exitReason == ExitReason.SUCCEEDED) return report;
        String text = switch (exitReason) {
            case CANCELLED -> "(子任务被中断)";
            case TIMED_OUT -> "子任务超时";
            case SHUTDOWN -> "子任务因应用退出而停止";
            default -> "子任务失败: " + (error == null ? exitReason : error.message());
        };
        return partialFindings == null || partialFindings.isBlank() ? text : text + "\n" + partialFindings;
    }
}
