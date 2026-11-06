package com.jaco.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.llm.ToolCall;
import com.jaco.tool.*;
import com.jaco.tool.builtin.*;
import com.jaco.hook.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanModeTest {
    @TempDir Path dir;
    @Test void persistsModeAndTodosAndEntersIdempotently() throws Exception {
        var store = new SessionStore(dir);
        var owner = store.createNew();
        assertFalse(owner.workflow().planning());
        var service = new WorkflowService(owner, store, () -> true);
        service.enterPlan();
        long version = owner.workflow().state().revision();
        service.enterPlan();
        assertEquals(version, owner.workflow().state().revision());
        service.writeTodos(List.of(new TodoItem("one", "研究", TodoItem.Status.pending)));
        var restored = store.load(owner.id()).orElseThrow();
        assertTrue(restored.workflow().planning());
        assertEquals(1, restored.workflow().state().todos().size());
        assertFalse(store.createNew().workflow().planning());
        assertTrue(owner.withFirstPromptTitle("方案").workflow().planning());
    }
    @Test void refusesBusyResourcesAndRollsBackFailedPersistence() throws Exception {
        var store = new SessionStore(dir);
        var owner = store.createNew();
        var service = new WorkflowService(owner, store, () -> true, () -> List.of("process-cleaning"));
        assertTrue(assertThrows(IllegalStateException.class, service::enterPlan).getMessage().contains("process-cleaning"));
        assertFalse(owner.workflow().planning());
        Files.createDirectory(dir.resolve(owner.id() + ".json.tmp"));
        assertThrows(java.io.UncheckedIOException.class, () -> new WorkflowService(owner, store, () -> true).enterPlan());
        assertFalse(owner.workflow().planning());
    }
    @Test void filtersAndChecksEveryCallBeforePreparationDespitePermission() throws Exception {
        Tool unsafe = new Tool() {
            public String name() { return "unsafe"; }
            public String description() { return "unsafe"; }
            public String prompt() { return "unsafe rule"; }
            public JsonNode schema() { return JsonSchema.object().build(); }
            public String summary(JsonNode args) { return "unsafe"; }
            public ToolResult execute(JsonNode args, ToolContext ctx) { fail("must not execute"); return null; }
            public PreparedToolCall prepare(JsonNode args, ToolContext ctx) { fail("must not prepare"); return null; }
        };
        var registry = new ToolRegistry(new EnterPlanModeTool(), new TodoReadTool(), unsafe);
        var store = new SessionStore(dir);
        var owner = store.createNew();
        var ctx = new ToolContext(dir, null, "auto", null, () -> false, null, 8000,
                new WorkflowService(owner, store, () -> true));
        var executor = new ToolCallExecutor(registry, new HookChain(List.of(new AgentHook() {})));
        assertTrue(executor.execute(call("enter_plan_mode"), ctx, null, event -> {}).ok());
        assertEquals(ToolExecution.Status.DENIED, executor.execute(call("unsafe"), ctx, null, event -> {}).status());
        assertThrows(ToolException.class, () -> registry.prepare(call("unsafe"), ctx));
        assertEquals(2, registry.definitions(null, true).size());
        assertFalse(registry.prompts(null, true).contains("unsafe rule"));
        assertTrue(executor.execute(call("todo_read"), ctx, null, event -> {}).ok());
    }
    private ToolCall call(String name) { return new ToolCall(name, "function", new ToolCall.FunctionCall(name, "{}")); }
}
