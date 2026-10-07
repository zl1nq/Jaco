package com.jaco.agent;

import com.jaco.llm.StreamChunk;
import com.jaco.llm.ToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** 按 index 累积流式 tool_calls 增量片段，拼成完整 ToolCall。 */
final class ToolCallAccumulator {

    private record Slot(String id, String name, StringBuilder args) {
        Slot(String id, String name) {
            this(id, name, new StringBuilder());
        }
    }

    private final TreeMap<Integer, Slot> slots = new TreeMap<>();

    boolean isEmpty() {
        return slots.isEmpty();
    }

    void add(StreamChunk.ToolCallDelta delta) {
        slots.computeIfAbsent(delta.index(), i -> new Slot(delta.id(), delta.name()));
        Slot slot = slots.get(delta.index());
        if (delta.id() != null) {
            slot = mergeId(slot, delta.id());
        }
        if (delta.name() != null) {
            slot = mergeName(slot, delta.name());
        }
        if (delta.argumentsFragment() != null) {
            slot.args().append(delta.argumentsFragment());
        }
        slots.put(delta.index(), slot);
    }

    private Slot mergeId(Slot slot, String id) {
        return new Slot(id, slot.name(), slot.args());
    }

    private Slot mergeName(Slot slot, String name) {
        return new Slot(slot.id(), name, slot.args());
    }

    /** arguments 为空的条目补 "{}"，保证下游 JSON 解析不炸。 */
    List<ToolCall> build() {
        List<ToolCall> calls = new ArrayList<>();
        slots.forEach((index, slot) -> calls.add(new ToolCall(
                slot.id() != null ? slot.id() : "call_" + index,
                "function",
                new ToolCall.FunctionCall(slot.name(), slot.args().isEmpty() ? "{}" : slot.args().toString()))));
        return calls;
    }
}
