package com.jaco.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaco.llm.*;
import com.jaco.tool.*;
import com.jaco.tool.builtin.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowTest {
    @TempDir Path dir;
    private final ObjectMapper mapper = new ObjectMapper();
    private TodoItem todo() { return new TodoItem("first", "读取项目", TodoItem.Status.in_progress); }

    @Test void persistsClearsAndPreservesStateAcrossTitleChanges() throws Exception {
        var store = new SessionStore(dir);
        var owner = store.createNew();
        var service = new WorkflowService(owner, store, () -> true);
        service.writeTodos(List.of(todo()));
        assertEquals(List.of(todo()), store.load(owner.id()).orElseThrow().workflow().state().todos());
        var titled = owner.withFirstPromptTitle("分析项目");
        assertSame(owner.workflow(), titled.workflow());
        assertTrue(titled.workflow().renderTodos().contains("1. [in_progress] first"));
        service.writeTodos(List.of());
        assertTrue(store.load(owner.id()).orElseThrow().workflow().state().todos().isEmpty());
        var old = mapper.readValue("{\"id\":\"old\",\"createdAt\":1,\"messages\":[]}", Session.class);
        assertTrue(old.workflow().state().todos().isEmpty());
        assertTrue(store.createNew().workflow().state().todos().isEmpty());
    }
    @Test void failedSaveAndExpiredCallsCannotAlterState() throws Exception {
        var store = new SessionStore(dir);
        var owner = store.createNew();
        var active = new AtomicBoolean(true);
        var service = new WorkflowService(owner, store, active::get);
        service.writeTodos(List.of(todo()));
        Files.createDirectory(dir.resolve(owner.id() + ".json.tmp"));
        assertThrows(java.io.UncheckedIOException.class, () -> service.writeTodos(List.of()));
        assertEquals(List.of(todo()), owner.workflow().state().todos());
        assertEquals(List.of(todo()), store.load(owner.id()).orElseThrow().workflow().state().todos());
        active.set(false);
        assertThrows(IllegalStateException.class, service::readTodos);
        assertThrows(IllegalStateException.class, () -> service.writeTodos(List.of()));
    }
    @Test void workflowSaveDoesNotPersistUnpairedToolMessages() throws Exception {
        var store = new SessionStore(dir);
        var owner = store.createNew();
        owner.messages().add(Message.user("做任务"));
        owner.messages().add(new Message(Role.ASSISTANT, null, List.of(
                new ToolCall("a", "function", new ToolCall.FunctionCall("todo_write", "{}")),
                new ToolCall("b", "function", new ToolCall.FunctionCall("todo_read", "{}"))), null, null));
        owner.messages().add(Message.toolResult("a", "ok"));
        new WorkflowService(owner, store, () -> true).writeTodos(List.of(todo()));
        assertEquals(1, store.load(owner.id()).orElseThrow().messages().size());
        assertEquals(3, owner.messages().size());
    }
    @Test void validatesToolInputsAndRejectsSubagentContext() throws Exception {
        var store = new SessionStore(dir);
        var owner = store.createNew();
        var registry = new ToolRegistry(new TodoWriteTool(), new TodoReadTool());
        var ctx = new ToolContext(dir, null, "auto", null, () -> false, null, 8000,
                new WorkflowService(owner, store, () -> true));
        for (String json : List.of("{}", "{\"todos\":null}", "{\"todos\":[{\"id\":\"x\",\"content\":\" \",\"status\":\"pending\"}]}",
                "{\"todos\":[{\"id\":\"x\",\"content\":12,\"status\":\"pending\"}]}",
                "{\"todos\":[{\"id\":\"x\",\"content\":\"x\",\"status\":\"bad\"}]}")) {
            assertThrows(Exception.class, () -> registry.execute(new ToolCall("id", "function", new ToolCall.FunctionCall("todo_write", json)), ctx));
        }
        assertThrows(IllegalArgumentException.class, () -> ctx.workflow().writeTodos(List.of(todo(), todo())));
        assertThrows(IllegalArgumentException.class, () -> new TodoItem("x", "x".repeat(501), TodoItem.Status.pending));
        assertThrows(IllegalArgumentException.class, () -> new SessionWorkflow.State(java.util.stream.IntStream.range(0,51)
                .mapToObj(i -> new TodoItem("t" + i, "x", TodoItem.Status.pending)).toList()));
        assertTrue(registry.definitions(Set.of("todo_read", "todo_write")).isEmpty());
        assertTrue(new PermissionHook(registry).onBeforeToolCall(new ToolCall("id", "function", new ToolCall.FunctionCall("todo_write", "{}"))).proceed());
        assertThrows(ToolException.class, () -> new TodoReadTool().execute(mapper.createObjectNode(), new ToolContext(dir, null, "auto", null, () -> false, null)));
    }
}
