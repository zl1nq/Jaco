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
    record ToolCallStart(String tool, String summary) implements TurnEvent {
    }

    record ToolCallEnd(String tool, boolean ok, String summary) implements TurnEvent {
    }

    /** 文件工具只读准备后的修改预览，先于权限确认和写入。 */
    record ToolPreview(String text) implements TurnEvent {
    }

    /** 需要用户确认。loop 线程阻塞在 resolver 上，TUI 用 "y"/"a"/"n" 应答。 */
    record ApprovalRequest(String tool, String detail, Consumer<String> resolver) implements TurnEvent {
    }

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
