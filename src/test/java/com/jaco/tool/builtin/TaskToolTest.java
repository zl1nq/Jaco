package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaco.tool.ToolContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolContext ctx() {
        return new ToolContext(Path.of("."), null, "auto", new AtomicReference<>(), () -> false, null);
    }

    @Test
    void unboundHandlerFailsGracefully() throws Exception {
        var args = MAPPER.createObjectNode();
        args.put("prompt", "调研一下");
        String out = new TaskTool().execute(args, ctx());
        assertEquals("ERROR: 子 agent 未接线", out);
    }

    @Test
    void boundHandlerReceivesPromptAndReturnsReport() throws Exception {
        TaskTool tool = new TaskTool();
        AtomicReference<String> received = new AtomicReference<>();
        tool.bind((prompt, cancelled) -> {
            received.set(prompt);
            return "报告内容";
        });
        var args = MAPPER.createObjectNode();
        args.put("prompt", "调研一下");
        assertEquals("报告内容", tool.execute(args, ctx()));
        assertEquals("调研一下", received.get());
    }

    @Test
    void blankPromptRejected() throws Exception {
        TaskTool tool = new TaskTool();
        tool.bind((prompt, cancelled) -> "不应被调用");
        var args = MAPPER.createObjectNode();
        args.put("prompt", "   ");
        assertEquals("ERROR: prompt 为空", tool.execute(args, ctx()));
    }
}
