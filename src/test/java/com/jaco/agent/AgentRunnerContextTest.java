package com.jaco.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.config.ProviderConfig;
import com.jaco.hook.HookChain;
import com.jaco.llm.*;
import com.jaco.session.SessionStore;
import com.jaco.tool.*;
import com.jaco.tool.builtin.RecallTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerContextTest {
    @TempDir Path tmp;
    @FunctionalInterface interface Script { ChatStream respond(ChatRequest request) throws IOException; }
    @FunctionalInterface interface Output { String read(ToolContext ctx) throws Exception; }
    private SessionStore store;

    private AgentRunner runner(Script script, Output output, long limit, int budget) throws Exception {
        OpenAiCompatClient client = new OpenAiCompatClient("http://localhost", "k") {
            @Override public ChatStream chatStream(ChatRequest request) throws IOException { return script.respond(request); }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            public String name() { return "read_file"; }
            public String description() { return "test"; }
            public JsonNode schema() { return JsonSchema.object().build(); }
            public String summary(JsonNode args) { return "test"; }
            public String execute(JsonNode args, ToolContext ctx) throws Exception { return output.read(ctx); }
        });
        registry.register(new RecallTool());
        store = new SessionStore(tmp);
        AgentRunner agent = new AgentRunner(client, new ProviderConfig("http://localhost", "k", "m", null),
                null, store, new HookChain(List.of()), registry, new ToolSandbox(tmp, List.of()), tmp, "auto", 15, limit, budget);
        agent.start();
        return agent;
    }
    private static ChatStream call(String id, String name, String args) {
        return TestStreams.of(new StreamChunk.ToolCallDelta(0, id, name, args), new StreamChunk.Done("tool_calls", null));
    }
    private static ChatStream answer() { return TestStreams.of(new StreamChunk.Delta("完成"), new StreamChunk.Done("stop", null)); }
    private static String tools(ChatRequest request) {
        return request.messages().stream().filter(m -> m.role() == Role.TOOL).map(Message::content).reduce("", (a, b) -> a + b);
    }
    private static TurnEvent.Done finish(AgentRunner agent) {
        return assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (TurnHandle handle = agent.runTurn("原始任务及约束")) {
                while (true) if (handle.poll(100) instanceof TurnEvent.Done done) return done;
            }
        });
    }

    @Test void childCanRecallMissingMiddleAndCannotReadParentArchive() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String original = "HEAD" + "x".repeat(3000) + "MIDDLE_MARKER" + "y".repeat(3000) + "TAIL";
        List<ChatRequest> requests = new ArrayList<>();
        AgentRunner agent = runner(request -> {
            requests.add(request);
            return switch (calls.getAndIncrement()) {
                case 0 -> call("c1", "read_file", "{}");
                case 1 -> call("c2", "recall", "{\"query\":\"MIDDLE_MARKER\"}");
                case 2 -> call("c3", "recall", "{\"entry_id\":0,\"content_offset\":3004,\"content_limit\":13}");
                case 3 -> call("c4", "recall", "{\"query\":\"ONLY_PARENT\"}");
                default -> answer();
            };
        }, ctx -> original, 100_000, 1000);
        store.appendArchivedOutput(agent.session(), "ONLY_PARENT");
        String parentBefore = Files.readString(store.archivePath(agent.session()));
        assertEquals("完成", agent.runSubagent("检索任务", () -> false));
        assertFalse(tools(requests.get(1)).contains("MIDDLE_MARKER"));
        assertTrue(tools(requests.get(2)).contains("MIDDLE_MARKER"));
        assertTrue(requests.get(3).messages().getLast().content().contains("MIDDLE_MARKER"));
        assertFalse(tools(requests.get(4)).contains("ONLY_PARENT"));
        assertEquals(parentBefore, Files.readString(store.archivePath(agent.session())));
        assertEquals(1, store.listSessions().size());
        try (var files = Files.list(tmp)) {
            Path child = files.filter(p -> p.getFileName().toString().contains("-sub-")).findFirst().orElseThrow();
            assertTrue(Files.readString(child).contains(original));
        }
    }

    @Test void failedArchivesHaveTruthfulBudgetedPreviewsInBothLoops() throws Exception {
        for (boolean child : List.of(false, true)) {
            AtomicInteger calls = new AtomicInteger();
            List<ChatRequest> requests = new ArrayList<>();
            AgentRunner agent = runner(request -> {
                requests.add(request);
                return calls.getAndIncrement() == 0 ? call("c", "read_file", "{}") : answer();
            }, ctx -> { Files.createDirectory(ctx.archiveFile()); return "x".repeat(3000); }, 100_000, 200);
            if (child) assertEquals("完成", agent.runSubagent("task", () -> false));
            else assertNull(finish(agent).error());
            String preview = tools(requests.get(1));
            assertTrue(preview.contains("归档失败"));
            assertFalse(preview.contains("recall"));
            assertFalse(preview.contains("已归档"));
            assertTrue(preview.length() <= 200);
        }
    }

    @Test void mainLoopAlsoArchivesBeforeClipping() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String original = "x".repeat(3000) + "UNIQUE_MIDDLE" + "y".repeat(3000);
        AgentRunner agent = runner(request -> calls.getAndIncrement() == 0 ? call("c", "read_file", "{}") : answer(),
                ctx -> original, 100_000, 1000);
        assertNull(finish(agent).error());
        assertTrue(Files.readString(store.archivePath(agent.session())).contains(original));
        String preview = agent.session().messages().stream().filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow().content();
        assertTrue(preview.contains("recall"));
        assertFalse(preview.contains("UNIQUE_MIDDLE"));
        assertTrue(preview.length() <= 1000);
    }

    @Test void eachLoopCompactsInsideOneUserTurnAndKeepsTaskAndPairs() throws Exception {
        for (boolean child : List.of(false, true)) {
            AtomicInteger calls = new AtomicInteger();
            AtomicBoolean compacted = new AtomicBoolean();
            AgentRunner agent = runner(request -> {
                if (request.tools() == null) return TestStreams.of(new StreamChunk.Delta("发现 src/A.java，继续检查剩余文件"), new StreamChunk.Done("stop", null));
                if (request.messages().stream().anyMatch(m -> m.content() != null && m.content().startsWith("[迭代摘要"))) compacted.set(true);
                assertTrue(request.messages().stream().anyMatch(m -> m.role() == Role.USER && m.content().equals("原始任务及约束")));
                assertPairs(request.messages());
                int i = calls.getAndIncrement();
                return i < 5 ? call("c" + i, "read_file", "{}") : answer();
            }, ctx -> "内容\n" + "x".repeat(2000), 1600, 2500);
            if (child) assertEquals("完成", agent.runSubagent("原始任务及约束", () -> false));
            else assertNull(finish(agent).error());
            assertTrue(compacted.get());
        }
    }

    @Test void serverOverflowRetriesOnlyAfterReductionForStreamAndThrownErrors() throws Exception {
        for (boolean thrown : List.of(false, true)) {
            for (boolean child : List.of(false, true)) {
            AtomicInteger calls = new AtomicInteger();
            AtomicInteger reads = new AtomicInteger();
            List<Long> sizes = new ArrayList<>();
            AgentRunner agent = runner(request -> {
                if (request.tools() == null) return TestStreams.of(new StreamChunk.Delta("进展摘要"), new StreamChunk.Done("stop", null));
                sizes.add(ContextCompactor.estimateTokens(request.messages()));
                int i = calls.getAndIncrement();
                if (i < 2) return call("c" + i, "read_file", "{}");
                if (i == 2) {
                    if (thrown) throw new IOException("context length exceeded");
                    return TestStreams.of(new StreamChunk.Error(null, "context length exceeded"));
                }
                return answer();
            }, ctx -> "progress-" + reads.incrementAndGet() + "\n" + "x".repeat(2000), 100_000, 2500);
            if (child) assertEquals("完成", agent.runSubagent("task", () -> false));
            else assertNull(finish(agent).error());
            assertEquals(4, calls.get());
            assertTrue(sizes.get(3) < sizes.get(2));
            }
        }
    }

    @Test void irreducibleOverflowDoesNotRetryAndReturnsPartialFindings() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AgentRunner agent = runner(request -> calls.getAndIncrement() == 0 ? call("c", "read_file", "{}")
                        : TestStreams.of(new StreamChunk.Error(null, "maximum context length exceeded")),
                ctx -> "src/A.java:42 已确认的发现", 100_000, 1000);
        String report = agent.runSubagent("task", () -> false);
        assertTrue(report.contains("压缩未能缩小请求"));
        assertTrue(report.contains("src/A.java:42"));
        assertEquals(2, calls.get());
    }

    @Test void cancellationDuringSummaryClosesStreamAndStopsChild() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ChatStream> summaryStream = new AtomicReference<>();
        AgentRunner agent = runner(request -> {
            if (request.tools() == null) {
                cancelled.set(true);
                summaryStream.set(answer());
                return summaryStream.get();
            }
            return call("c" + calls.getAndIncrement(), "read_file", "{}");
        }, ctx -> "内容\n" + "x".repeat(2000), 1600, 2500);
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertEquals("(子任务被中断)", agent.runSubagent("task", cancelled::get)));
        assertTrue(cancelled.get());
        assertInstanceOf(StreamChunk.Error.class, summaryStream.get().poll(0));
    }

    @Test void twoChildRunsHaveSeparateArchivesAndOnlyFinalReportsReturn() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        List<String> lastTools = new ArrayList<>();
        AgentRunner agent = runner(request -> {
            lastTools.add(tools(request));
            return switch (calls.getAndIncrement()) {
                case 0, 3 -> call("read", "read_file", "{}");
                case 2 -> call("recall", "recall", "{\"query\":\"CHILD_ONE\"}");
                default -> answer();
            };
        }, ctx -> (reads.getAndIncrement() == 0 ? "CHILD_ONE" : "CHILD_TWO") + "x".repeat(3000), 100_000, 1000);
        assertEquals("完成", agent.runSubagent("first", () -> false));
        assertEquals("完成", agent.runSubagent("second", () -> false));
        assertFalse(lastTools.get(3).contains("CHILD_ONE"));
        assertTrue(agent.session().messages().isEmpty());
        assertFalse(Files.exists(store.archivePath(agent.session())));
        try (var files = Files.list(tmp)) { assertEquals(2, files.filter(p -> p.getFileName().toString().contains("-sub-")).count()); }
    }

    @Test void secondOverflowEndsInsteadOfRetryingAgainInBothLoops() throws Exception {
        for (boolean child : List.of(false, true)) {
            AtomicInteger calls = new AtomicInteger();
            AgentRunner agent = runner(request -> {
                int i = calls.getAndIncrement();
                return i < 2 ? call("c" + i, "read_file", "{}")
                        : TestStreams.of(new StreamChunk.Error(null, "context length exceeded"));
            }, ctx -> "已发现线索\n" + "x".repeat(2000), 100_000, 2500);
            if (child) assertTrue(agent.runSubagent("task", () -> false).contains("子任务失败"));
            else assertNotNull(finish(agent).error());
            assertEquals(4, calls.get());
        }
    }

    private static void assertPairs(List<Message> messages) {
        List<String> pending = new ArrayList<>();
        for (Message m : messages) {
            if (m.role() == Role.ASSISTANT) {
                assertTrue(pending.isEmpty());
                if (m.toolCalls() != null) for (var call : m.toolCalls()) pending.add(call.id());
            } else if (m.role() == Role.TOOL) assertTrue(pending.remove(m.toolCallId()));
        }
        assertTrue(pending.isEmpty());
    }
}
