package com.jaco.llm;

/**
 * 流式响应的事件。agent loop 将来在消费 Delta 的同时跨 chunk 累积 tool_call 的
 * JSON 参数，收到 Done（finish_reason == "tool_calls"）后触发执行——这是 M1 的接线点。
 */
public sealed interface StreamChunk {

    record Delta(String text) implements StreamChunk {
    }

    /** finishReason 可能为 null（部分服务商不发或连接中断）。 */
    record Done(String finishReason, Usage usage) implements StreamChunk {
    }

    record Error(Throwable cause, String message) implements StreamChunk {
    }
}
