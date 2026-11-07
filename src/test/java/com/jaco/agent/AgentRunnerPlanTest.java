package com.jaco.agent;

import com.jaco.config.ProviderConfig;
import com.jaco.hook.HookChain;
import com.jaco.llm.*;
import com.jaco.session.SessionStore;
import com.jaco.tool.*;
import com.jaco.tool.builtin.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerPlanTest {
    @TempDir Path dir;
    @Test void approvalContinuesSameTurnWithFreshToolsAndIndependentPermission() throws Exception { run(true); }
    @Test void rejectionStopsTurnAndDeniesRemainingBatchCalls() throws Exception { run(false); }
    private void run(boolean approve) throws Exception {
        var requests = new ArrayList<ChatRequest>();
        var client = new OpenAiCompatClient("http://localhost", "k") {
            @Override public ChatStream chatStream(ChatRequest request) {
                requests.add(request);
                if (requests.size() == 1) return TestStreams.of(
                        new StreamChunk.ToolCallDelta(0, "p", "submit_plan", "{\"plan\":\"# 完整计划\\n创建测试文件\"}"),
                        new StreamChunk.ToolCallDelta(1, "w", "write_file", "{\"path\":\"new.txt\",\"content\":\"hello\"}"),
                        new StreamChunk.Done("tool_calls", null));
                return TestStreams.of(new StreamChunk.Delta("完成"), new StreamChunk.Done("stop", null));
            }
        };
        var registry = new ToolRegistry(new SubmitPlanTool(), new EnterPlanModeTool(), new TodoReadTool(), new WriteFileTool());
        var permission = new PermissionHook(registry);
        var store = new SessionStore(dir.resolve("sessions"));
        try (var agent = new AgentRunner(client, new ProviderConfig("http://localhost", "k", "m", null), null,
                store, new HookChain(List.of(permission)), registry, new ToolSandbox(dir, List.of()), dir, "auto", 10, 100000, 8000)) {
            agent.start(); agent.plan();
            var events = new ArrayList<TurnEvent>();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                try (var handle = agent.runTurn("制定方案")) {
                    while (true) {
                        TurnEvent event = handle.poll(100);
                        if (event == null) continue;
                        events.add(event);
                        if (event instanceof TurnEvent.PlanApprovalRequest p) p.resolver().accept(approve);
                        if (event instanceof TurnEvent.ApprovalRequest p) p.resolver().accept("n");
                        if (event instanceof TurnEvent.Done d) { assertNull(d.error()); assertEquals(approve ? "stop" : "plan_waiting", d.finishReason()); break; }
                    }
                }
            });
            assertEquals(!approve, agent.planning());
            assertEquals(approve ? 2 : 1, requests.size());
            assertFalse(requests.getFirst().tools().stream().anyMatch(t -> t.function().name().equals("write_file")));
            if (approve) {
                assertTrue(requests.getLast().tools().stream().anyMatch(t -> t.function().name().equals("write_file")));
                assertFalse(requests.getLast().tools().stream().anyMatch(t -> t.function().name().equals("submit_plan")));
                assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.ApprovalRequest));
            } else assertFalse(events.stream().anyMatch(e -> e instanceof TurnEvent.ApprovalRequest));
            assertFalse(Files.exists(dir.resolve("new.txt")));
            assertEquals("制定方案", agent.session().title());
            assertEquals(!approve, store.load(agent.session().id()).orElseThrow().workflow().planning());
            assertEquals(2, agent.session().messages().stream().filter(m -> m.role() == Role.TOOL).count());
        }
    }
    @Test void existingSessionAuthorizationCannotBypassPlanGate() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var client = new OpenAiCompatClient("http://localhost", "k") {
            @Override public ChatStream chatStream(ChatRequest request) {
                int i = calls.getAndIncrement();
                return i % 2 == 0 ? TestStreams.of(new StreamChunk.ToolCallDelta(0, "w" + i, "write_file",
                        "{\"path\":\"" + (i == 0 ? "granted.txt" : "blocked.txt") + "\",\"content\":\"hello\"}"),
                        new StreamChunk.Done("tool_calls", null))
                        : TestStreams.of(new StreamChunk.Delta("完成"), new StreamChunk.Done("stop", null));
            }
        };
        var registry = new ToolRegistry(new WriteFileTool());
        try (var agent = new AgentRunner(client, new ProviderConfig("http://localhost", "k", "m", null), null,
                new SessionStore(dir.resolve("s")), new HookChain(List.of(new PermissionHook(registry))), registry,
                new ToolSandbox(dir, List.of()), dir, "auto", 10, 100000, 8000)) {
            agent.start();
            for (int round = 0; round < 2; round++) {
                final boolean planning = round == 1;
                if (planning) agent.plan();
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    try (var handle = agent.runTurn("创建文件")) {
                        while (true) {
                            var event = handle.poll(100);
                            if (event instanceof TurnEvent.ToolPreview) assertFalse(planning);
                            if (event instanceof TurnEvent.ApprovalRequest a) { assertFalse(planning); a.resolver().accept("a"); }
                            if (event instanceof TurnEvent.Done done) { assertNull(done.error()); break; }
                        }
                    }
                });
            }
            assertTrue(Files.exists(dir.resolve("granted.txt")));
            assertFalse(Files.exists(dir.resolve("blocked.txt")));
            assertTrue(agent.session().messages().stream().anyMatch(m -> m.role() == Role.TOOL && m.content().contains("计划模式禁止")));
        }
    }

    @Test void persistenceFailureStillFinishesTurnAndDoesNotApprove() throws Exception {
        var client = new OpenAiCompatClient("http://localhost", "k") {
            @Override public ChatStream chatStream(ChatRequest request) {
                return TestStreams.of(new StreamChunk.ToolCallDelta(0, "plan", "submit_plan", "{\"plan\":\"完整方案\"}"),
                        new StreamChunk.Done("tool_calls", null));
            }
        };
        var store = new SessionStore(dir.resolve("failure-sessions"));
        try (var agent = new AgentRunner(client, new ProviderConfig("http://localhost", "k", "m", null), null,
                store, new HookChain(List.of()), new ToolRegistry(new SubmitPlanTool()),
                new ToolSandbox(dir, List.of()), dir, "auto", 3, 100000, 8000)) {
            agent.start(); agent.plan();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                try (var handle = agent.runTurn("计划")) {
                    while (true) {
                        var event = handle.poll(100);
                        if (event instanceof TurnEvent.PlanApprovalRequest p) {
                            Files.createDirectory(dir.resolve("failure-sessions").resolve(agent.session().id() + ".json.tmp"));
                            p.resolver().accept(true);
                        }
                        if (event instanceof TurnEvent.Done done) { assertNotNull(done.error()); break; }
                    }
                }
            });
            assertTrue(agent.planning());
            assertEquals("draft", agent.session().workflow().state().decision());
            assertTrue(store.load(agent.session().id()).orElseThrow().workflow().planning());
        }
    }
    @Test void staleTurnCannotOverwriteReloadedWorkflowAfterSwitchingBack() throws Exception {
        var client = new OpenAiCompatClient("http://localhost", "k") {
            @Override public ChatStream chatStream(ChatRequest request) {
                return TestStreams.of(new StreamChunk.ToolCallDelta(0, "p", "submit_plan", "{\"plan\":\"旧方案\"}"),
                        new StreamChunk.Done("tool_calls", null));
            }
        };
        var store = new SessionStore(dir.resolve("stale"));
        try (var agent = new AgentRunner(client, new ProviderConfig("http://localhost", "k", "m", null), null,
                store, new HookChain(List.of()), new ToolRegistry(new SubmitPlanTool()),
                new ToolSandbox(dir, List.of()), dir, "auto", 3, 100000, 8000)) {
            agent.start(); agent.plan();
            String original = agent.session().id();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                try (var handle = agent.runTurn("计划")) {
                    while (true) {
                        var event = handle.poll(100);
                        if (event instanceof TurnEvent.PlanApprovalRequest p) {
                            agent.newSession();
                            assertNull(agent.switchSession(original));
                            var service = new com.jaco.session.WorkflowService(agent.session(), store, () -> true);
                            service.saveDraft("新方案");
                            service.cancelPlan();
                            p.resolver().accept(true);
                            assertFalse(p.active().getAsBoolean());
                        }
                        if (event instanceof TurnEvent.Done done) { assertNull(done.error()); break; }
                    }
                }
            });
            var state = store.load(original).orElseThrow().workflow().state();
            assertEquals("新方案", state.draft());
            assertEquals("cancelled", state.decision());
            assertEquals(com.jaco.session.SessionWorkflow.Mode.NORMAL, state.mode());
        }
    }
}
