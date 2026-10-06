package com.jaco.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OpenAI Chat Completions 线协议形状的消息。
 * v1 只有 system/user/assistant 三种纯文本消息在流转，
 * 但 toolCalls / toolCallId 字段从第一天就在模型里，工具调用接入时无需重构。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Message(
        Role role,
        String content,
        @JsonProperty("tool_calls") List<ToolCall> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId,
        String name) {

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, null, null, null);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content, null, null, null);
    }

    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content, null, null, null);
    }

    /** 工具结果消息（M1 接入工具调用时启用）。 */
    public static Message toolResult(String toolCallId, String content) {
        return new Message(Role.TOOL, content, null, toolCallId, null);
    }
}
