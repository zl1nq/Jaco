package com.jaco.agent;

import com.jaco.llm.Usage;

import java.util.function.Consumer;

/**
 * agent loop 对外吐出的 turn 事件（pull 模型，TUI 是第一个消费者，
 * 将来的 Web 前端是第二个）。
 */
public sealed interface TurnEvent {

    /** assistant 正文的增量。 */
    record Delta(String text) implements TurnEvent {
    }

    /** 即将执行一个工具（已过权限闸门或正在等待确认）。 */
    record ToolCallStart(String tool, String summary, String taskId) implements TurnEvent {
        public ToolCallStart(String tool, String summary) { this(tool, summary, null); }
    }

    record ToolCallEnd(String tool, boolean ok, String summary, String taskId) implements TurnEvent {
        public ToolCallEnd(String tool, boolean ok, String summary) { this(tool, ok, summary, null); }
    }

    /** 文件工具只读准备后的修改预览，先于权限确认和写入。 */
    record ToolPreview(String text, String taskId) implements TurnEvent {
        public ToolPreview(String text) { this(text, null); }
    }

    /** 需要用户确认。loop 线程阻塞在 resolver 上，TUI 用 "y"/"a"/"n" 应答。 */
    record ApprovalRequest(String tool, String detail, Consumer<String> resolver, String taskId) implements TurnEvent {
        public ApprovalRequest(String tool, String detail, Consumer<String> resolver) { this(tool, detail, resolver, null); }
    }

    /** 主任务提问；过期请求不能展示或接收回答。提交必须来自用户动作，静默不触发默认。 */
    record QuestionRequest(String requestId, com.jaco.tool.UserQuestion question,
                           Consumer<com.jaco.tool.UserQuestion.Submission> resolver,
                           java.util.function.BooleanSupplier active) implements TurnEvent {}

    /** 非正文的状态提示（如上下文压缩进度）。 */
    record Notice(String text) implements TurnEvent {
    }

    record Done(Usage usage,
                String finishReason,
                boolean interrupted,
                boolean abortedByHook,
                Throwable error,
                int iterations) implements TurnEvent {
    }
}
