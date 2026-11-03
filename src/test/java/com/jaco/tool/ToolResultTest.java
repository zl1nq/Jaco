package com.jaco.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.agent.TurnEvent;
import com.jaco.hook.*;
import com.jaco.llm.ToolCall;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ToolResultTest {
    @Test void textCannotChangeStatusAndHooksSeeActualResult() {
        for (var status : ToolExecution.Status.values()) {
            var registry = new ToolRegistry();
            registry.register(new Tool() {
                public String name() { return "sample"; }
                public String description() { return "sample"; }
                public JsonNode schema() { return JsonSchema.object().build(); }
                public String summary(JsonNode args) { return "sample"; }
                public ToolResult execute(JsonNode args, ToolContext ctx) { return new ToolResult(status, "ERROR: normal content"); }
            });
            var observed = new AtomicReference<ToolExecution>();
            var executor = new ToolCallExecutor(registry, new HookChain(List.of(new AgentHook() {
                public void onToolCompleted(ToolCall call, ToolExecution result) { observed.set(result); }
            })));
            var events = new ArrayList<TurnEvent>();
            var call = new ToolCall("id", "function", new ToolCall.FunctionCall("sample", "{}"));
            var ctx = new ToolContext(Path.of("."), null, "auto", new AtomicReference<>(), () -> false, null);
            var result = executor.execute(call, ctx, null, events::add);
            executor.completed(call, result, events::add);
            assertEquals(status, result.status());
            assertSame(result, observed.get());
            assertTrue(events.getLast() instanceof TurnEvent.ToolCallEnd end && end.ok() == result.ok());
        }
    }
}
