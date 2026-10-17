package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaco.agent.AgentRunner;
import com.jaco.config.ProviderConfig;
import com.jaco.hook.HookChain;
import com.jaco.llm.ToolCall;
import com.jaco.session.SessionStore;
import com.jaco.tool.PermissionHook;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;
import com.jaco.tool.ToolRegistry;
import com.jaco.tool.ToolSandbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class BackgroundProcessManagerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final Set<String> FINISHED = Set.of("exited", "timed_out", "stopped", "start_failed", "execution_failed");
    private final BackgroundProcessManager manager = new BackgroundProcessManager();

    @TempDir
    Path tmp;

    @AfterEach
    void cleanup() {
        manager.close();
    }

    private ToolContext ctx() {
        return context(tmp.resolve("session.archive.jsonl"), new AtomicBoolean(), 8000);
    }

    private ToolContext context(Path archive, AtomicBoolean cancelled, int budget) {
        return new ToolContext(tmp, new ToolSandbox(tmp, List.of()), WINDOWS ? "cmd" : "bash",
                new AtomicReference<>(), cancelled::get, archive, budget);
    }

    private String quote(String text) {
        return WINDOWS ? "\"" + text + "\"" : "'" + text.replace("'", "'\"'\"'") + "'";
    }

    private ObjectNode command(String... arguments) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java").toString();
        Class<?> fixture = RunCommandToolTest.Fixture.class;
        String classes = Path.of(fixture.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        List<String> parts = new ArrayList<>(List.of(java, "-cp", classes, fixture.getName()));
        parts.addAll(List.of(arguments));
        return MAPPER.createObjectNode().put("command", (WINDOWS ? "call " : "")
                + String.join(" ", parts.stream().map(this::quote).toList())).put("encoding", "UTF-8");
    }

    private String start(ObjectNode args, ToolContext ctx) throws Exception {
        return field(new ProcessStartTool(manager).execute(args, ctx), "task_id");
    }

    private String poll(String id, long offset, int wait, ToolContext ctx) throws Exception {
        return new ProcessPollTool(manager).execute(MAPPER.createObjectNode().put("task_id", id)
                .put("offset", offset).put("wait_ms", wait), ctx);
    }

    private String stop(String id, ToolContext ctx) throws Exception {
        return new ProcessStopTool(manager).execute(MAPPER.createObjectNode().put("task_id", id), ctx);
    }

    private String field(String out, String name) {
        String prefix = name + ": ";
        return out.lines().filter(line -> line.startsWith(prefix)).findFirst().orElseThrow(() -> new AssertionError(out))
                .substring(prefix.length());
    }

    private String body(String out) {
        return out.substring(out.indexOf("\n\n") + 2).replace("\r\n", "\n");
    }

    private String finished(String id, ToolContext ctx) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String out;
        do {
            out = poll(id, 0, 0, ctx);
            if (FINISHED.contains(field(out, "status"))) return out;
            TimeUnit.MILLISECONDS.sleep(30);
        } while (System.nanoTime() < deadline);
        fail("任务未结束: " + out);
        return out;
    }

    @Test
    void startsImmediatelyAndPollsIncrementalLogsWithoutDuplicates() throws Exception {
        ToolContext ctx = ctx();
        long before = System.nanoTime();
        String id = start(command("stream"), ctx);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 1500);
        assertNull(ctx.currentProcess().get(), "后台任务不能注册为当前对话的前台进程");
        StringBuilder logs = new StringBuilder();
        long offset = 0;
        for (int i = 0; i < 15; i++) {
            String page = poll(id, offset, 2000, ctx);
            String text = body(page);
            if (!text.equals("(no new output)")) logs.append(text);
            offset = Long.parseLong(field(page, "next_offset"));
            if (FINISHED.contains(field(page, "status")) && field(page, "has_more").equals("false")) break;
        }
        assertEquals("FIRST\nSECOND\nEND\n", logs.toString());
        assertEquals("0", field(finished(id, ctx), "exit_code"));
        assertEquals("(no new output)", body(poll(id, offset, 0, ctx)));
    }

    @Test
    void retainsFailedExitResultAndStoppingFinishedTaskIsIdempotent() throws Exception {
        ToolContext ctx = ctx();
        String id = start(command("exit"), ctx);
        String done = finished(id, ctx);
        assertEquals("exited", field(done, "status"));
        assertEquals("7", field(done, "exit_code"));
        assertTrue(body(done).contains("STDOUT"));
        assertTrue(body(done).contains("STDERR"));
        String stopped = stop(id, ctx);
        assertEquals("exited", field(stopped, "status"));
        assertEquals("7", field(stopped, "exit_code"));
        assertTrue(new ProcessPollTool(manager).execute(MAPPER.createObjectNode(), ctx).contains(id));
        assertTrue(Files.readString(ctx.archiveFile()).contains("STDOUT"), "小日志也应归档");
    }

    @Test
    void stopsTaskAndItsChildProcess() throws Exception {
        ToolContext ctx = ctx();
        Path pidFile = tmp.resolve("child.pid");
        String id = start(command("spawn", pidFile.toString()), ctx);
        waitForFile(pidFile);
        long pid = Long.parseLong(Files.readString(pidFile));
        assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        assertEquals("stopped", field(stop(id, ctx), "status"));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        assertEquals("stopped", field(stop(id, ctx), "status"));
    }

    @Test
    void timesOutInBackground() throws Exception {
        ToolContext ctx = ctx();
        String id = start(command("wait").put("timeout_seconds", 1), ctx);
        String done = finished(id, ctx);
        assertEquals("timed_out", field(done, "status"));
        assertEquals("null", field(done, "exit_code"));
    }

    @Test
    void foregroundCancellationAndPollCancellationDoNotStopBackgroundTask() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        ToolContext original = context(tmp.resolve("session.archive.jsonl"), cancelled, 8000);
        String id = start(command("wait"), original);
        cancelled.set(true);
        assertThrows(InterruptedException.class, () -> poll(id, 0, 10000, original));
        assertFalse(FINISHED.contains(field(poll(id, 0, 0, ctx()), "status")));
        assertEquals("stopped", field(stop(id, ctx()), "status"));
    }

    @Test
    void rejectsCancelledStartAndCrossSessionAccess() throws Exception {
        ToolContext ctx = ctx();
        String id = start(command("wait"), ctx);
        ToolContext other = context(tmp.resolve("other.archive.jsonl"), new AtomicBoolean(), 8000);
        assertThrows(ToolException.class, () -> poll(id, 0, 0, other));
        assertThrows(ToolException.class, () -> stop(id, other));
        assertThrows(ToolException.class, () -> start(command("wait"), other));
        ToolContext cancelled = context(ctx.archiveFile(), new AtomicBoolean(true), 8000);
        assertThrows(InterruptedException.class, () -> start(command("wait"), cancelled));
        assertEquals("stopped", field(stop(id, ctx), "status"));
    }

    @Test
    void capsRunningTasksAndFreesSlotAfterStop() throws Exception {
        ToolContext ctx = ctx();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) ids.add(start(command("wait"), ctx));
        assertThrows(ToolException.class, () -> start(command("wait"), ctx));
        stop(ids.getFirst(), ctx);
        String replacement = start(command("wait"), ctx);
        assertFalse(FINISHED.contains(field(poll(replacement, 0, 0, ctx), "status")));
    }

    @Test
    void evictsOnlyOldFinishedTasksWhenHistoryIsFull() throws Exception {
        ToolContext ctx = ctx();
        String first = null;
        String last = null;
        for (int i = 0; i < 33; i++) {
            last = start(MAPPER.createObjectNode().put("command", WINDOWS ? "exit /b 0" : "exit 0"), ctx);
            if (first == null) first = last;
            assertEquals("exited", field(finished(last, ctx), "status"));
        }
        String evicted = first;
        assertThrows(ToolException.class, () -> poll(evicted, 0, 0, ctx));
        assertEquals("exited", field(poll(last, 0, 0, ctx), "status"));
        String listing = new ProcessPollTool(manager).execute(MAPPER.createObjectNode(), ctx);
        assertEquals(32, listing.lines().count());
    }

    @Test
    void showsLogGapAndHonorsToolOutputBudget() throws Exception {
        ToolContext ctx = context(tmp.resolve("session.archive.jsonl"), new AtomicBoolean(), 1000);
        String id = start(command("large", "2100000"), ctx);
        String page = finished(id, ctx);
        assertEquals("true", field(page, "log_gap"));
        assertEquals("true", field(page, "archive_truncated"));
        assertTrue(Long.parseLong(field(page, "available_from")) > 0);
        assertTrue(page.length() <= 1000, "回填超预算: " + page.length());
        assertTrue(page.contains("archive_id:"));
        assertThrows(ToolException.class, () -> poll(id, Long.MAX_VALUE, 0, ctx));
    }

    @Test
    void newSessionStopsTasksAndInvalidatesPreparedStarts() throws Exception {
        SessionStore store = new SessionStore(tmp);
        AgentRunner agent = new AgentRunner(null, new ProviderConfig("http://localhost", "key", "model", null),
                null, store, new HookChain(List.of(manager)), new ToolRegistry(), new ToolSandbox(tmp, List.of()),
                tmp, WINDOWS ? "cmd" : "bash", 25, 100000, 8000);
        agent.start();
        ToolContext ctx = context(store.archivePath(agent.session()), new AtomicBoolean(), 8000);
        Path pidFile = tmp.resolve("session-child.pid");
        String id = start(command("spawn", pidFile.toString()), ctx);
        waitForFile(pidFile);
        long pid = Long.parseLong(Files.readString(pidFile));
        var prepared = new ProcessStartTool(manager).prepare(command("wait"), ctx);
        assertNotNull(agent.switchSession("missing-session"));
        assertFalse(FINISHED.contains(field(poll(id, 0, 0, ctx), "status")));
        agent.newSession();
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        assertThrows(ToolException.class, prepared::execute);
        assertThrows(ToolException.class, () -> poll(id, 0, 0, ctx));
        ToolContext next = context(store.archivePath(agent.session()), new AtomicBoolean(), 8000);
        assertTrue(new ProcessPollTool(manager).execute(MAPPER.createObjectNode(), next).contains("没有后台任务"));
    }

    @Test
    void closeStopsProcessesAndRejectsNewStarts() throws Exception {
        ToolContext ctx = ctx();
        Path pidFile = tmp.resolve("close-child.pid");
        start(command("spawn", pidFile.toString()), ctx);
        waitForFile(pidFile);
        long pid = Long.parseLong(Files.readString(pidFile));
        CompletableFuture<Void> switcher = CompletableFuture.runAsync(manager::onSessionChanged);
        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(manager::close);
        CompletableFuture.allOf(switcher, shutdown).get(10, TimeUnit.SECONDS);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        assertThrows(ToolException.class, () -> start(command("wait"), ctx));
        manager.close();
    }

    @Test
    void validatesArgumentsBeforeAnyTaskIsCreated() throws Exception {
        ProcessStartTool start = new ProcessStartTool(manager);
        for (String json : List.of("{}", "[]", "{\"command\":\" \"}", "{\"command\":false}",
                "{\"command\":\"echo x\",\"cwd\":\"..\"}", "{\"command\":\"echo x\",\"timeout_seconds\":86401}",
                "{\"command\":\"echo x\",\"encoding\":\"bad-charset\"}", "{\"command\":\"echo x\",\"extra\":1}")) {
            assertThrows(ToolException.class, () -> start.prepare(MAPPER.readTree(json), ctx()), json);
        }
        for (String json : List.of("null", "{\"offset\":0}", "{\"task_id\":\"x\",\"offset\":-1}",
                "{\"task_id\":\"x\",\"offset\":1.5}", "{\"task_id\":\"x\",\"limit\":0}",
                "{\"task_id\":\"x\",\"wait_ms\":10001}", "{\"task_id\":false}")) {
            assertThrows(ToolException.class, () -> new ProcessPollTool(manager).prepare(MAPPER.readTree(json), ctx()), json);
        }
        assertThrows(ToolException.class, () -> new ProcessStopTool(manager).prepare(MAPPER.createObjectNode(), ctx()));
        var prepared = start.prepare(command("wait"), ctx());
        assertNotNull(prepared);
        assertTrue(new ProcessPollTool(manager).execute(MAPPER.createObjectNode(), ctx()).contains("没有后台任务"));
    }

    @Test
    void pollIsReadOnlyAndStartAndStopRequireApproval() {
        PermissionHook permission = new PermissionHook();
        assertTrue(permission.onBeforeToolCall(call("process_poll", "{}")).proceed());
        assertFalse(permission.onBeforeToolCall(call("process_start", "{\"command\":\"echo x\"}")).proceed());
        assertFalse(permission.onBeforeToolCall(call("process_stop", "{\"task_id\":\"x\"}")).proceed());
        assertFalse(permission.onBeforeToolCall(call("process_start", "{\"command\":\"format C:\"}")).proceed());
    }

    private ToolCall call(String name, String args) {
        return new ToolCall("id", "function", new ToolCall.FunctionCall(name, args));
    }

    private void waitForFile(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((!Files.exists(file) || Files.size(file) == 0) && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertTrue(Files.exists(file), "子进程未启动");
        assertTrue(Files.size(file) > 0);
    }
}
