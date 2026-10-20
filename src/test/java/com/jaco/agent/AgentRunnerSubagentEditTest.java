package com.jaco.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaco.agent.subagent.*;
import com.jaco.config.ProviderConfig;
import com.jaco.hook.*;
import com.jaco.llm.*;
import com.jaco.session.SessionStore;
import com.jaco.tool.*;
import com.jaco.tool.builtin.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerSubagentEditTest {
    @TempDir Path tmp;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final class Fixture implements AutoCloseable {
        final Path work = tmp.resolve(UUID.randomUUID().toString());
        final Path file = work.resolve("code.txt");
        final List<ChatRequest> requests = new ArrayList<>();
        final List<ToolExecution> completions = new ArrayList<>();
        final List<String> origins = new ArrayList<>();
        final AgentRunner agent;
        TurnHandle parent;
        Thread parentThread;
        Consumer<ChatRequest> beforeChild = r -> { };
        int parentRequests, childRequests;

        Fixture(String tool, String profile, Set<String> selected, String expectedVersion, boolean newFile,
                AgentHook extra) throws Exception {
            Files.createDirectories(work);
            Files.writeString(file, "old");
            var arguments = JSON.createObjectNode().put("path", newFile ? "new.txt" : "code.txt");
            if (tool.equals("edit_file")) arguments.put("old_text", "old").put("new_text", "new");
            else arguments.put("content", "new");
            if (!newFile) arguments.put("expected_version", expectedVersion == null ? version("old") : expectedVersion);
            var taskArgs = JSON.createObjectNode().put("prompt", "修改文件并报告").put("profile", profile);
            if (selected != null) selected.stream().sorted().forEach(taskArgs.putArray("tools")::add);
            var client = new OpenAiCompatClient("http://localhost", "k") {
                @Override public ChatStream chatStream(ChatRequest request) {
                    requests.add(request);
                    boolean main = request.tools() != null && request.tools().stream()
                            .anyMatch(t -> t.function().name().equals("task"));
                    if (main) {
                        parentThread = Thread.currentThread();
                        return parentRequests++ % 2 == 0 ? call("task1", "task", taskArgs.toString()) : answer("父报告");
                    }
                    beforeChild.accept(request);
                    return childRequests++ % 2 == 0 ? call("file1", tool, arguments.toString()) : answer("子报告");
                }
            };
            ToolRegistry registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            registry.register(new WriteFileTool());
            registry.register(new EditFileTool());
            TaskTool task = new TaskTool();
            registry.register(task);
            List<AgentHook> hooks = new ArrayList<>(List.of(new PermissionHook(), new AgentHook() {
                @Override public void onToolCompleted(ToolCall call, ToolExecution execution) {
                    if (TurnEventSink.current() != null && TurnEventSink.current().taskId() != null) {
                        completions.add(execution);
                        origins.add(TurnEventSink.current().taskId());
                    }
                }
            }));
            if (extra != null) hooks.add(extra);
            agent = new AgentRunner(client, new ProviderConfig("http://localhost", "k", "m", null), null,
                    new SessionStore(work.resolve("sessions")), new HookChain(hooks), registry,
                    new ToolSandbox(work, List.of()), work, "auto", 10, 100_000, 8000);
            agent.start();
            task.bind(agent::runSubagent);
        }

        List<TurnEvent> run(Consumer<TurnEvent.ApprovalRequest> approval) {
            return assertTimeoutPreemptively(Duration.ofSeconds(6), () -> {
                List<TurnEvent> events = new ArrayList<>();
                parent = agent.runTurn("委派修改");
                while (true) {
                    var event = parent.poll(100);
                    if (event == null) continue;
                    events.add(event);
                    if (event instanceof TurnEvent.ApprovalRequest request) {
                        if (request.tool().equals("task")) request.resolver().accept("y");
                        else approval.accept(request);
                    }
                    if (event instanceof TurnEvent.Done done) {
                        assertNull(done.error());
                        awaitExit();
                        return events;
                    }
                }
            });
        }

        void awaitExit() throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
            while (agent.subagentSnapshots().stream().anyMatch(s -> s.state() != SubagentState.EXITED)) {
                assertTrue(System.nanoTime() < deadline);
                Thread.sleep(10);
            }
            if (parentThread != null) { parentThread.join(3000); assertFalse(parentThread.isAlive()); }
        }

        SubagentHandle.Snapshot snapshot() { return agent.subagentSnapshots().getLast(); }
        List<JsonNode> audit() throws Exception {
            List<JsonNode> records = new ArrayList<>();
            for (String line : Files.readAllLines(Path.of(snapshot().auditFile()))) records.add(JSON.readTree(line));
            return records;
        }
        JsonNode completed() throws Exception {
            return audit().stream().filter(n -> n.path("phase").asText().equals("completed")).findFirst().orElseThrow();
        }
        String taskResult() {
            return agent.session().messages().stream().filter(m -> m.role() == Role.TOOL && "task1".equals(m.toolCallId()))
                    .findFirst().orElseThrow().content();
        }
        @Override public void close() throws Exception {
            if (parent != null) parent.cancel();
            agent.close();
            if (parentThread != null) parentThread.join(4000);
        }
    }

    private static ChatStream call(String id, String name, String arguments) {
        return TestStreams.of(new StreamChunk.ToolCallDelta(0, id, name, arguments), new StreamChunk.Done("tool_calls", null));
    }
    private static ChatStream answer(String text) {
        return TestStreams.of(new StreamChunk.Delta(text), new StreamChunk.Done("stop", null));
    }
    private static String version(String text) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test void editAndOverwritePreviewPrecedeSeparateApprovalAndProduceAttributedAudit() throws Exception {
        for (String tool : List.of("edit_file", "write_file")) {
            try (var f = new Fixture(tool, "edit", null, null, false, null)) {
                var events = f.run(request -> {
                    try {
                        assertEquals("old", Files.readString(f.file));
                        assertEquals(f.snapshot().id(), request.taskId());
                        assertTrue(f.audit().stream().anyMatch(n -> n.path("phase").asText().equals("prepared")));
                    } catch (Exception e) { throw new AssertionError(e); }
                    request.resolver().accept("y");
                });
                assertEquals("new", Files.readString(f.file));
                assertEquals("edit", f.snapshot().profile());
                var preview = events.stream().filter(e -> e instanceof TurnEvent.ToolPreview).findFirst().orElseThrow();
                var approval = events.stream().filter(e -> e instanceof TurnEvent.ApprovalRequest a && a.taskId() != null)
                        .findFirst().orElseThrow();
                assertTrue(events.indexOf(preview) < events.indexOf(approval));
                assertEquals(f.snapshot().id(), ((TurnEvent.ToolPreview) preview).taskId());
                assertTrue(((TurnEvent.ToolPreview) preview).text().contains("old"));
                var audit = f.completed();
                assertEquals("SUCCEEDED", audit.path("status").asText());
                assertTrue(f.audit().stream().anyMatch(n -> n.path("phase").asText().equals("permission")
                        && n.path("decision").asText().equals("ALLOW")));
                assertEquals("edit", audit.path("profile").asText());
                assertEquals(f.snapshot().parentTurnId(), audit.path("parent_turn_id").asText());
                assertEquals(f.snapshot().parentSessionId(), audit.path("parent_session_id").asText());
                assertEquals("file1", audit.path("tool_call_id").asText());
                assertEquals(version("old"), audit.path("expected_version").asText());
                assertTrue(audit.path("result").asText().contains(version("new")));
                assertEquals(64, audit.path("arguments_sha256").asText().length());
                assertEquals(List.of(ToolExecution.Status.SUCCEEDED), f.completions.stream().map(ToolExecution::status).toList());
                assertEquals(List.of(f.snapshot().id()), f.origins);
                assertEquals("子报告", f.taskResult());
                assertTrue(f.agent.subagentCommand(f.snapshot().id()).contains("审计日志:"));
            }
        }
    }

    @Test void newFileRequiresPreviewAndOwnApproval() throws Exception {
        try (var f = new Fixture("write_file", "edit", null, null, true, null)) {
            f.run(request -> { assertFalse(Files.exists(f.work.resolve("new.txt"))); request.resolver().accept("y"); });
            assertEquals("new", Files.readString(f.work.resolve("new.txt")));
            assertEquals("SUCCEEDED", f.completed().path("status").asText());
        }
    }

    @Test void rejectionLeavesFileUnchangedAndCallsAuditHooks() throws Exception {
        try (var f = new Fixture("edit_file", "edit", null, null, false, null)) {
            f.run(request -> request.resolver().accept("n"));
            assertEquals("old", Files.readString(f.file));
            assertEquals("DENIED", f.completed().path("status").asText());
            assertEquals(ToolExecution.Status.DENIED, f.completions.getFirst().status());
        }
    }

    @Test void sessionGrantStillShowsPreviewOnNextChildWithoutAnotherWriteApproval() throws Exception {
        try (var f = new Fixture("write_file", "edit", null, null, false, null)) {
            f.run(request -> request.resolver().accept("a"));
            String firstId = f.snapshot().id();
            Files.writeString(f.file, "old");
            var events = f.run(request -> fail("会话内文件写入已放行，不应再次确认"));
            assertNotEquals(firstId, f.snapshot().id());
            assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.ToolPreview p
                    && f.snapshot().id().equals(p.taskId())));
            assertEquals("new", Files.readString(f.file));
            assertEquals("SUCCEEDED", f.completed().path("status").asText());
        }
    }

    @Test void customPolicyCanDenyAfterApprovalAndStillReceivesCompletion() throws Exception {
        var policy = new AgentHook() {
            @Override public HookVerdict onBeforeToolCall(ToolCall call) {
                return call.function().name().equals("edit_file") ? HookVerdict.deny("项目策略拒绝") : HookVerdict.allow();
            }
        };
        try (var f = new Fixture("edit_file", "edit", null, null, false, policy)) {
            f.run(request -> request.resolver().accept("y"));
            assertEquals("old", Files.readString(f.file));
            assertEquals("DENIED", f.completed().path("status").asText());
            assertTrue(f.completed().path("result").asText().contains("项目策略拒绝"));
        }
    }

    @Test void changeDuringApprovalRejectsThePreparedPlan() throws Exception {
        try (var f = new Fixture("edit_file", "edit", null, null, false, null)) {
            var events = f.run(request -> {
                try { Files.writeString(f.file, "external change"); } catch (Exception e) { throw new AssertionError(e); }
                request.resolver().accept("y");
            });
            assertEquals("external change", Files.readString(f.file));
            assertEquals("FAILED", f.completed().path("status").asText());
            assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.ToolCallEnd end
                    && end.taskId() != null && !end.ok() && end.summary().contains("预览后发生变化")));
        }
    }

    @Test void staleVersionFailsBeforePreviewAndConfirmation() throws Exception {
        try (var f = new Fixture("write_file", "edit", null, version("stale"), false, null)) {
            var events = f.run(request -> fail("版本错误不应请求写入授权"));
            assertFalse(events.stream().anyMatch(e -> e instanceof TurnEvent.ToolPreview));
            assertEquals("old", Files.readString(f.file));
            assertEquals("FAILED", f.completed().path("status").asText());
        }
    }

    @Test void subsetAndExploreCannotReachWritePreparation() throws Exception {
        for (String profile : List.of("explore", "edit")) {
            try (var f = new Fixture("write_file", profile, Set.of("read_file"), null, false, null)) {
                var events = f.run(request -> fail("未授权的工具不应弹出确认"));
                assertEquals("old", Files.readString(f.file));
                assertFalse(events.stream().anyMatch(e -> e instanceof TurnEvent.ToolPreview));
                assertEquals(ToolExecution.Status.DENIED, f.completions.getFirst().status());
                if (profile.equals("edit")) assertEquals("DENIED", f.completed().path("status").asText());
            }
        }
    }

    @Test void cancelledApprovalCannotBeResurrectedByLateAnswer() throws Exception {
        try (var f = new Fixture("write_file", "edit", null, null, false, null)) {
            f.run(request -> { f.parent.cancel(); request.resolver().accept("a"); });
            assertEquals("old", Files.readString(f.file));
            assertEquals("CANCELLED", f.completed().path("status").asText());
            assertEquals(StopReason.PARENT_CANCELLED, f.snapshot().stopReason());
        }
    }

    @Test void noUiChannelDeniesStandaloneEditEvenWithNoOuterTaskApproval() throws Exception {
        try (var f = new Fixture("write_file", "edit", null, null, false, null)) {
            f.agent.runSubagent(new SubagentRequest("修改", SubagentProfile.EDIT, null), () -> false);
            assertEquals("old", Files.readString(f.file));
            assertEquals("DENIED", f.completed().path("status").asText());
            assertTrue(f.completed().path("result").asText().contains("没有可用的确认渠道"));
        }
    }

    @Test void timeoutWhileAwaitingApprovalClosesChildAndLateGrantDoesNotWrite() throws Exception {
        try (var f = new Fixture("edit_file", "edit", null, null, false, null)) {
            f.agent.configureSubagentTimeout(Duration.ofSeconds(1));
            var requested = new java.util.concurrent.atomic.AtomicBoolean();
            f.run(request -> {
                requested.set(true);
                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                    while (f.snapshot().state() != SubagentState.EXITED) Thread.sleep(10);
                });
                request.resolver().accept("a");
            });
            assertEquals("old", Files.readString(f.file));
            assertTrue(requested.get());
            assertEquals(StopReason.TIMEOUT, f.snapshot().stopReason());
            assertEquals("CANCELLED", f.completed().path("status").asText());
            assertTrue(f.taskResult().contains("子任务超时"));
        }
    }

    @Test void auditFailureBeforeAuthorizationPreventsWriting() throws Exception {
        try (var f = new Fixture("write_file", "edit", null, null, false, null)) {
            f.beforeChild = request -> {
                if (f.childRequests == 0) {
                    try {
                        Path audit = Path.of(f.snapshot().auditFile());
                        Files.delete(audit); Files.createDirectory(audit);
                    } catch (Exception e) { throw new AssertionError(e); }
                }
            };
            f.run(request -> fail("审计写入失败不应请求写入授权"));
            assertEquals("old", Files.readString(f.file));
            assertTrue(f.taskResult().contains("审计写入失败"));
        }
    }

    @Test void auditFailureAfterWritingReportsAppliedChangeInsteadOfClaimingRollback() throws Exception {
        var active = new java.util.concurrent.atomic.AtomicReference<Fixture>();
        var fault = new AgentHook() {
            @Override public void onToolPermission(ToolCall call, HookVerdict verdict) {
                if (call.function().name().equals("write_file") && verdict.proceed()) {
                    try {
                        Path audit = Path.of(active.get().snapshot().auditFile());
                        Files.delete(audit); Files.createDirectory(audit);
                    } catch (Exception e) { throw new AssertionError(e); }
                }
            }
        };
        try (var f = new Fixture("write_file", "edit", null, null, false, fault)) {
            active.set(f);
            var events = f.run(request -> request.resolver().accept("y"));
            assertEquals("new", Files.readString(f.file));
            assertTrue(f.taskResult().contains("审计写入失败"));
            assertTrue(f.taskResult().contains("此前已执行的修改不会回滚"));
            assertTrue(f.taskResult().contains("已覆盖"));
            assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.ToolCallEnd end
                    && end.tool().equals("task") && !end.ok()));
        }
    }

    @Test void editStillRejectsShellNetworkAndRecursiveDelegation() throws Exception {
        for (String name : List.of("run_command", "web_fetch", "task", "process_start")) {
            try (var f = new Fixture(name, "edit", null, null, false, null)) {
                f.run(request -> fail("不在预设中的工具不应请求权限"));
                assertEquals("DENIED", f.completed().path("status").asText());
                assertFalse(SubagentProfile.EDIT.tools().contains("task"));
                assertEquals("old", Files.readString(f.file));
            }
        }
    }
}
