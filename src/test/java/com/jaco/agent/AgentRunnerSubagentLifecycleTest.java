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
import java.util.ArrayList;
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

    @Test void backgroundSurvivesParentCompletionAndCancellationAndNotifiesExactlyOnce() throws Exception {
        CountDownLatch childStarted = new CountDownLatch(1);
        CountDownLatch finishChild = new CountDownLatch(1);
        AtomicInteger parentRequests = new AtomicInteger();
        try (var agent = runner(request -> {
            boolean parent = request.tools() != null && request.tools().stream()
                    .anyMatch(t -> t.function().name().equals("task"));
            if (parent) return parentRequests.getAndIncrement() == 0
                    ? TestStreams.of(new StreamChunk.ToolCallDelta(0, "bg", "task",
                        "{\"prompt\":\"后台调查\",\"run_in_background\":true,\"tools\":[]}"),
                        new StreamChunk.Done("tool_calls", null))
                    : TestStreams.of(new StreamChunk.Delta("父任务完成"), new StreamChunk.Done("stop", null));
            assertNull(request.tools());
            childStarted.countDown();
            awaitIgnoringInterrupts(finishChild);
            return TestStreams.of(new StreamChunk.Delta("后台独立报告"), new StreamChunk.Done("stop", null));
        }, "", 100_000)) {
            try {
                try (var turn = agent.runTurn("启动后台调查后继续")) {
                    assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                        while (!(turn.poll(100) instanceof TurnEvent.Done)) { }
                    });
                    assertTrue(childStarted.await(3, TimeUnit.SECONDS));
                    assertTrue(agent.session().messages().stream().anyMatch(m -> m.role() == Role.TOOL
                            && m.toolCallId().equals("bg") && m.content().startsWith("后台子任务已启动:")));
                } // close() 取消原父轮，后台仍继续。
                var child = agent.subagentSnapshots().getFirst();
                assertTrue(child.runInBackground());
                assertEquals(SubagentState.RUNNING, child.state());
                assertTrue(agent.subagentCommand(child.id()).contains("[后台]"));
                assertTrue(agent.runSubagent("第二个调查", () -> false).contains("已有活动子任务"));
                assertNull(agent.pollSubagentNotice());
                finishChild.countDown();
                eventually(() -> agent.subagentSnapshots().getFirst().state() == SubagentState.EXITED);
                AtomicReference<String> notice = new AtomicReference<>();
                eventually(() -> { notice.set(agent.pollSubagentNotice()); return notice.get() != null; });
                assertTrue(notice.get().contains(child.id()));
                assertTrue(notice.get().contains("SUCCEEDED"));
                assertNull(agent.pollSubagentNotice());
                assertTrue(agent.subagentCommand(child.id()).contains("后台独立报告"));
                assertEquals(1, agent.session().messages().stream().filter(m -> m.role() == Role.TOOL
                        && m.toolCallId().equals("bg")).count());
                assertFalse(agent.session().messages().stream().anyMatch(m -> m.content() != null
                        && m.content().contains("后台独立报告")));
                assertEquals(2, parentRequests.get());
            } finally { finishChild.countDown(); }
        }
    }

    @Test void backgroundCancelAndTimeoutProduceQueryableResultsAndNotifications() throws Exception {
        try (var agent = runner(request -> TestStreams.of(), "", 100_000)) {
            var request = new SubagentRequest("后台调查", SubagentProfile.EXPLORE, null, true);
            assertTrue(agent.runSubagent(request, () -> true).contains("未启动"));
            assertTrue(agent.subagentSnapshots().isEmpty());
            agent.runSubagent(request, () -> false);
            String id = agent.subagentSnapshots().getFirst().id();
            assertTrue(agent.subagentCommand("cancel " + id).contains("已请求取消"));
            eventually(() -> agent.subagentCommand(id).contains("CANCELLED"));
            eventually(() -> agent.pollSubagentNotice() != null);
            assertTrue(agent.cancelSubagent(id).contains("已退出"));
            agent.configureSubagentTimeout(Duration.ofMillis(100));
            agent.runSubagent(request, () -> false);
            String timedId = agent.subagentSnapshots().getLast().id();
            eventually(() -> agent.subagentCommand(timedId).contains("TIMED_OUT"));
            eventually(() -> agent.pollSubagentNotice() != null);
        }
    }

    @Test void sessionChangeCancelsBackgroundWithoutDeliveringOldSessionNotice() throws Exception {
        var stream = TestStreams.of();
        try (var agent = runner(request -> stream, "", 100_000)) {
            agent.runSubagent(new SubagentRequest("后台调查", SubagentProfile.EXPLORE, null, true), () -> false);
            var child = agent.subagentSnapshots().getFirst();
            agent.newSession();
            eventually(() -> agent.subagentSnapshots().getFirst().state() == SubagentState.EXITED);
            assertEquals(StopReason.SESSION_CHANGED, agent.subagentSnapshots().getFirst().stopReason());
            assertEquals("没有匹配的子任务", agent.subagentCommand(child.id()));
            assertEquals("没有匹配的子任务", agent.cancelSubagent(child.id()));
            assertNull(agent.pollSubagentNotice());
        }
    }

    @Test void backgroundFailureIsNotifiedAndQueryable() throws Exception {
        try (var agent = runner(request -> { throw new IOException("后台请求失败"); }, "", 100_000)) {
            agent.runSubagent(new SubagentRequest("调查", SubagentProfile.EXPLORE, null, true), () -> false);
            var child = agent.subagentSnapshots().getFirst();
            eventually(() -> agent.subagentCommand(child.id()).contains("FAILED"));
            AtomicReference<String> notice = new AtomicReference<>();
            eventually(() -> { notice.set(agent.pollSubagentNotice()); return notice.get() != null; });
            assertTrue(notice.get().contains("FAILED"));
            assertTrue(agent.subagentCommand(child.id()).contains("后台请求失败"));
        }
    }

    @Test void parentTaskSelectionReachesChildAndExplicitEmptyToolsRemainEmpty() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<ChatRequest> requests = new ArrayList<>();
        try (var agent = runner(request -> {
            requests.add(request);
            return switch (calls.getAndIncrement()) {
                case 0 -> TestStreams.of(new StreamChunk.ToolCallDelta(0, "task1", "task",
                        "{\"prompt\":\"只根据说明回答\",\"profile\":\"explore\",\"tools\":[]}"),
                        new StreamChunk.Done("tool_calls", null));
                case 1 -> read("denied", null);
                case 2 -> TestStreams.of(new StreamChunk.Delta("子报告"), new StreamChunk.Done("stop", null));
                default -> TestStreams.of(new StreamChunk.Delta("父报告"), new StreamChunk.Done("stop", null));
            };
        }, "不应读取的内容", 100_000)) {
            try (TurnHandle handle = agent.runTurn("委派无工具调查")) {
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    while (!(handle.poll(100) instanceof TurnEvent.Done)) { }
                });
            }
            assertEquals(4, requests.size());
            var taskSchema = requests.getFirst().tools().stream().filter(t -> t.function().name().equals("task"))
                    .findFirst().orElseThrow().function().parameters();
            assertEquals("[\"explore\",\"edit\"]", taskSchema.path("properties").path("profile").path("enum").toString());
            assertTrue(taskSchema.path("properties").has("tools"));
            assertNull(requests.get(1).tools());
            assertTrue(requests.get(1).messages().getFirst().content().contains("无，只根据任务说明回答"));
            var rejected = requests.get(2).messages().stream().filter(m -> m.role() == Role.TOOL).toList();
            assertEquals(1, rejected.size());
            assertEquals("denied", rejected.getFirst().toolCallId());
            assertTrue(rejected.getFirst().content().startsWith("ERROR: 子 agent 本次只允许工具"));
            assertFalse(rejected.getFirst().content().contains("不应读取的内容"));
            var snapshot = agent.subagentSnapshots().getFirst();
            assertTrue(snapshot.tools().isEmpty());
            assertEquals("explore", snapshot.profile());
            assertTrue(agent.session().messages().stream().anyMatch(m -> m.role() == Role.TOOL && m.content().equals("子报告")));
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
