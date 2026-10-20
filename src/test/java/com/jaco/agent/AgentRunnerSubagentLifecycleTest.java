package com.jaco.agent;

import com.jaco.agent.subagent.*;
import com.jaco.config.ProviderConfig;
import com.jaco.hook.HookChain;
import com.jaco.llm.*;
import com.jaco.session.Session;
import com.jaco.session.SessionStore;
import com.jaco.tool.*;
import com.jaco.tool.builtin.TaskTool;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerSubagentLifecycleTest {
    @TempDir Path tmp;
    @FunctionalInterface interface Script { ChatStream run(ChatRequest request) throws IOException; }
    private SessionStore store;

    private AgentRunner runner(Script script, String toolOutput, long contextLimit) throws IOException {
        var client = new OpenAiCompatClient("http://localhost", "k") {
            @Override public ChatStream chatStream(ChatRequest request) throws IOException { return script.run(request); }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            public String name() { return "read_file"; }
            public String description() { return "read"; }
            public JsonNode schema() { return JsonSchema.object().build(); }
            public String summary(JsonNode args) { return "read"; }
            public String execute(JsonNode args, ToolContext ctx) { return toolOutput; }
        });
        TaskTool task = new TaskTool();
        registry.register(task);
        store = new SessionStore(tmp);
        var agent = new AgentRunner(client, new ProviderConfig("http://localhost", "k", "m", null),
                null, store, new HookChain(List.of()), registry, new ToolSandbox(tmp, List.of()),
                tmp, "auto", 15, contextLimit, 2500);
        agent.start();
        task.bind(agent::runSubagent);
        return agent;
    }

    private static ChatStream read(String id, Usage usage) {
        return TestStreams.of(new StreamChunk.ToolCallDelta(0, id, "read_file", "{}"),
                new StreamChunk.Done("tool_calls", usage));
    }

    private static void eventually(BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (!condition.getAsBoolean()) Thread.sleep(10);
        });
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        while (true) {
            try { latch.await(); return; }
            catch (InterruptedException ignored) { }
        }
    }

    @Test void entireTaskTimeoutCancelsModelStream() throws Exception {
        var stream = TestStreams.of();
        try (var agent = runner(request -> stream, "", 100_000)) {
            agent.configureSubagentTimeout(Duration.ofMillis(100));
            String report = assertTimeoutPreemptively(Duration.ofSeconds(3),
                    () -> agent.runSubagent("调查", () -> false));
            assertTrue(report.startsWith("子任务超时"));
            assertTrue(stream.isCancelled());
            var snapshot = agent.subagentSnapshots().getFirst();
            assertEquals(SubagentState.EXITED, snapshot.state());
            assertEquals(StopReason.TIMEOUT, snapshot.stopReason());
            assertTrue(agent.subagentCommand(snapshot.id()).contains("TIMED_OUT"));
        }
    }

    @Test void iterationBudgetReturnsPartialEvidenceAndKnownUsage() throws Exception {
        try (var agent = runner(request -> read("c1", new Usage(10, 2, 12)), "src/A.java:42", 100_000)) {
            agent.configureSubagentLimits(Duration.ofSeconds(5), 1);
            String report = agent.runSubagent("调查", () -> false);
            assertTrue(report.contains("达到迭代上限 1"));
            assertTrue(report.contains("src/A.java:42"));
            var snapshot = agent.subagentSnapshots().getFirst();
            assertEquals(1, snapshot.iterations());
            assertEquals(12, snapshot.totalTokens());
            assertTrue(agent.subagentCommand(snapshot.id()).contains("BUDGET_EXCEEDED"));
        }
    }

    @Test void compressionRequestsAreIncludedInTaskUsage() throws Exception {
        AtomicInteger investigations = new AtomicInteger();
        AtomicInteger summaries = new AtomicInteger();
        try (var agent = runner(request -> {
            if (request.tools() == null) {
                summaries.incrementAndGet();
                return TestStreams.of(new StreamChunk.Delta("摘要"), new StreamChunk.Done("stop", new Usage(4, 1, 5)));
            }
            int n = investigations.incrementAndGet();
            return n <= 6 ? read("c" + n, new Usage(8, 2, 10))
                    : TestStreams.of(new StreamChunk.Delta("报告"), new StreamChunk.Done("stop", new Usage(8, 2, 10)));
        }, "src/A.java:42\n" + "x".repeat(2000), 1600)) {
            assertEquals("报告", agent.runSubagent("调查", () -> false));
            assertTrue(summaries.get() > 0);
            assertEquals(investigations.get() * 10L + summaries.get() * 5L,
                    agent.subagentSnapshots().getFirst().totalTokens());
        }
    }

    @Test void failedAndSameSessionSwitchesKeepTaskButSuccessfulSwitchCancelsIt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        var stream = TestStreams.of();
        AtomicReference<String> report = new AtomicReference<>();
        try (var agent = runner(request -> { started.countDown(); return stream; }, "", 100_000)) {
            String original = agent.session().id();
            store.save(Session.create("other-session", 1));
            Thread caller = Thread.ofVirtual().start(() -> report.set(agent.runSubagent("调查", () -> false)));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertEquals("已是当前会话", agent.switchSession(original));
            assertTrue(agent.switchSession("missing").contains("没有匹配"));
            assertEquals(SubagentState.RUNNING, agent.subagentSnapshots().getFirst().state());
            assertNull(agent.switchSession("other-session"));
            caller.join(3000);
            assertFalse(caller.isAlive());
            assertTrue(report.get().startsWith("(子任务被中断)"));
            var snapshot = agent.subagentSnapshots().getFirst();
            assertEquals(original, snapshot.parentSessionId());
            assertEquals(StopReason.SESSION_CHANGED, snapshot.stopReason());
            assertEquals("没有匹配的子任务", agent.subagentCommand(snapshot.id()));
            assertEquals("（当前会话暂无子任务）", agent.subagentCommand(""));
        }
    }

    @Test void cancelledParentCannotAppendLateTaskResultToNewSession() throws Exception {
        CountDownLatch childStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<ChatStream> childStream = new AtomicReference<>();
        try (var agent = runner(request -> {
            boolean parent = request.tools().stream().anyMatch(t -> t.function().name().equals("task"));
            if (parent) return TestStreams.of(new StreamChunk.ToolCallDelta(0, "task1", "task", "{\"prompt\":\"调查\"}"),
                    new StreamChunk.Done("tool_calls", null));
            childStarted.countDown();
            awaitIgnoringInterrupts(release);
            var stream = TestStreams.of(new StreamChunk.Delta("迟到的报告"), new StreamChunk.Done("stop", null));
            childStream.set(stream);
            return stream;
        }, "", 100_000)) {
            String original = agent.session().id();
            store.save(Session.create("other-session", 1));
            TurnHandle parent = agent.runTurn("调查项目");
            try {
                assertTrue(childStarted.await(3, TimeUnit.SECONDS));
                assertEquals(parent.id(), agent.subagentSnapshots().getFirst().parentTurnId());
                parent.cancel();
                assertNull(agent.switchSession("other-session"));
                release.countDown();
                eventually(() -> agent.subagentSnapshots().getFirst().state() == SubagentState.EXITED);
                eventually(() -> store.load(original).map(s -> s.messages().stream()
                        .anyMatch(m -> m.role() == Role.TOOL && "task1".equals(m.toolCallId()))).orElse(false));
                assertTrue(childStream.get().isCancelled());
                assertEquals("other-session", agent.session().id());
                assertTrue(agent.session().messages().isEmpty());
                assertTrue(store.load("other-session").orElseThrow().messages().isEmpty());
            } finally { release.countDown(); parent.close(); }
        }
    }
}
