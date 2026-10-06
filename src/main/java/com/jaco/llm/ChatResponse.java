package com.jaco.llm;

/** 一轮 LLM 调用的最终结果，用于 hook 与会话归档。 */
public record ChatResponse(Message message, Usage usage, String finishReason) {
}
