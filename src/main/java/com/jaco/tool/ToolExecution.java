package com.jaco.tool;

/** 实际工具执行结果；拒绝、取消和失败不被误报成成功。 */
public record ToolExecution(Status status, String result) {
    public enum Status { SUCCEEDED, DENIED, CANCELLED, FAILED }
    public boolean ok() { return status == Status.SUCCEEDED; }
}
