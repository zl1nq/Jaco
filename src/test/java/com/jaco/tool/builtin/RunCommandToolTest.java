package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaco.agent.TurnHandle;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;
import com.jaco.tool.ToolSandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class RunCommandToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    @TempDir
    Path tmp;

    private ToolContext ctx(Path archive, AtomicReference<Process> process, BooleanSupplier cancelled) {
        return new ToolContext(tmp, new ToolSandbox(tmp, List.of()), WINDOWS ? "cmd" : "bash", process, cancelled, archive);
    }

    private ToolContext ctx() {
        return ctx(tmp.resolve("session.archive.jsonl"), new AtomicReference<>(), () -> false);
    }

    private String quote(String text) {
        return WINDOWS ? "\"" + text + "\"" : "'" + text.replace("'", "'\"'\"'") + "'";
    }

    private String command(String... arguments) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java").toString();
        String classes = Path.of(Fixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        List<String> parts = new ArrayList<>(List.of(java, "-cp", classes, Fixture.class.getName()));
        parts.addAll(List.of(arguments));
        return (WINDOWS ? "call " : "") + String.join(" ", parts.stream().map(this::quote).toList());
    }

    private ObjectNode args(String... arguments) throws Exception {
        return MAPPER.createObjectNode().put("command", command(arguments)).put("encoding", "UTF-8");
    }

    private String run(String... arguments) throws Exception {
        ToolContext context = ctx();
        String out = new RunCommandTool().execute(args(arguments), context);
        assertNull(context.currentProcess().get());
        return out;
    }

    @Test
    void capturesMergedOutputAndNonZeroExitCode() throws Exception {
        String out = run("exit");
        assertTrue(out.contains("status: exited"), out);
        assertTrue(out.contains("exit_code: 7"), out);
        assertTrue(out.contains("STDOUT"));
        assertTrue(out.contains("STDERR"));
    }

    @Test
    void closesStdinSoCommandsDoNotWaitForInteractiveInput() throws Exception {
        assertTrue(run("stdin").contains("EOF"));
    }

    @Test
    void releasesOutputReaderAfterNormalExit() throws Exception {
        String out = run("exit");
        assertFalse(out.contains("输出读取不完整"), out);
        assertFalse(Files.exists(tmp.resolve("session.archive.jsonl")));
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .noneMatch(t -> t.isAlive() && t.getName().startsWith("jaco-command-output-")));
    }

    @Test
    void respectsCwdAndExplicitEncoding() throws Exception {
        Path sub = Files.createDirectory(tmp.resolve("sub"));
        String out = new RunCommandTool().execute(args("cwd").put("cwd", "sub"), ctx());
        assertTrue(out.contains(sub.toRealPath().toString()), out);
        out = new RunCommandTool().execute(args("encoding", "GBK").put("encoding", "GBK"), ctx());
        assertTrue(out.contains("中文输出"), out);
    }

    @Test
    void drainsLargeOutputAndArchivesPrefixWithBoundedSize() throws Exception {
        ToolContext context = ctx();
        String out = new RunCommandTool().execute(args("large", "2100000"), context);
        assertTrue(out.contains("status: exited"), out.substring(0, Math.min(out.length(), 300)));
        assertTrue(out.contains("exit_code: 0"));
        assertTrue(out.contains("output_truncated: true"));
        assertTrue(out.contains("archive_truncated: true"));
        assertTrue(out.endsWith("END\n"));
        assertTrue(out.length() < CommandOutput.PREVIEW_LIMIT + 1000);
        List<String> chunks = Files.readAllLines(context.archiveFile());
        int stored = 0;
        String id = out.lines().filter(s -> s.startsWith("archive_id: ")).findFirst().orElseThrow().substring(12);
        for (String chunk : chunks) {
            String content = MAPPER.readTree(chunk).path("content").asText();
            assertTrue(content.contains(id));
            stored += content.length() - content.indexOf('\n') - 1;
        }
        assertEquals(CommandOutput.ARCHIVE_LIMIT, stored);
        assertTrue(chunks.getFirst().contains("BEGIN"));
        String recalled = new RecallTool().execute(MAPPER.createObjectNode().put("query", id), context);
        assertTrue(recalled.contains(id));
        assertTrue(recalled.contains("BEGIN"));
    }

    @Test
    void reportsArchiveFailureWithoutBreakingCommandOutput() throws Exception {
        ToolContext context = ctx(tmp.resolve("missing/archive.jsonl"), new AtomicReference<>(), () -> false);
        String out = new RunCommandTool().execute(args("large", "100000"), context);
        assertTrue(out.contains("status: exited"));
        assertTrue(out.contains("输出归档不完整"));
        assertTrue(out.endsWith("END\n"));
    }

    @Test
    void stillDrainsWithoutAnArchive() throws Exception {
        String out = new RunCommandTool().execute(args("large", "100000"), ctx(null, new AtomicReference<>(), () -> false));
        assertTrue(out.contains("status: exited"));
        assertTrue(out.contains("无可用输出归档"));
        assertTrue(out.endsWith("END\n"));
    }

    @Test
    void timesOutAndTerminatesChildProcess() throws Exception {
        Path pidFile = tmp.resolve("child.pid");
        ToolContext context = ctx();
        String out = new RunCommandTool().execute(args("spawn", pidFile.toString()).put("timeout_seconds", 2), context);
        assertTrue(out.contains("status: timed_out"), out);
        assertTrue(out.contains("exit_code: null"));
        assertNull(context.currentProcess().get());
        assertChildStopped(pidFile);
    }

    @Test
    void turnCancellationKillsTreeAndReleasesProcessReference() throws Exception {
        TurnHandle turn = new TurnHandle();
        Path pidFile = tmp.resolve("child.pid");
        ToolContext context = ctx(tmp.resolve("session.archive.jsonl"), turn.processRef(), turn::isCancelled);
        CompletableFuture<String> result = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try {
                result.complete(new RunCommandTool().execute(uncheckedArgs("spawn", pidFile.toString()), context));
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        worker.start();
        try {
            waitForFile(pidFile);
            turn.cancel();
            String out = result.get(8, TimeUnit.SECONDS);
            assertTrue(out.contains("status: cancelled"), out);
            assertNull(turn.processRef().get());
            assertChildStopped(pidFile);
        } finally {
            turn.cancel();
            worker.interrupt();
            worker.join(5000);
        }
    }

    private ObjectNode uncheckedArgs(String... arguments) {
        try {
            return args(arguments);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void interruptionCleansUpAndPreservesInterruptFlag() throws Exception {
        ToolContext context = ctx();
        AtomicBoolean restored = new AtomicBoolean();
        CompletableFuture<String> result = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try {
                String out = new RunCommandTool().execute(uncheckedArgs("wait"), context);
                restored.set(Thread.currentThread().isInterrupted());
                result.complete(out);
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        worker.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (context.currentProcess().get() == null && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(20);
            }
            Process process = context.currentProcess().get();
            assertNotNull(process);
            worker.interrupt();
            assertTrue(result.get(8, TimeUnit.SECONDS).contains("status: cancelled"));
            assertTrue(restored.get());
            assertFalse(process.isAlive());
            assertNull(context.currentProcess().get());
        } finally {
            worker.interrupt();
            worker.join(5000);
        }
    }

    @Test
    void cancellationBeforeStartDoesNotExecuteCommand() throws Exception {
        AtomicReference<Process> ref = new AtomicReference<>();
        String out = new RunCommandTool().execute(args("exit"), ctx(null, ref, () -> true));
        assertTrue(out.contains("status: cancelled"));
        assertNull(ref.get());
    }

    @Test
    void validatesArgumentsBeforeApprovalAndRechecksCwdBeforeExecution() throws Exception {
        RunCommandTool tool = new RunCommandTool();
        for (String json : List.of("{}", "null", "[]", "{\"command\":false}", "{\"command\":\" \"}",
                "{\"command\":\"echo x\",\"cwd\":\"..\"}", "{\"command\":\"echo x\",\"cwd\":\"missing\"}",
                "{\"command\":\"echo x\",\"encoding\":\"not-a-charset\"}",
                "{\"command\":\"echo x\",\"timeout_seconds\":0}", "{\"command\":\"echo x\",\"timeout_seconds\":601}",
                "{\"command\":\"echo x\",\"timeout_seconds\":1.5}", "{\"command\":\"echo x\",\"unknown\":1}")) {
            assertThrows(ToolException.class, () -> tool.prepare(MAPPER.readTree(json), ctx()), json);
        }
        Path sub = Files.createDirectory(tmp.resolve("sub"));
        var prepared = tool.prepare(args("exit").put("cwd", "sub"), ctx());
        Files.delete(sub);
        assertThrows(ToolException.class, prepared::execute);
    }

    private void waitForFile(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((!Files.exists(file) || Files.size(file) == 0) && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertTrue(Files.exists(file), "子进程未启动");
        assertTrue(Files.size(file) > 0);
    }

    private void assertChildStopped(Path pidFile) throws Exception {
        assertTrue(Files.exists(pidFile), "子进程 PID 未写入");
        long pid = Long.parseLong(Files.readString(pidFile));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "子进程仍存活: " + pid);
    }

    /** 通过真实 shell 启动的测试进程，避免依赖系统命令或本机编码。 */
    public static class Fixture {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "exit" -> {
                    System.out.println("STDOUT");
                    System.err.println("STDERR");
                    System.exit(7);
                }
                case "stdin" -> System.out.println(System.in.read() == -1 ? "EOF" : "INPUT");
                case "cwd" -> System.out.println(Path.of(".").toRealPath());
                case "encoding" -> System.out.write("中文输出".getBytes(Charset.forName(args[1])));
                case "large" -> {
                    System.out.write("BEGIN\n".getBytes(StandardCharsets.UTF_8));
                    byte[] block = "x".repeat(4096).getBytes(StandardCharsets.UTF_8);
                    int remaining = Integer.parseInt(args[1]);
                    while (remaining > 0) {
                        int length = Math.min(remaining, block.length);
                        System.out.write(block, 0, length);
                        remaining -= length;
                    }
                    System.out.write("\nEND\n".getBytes(StandardCharsets.UTF_8));
                }
                case "spawn" -> {
                    boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
                    String java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
                    new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Fixture.class.getName(), "child", args[1])
                            .inheritIO().start();
                    TimeUnit.SECONDS.sleep(60);
                }
                case "child" -> {
                    Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                    TimeUnit.SECONDS.sleep(60);
                }
                case "wait" -> TimeUnit.SECONDS.sleep(60);
                default -> throw new IllegalArgumentException(args[0]);
            }
        }
    }
}
