package com.jaco.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.hook.HookChain;
import com.jaco.llm.ToolCall;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolAttributesTest {
    private Tool sample(boolean declared) {
        return new Tool() {
            public String name() { return "read_file"; }
            public String description() { return "sample"; }
            public JsonNode schema() { return JsonSchema.object().build(); }
            public String summary(JsonNode args) { return "sample"; }
            public boolean isReadOnly() { return declared; }
            public boolean isSubagentAllowed() { return declared; }
            public ToolResult execute(JsonNode args, ToolContext ctx) { throw new AssertionError("必须在执行前拒绝"); }
        };
    }
    private final ToolCall call = new ToolCall("id", "function", new ToolCall.FunctionCall("read_file", "{}"));
    @Test void nameCannotGrantReadOnlyOrChildPermissions() {
        Tool tool = sample(false);
        assertTrue(tool.requiresConfirmationChannel());
        var registry = new ToolRegistry(tool);
        assertFalse(new PermissionHook(registry).onBeforeToolCall(call).proceed());
        assertTrue(registry.definitions(Set.of("read_file")).isEmpty());
        assertEquals(1, registry.definitions().size());
        var executor = new ToolCallExecutor(registry, new HookChain(List.of()));
        var ctx = new ToolContext(Path.of("."), null, "auto", new AtomicReference<>(), () -> false, null);
        assertEquals(ToolExecution.Status.DENIED, executor.execute(call, ctx, Set.of("read_file"), e -> { }).status());
    }
    @Test void childAllowListCannotBypassConfirmationChannel() {
        var registry = new ToolRegistry(sample(true));
        assertTrue(new PermissionHook(registry).onBeforeToolCall(call).proceed());
        assertEquals(1, registry.definitions(Set.of("read_file")).size());
        var executor = new ToolCallExecutor(registry, new HookChain(List.of()));
        var ctx = new ToolContext(Path.of("."), null, "auto", new AtomicReference<>(), () -> false, null);
        assertEquals(ToolExecution.Status.DENIED, executor.execute(call, ctx, Set.of("read_file"), e -> { }).status());
        assertEquals(ToolExecution.Status.DENIED, executor.execute(call, ctx, Set.of(), e -> { }).status());
    }
}
