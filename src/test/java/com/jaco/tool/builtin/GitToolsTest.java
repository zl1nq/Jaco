package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaco.llm.ToolCall;
import com.jaco.tool.PermissionHook;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;
import com.jaco.tool.ToolSandbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class GitToolsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;
    private Path repo;

    @BeforeEach
    void initialize() throws Exception {
        repo = Files.createDirectory(tmp.resolve("仓库 with space")).toRealPath();
        git("init", "-b", "main");
        git("config", "user.name", "Jaco Test");
        git("config", "user.email", "jaco@example.invalid");
        git("config", "core.autocrlf", "false");
    }

    private String git(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "--no-pager", "-c", "core.fsmonitor=false",
                "-c", "commit.gpgsign=false", "-c", "core.hooksPath=" + tmp.resolve("no-hooks")));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true);
        builder.environment().keySet().removeIf(key -> key.toUpperCase().startsWith("GIT_"));
        builder.environment().put("LC_ALL", "C");
        Process process = builder.start();
        process.getOutputStream().close();
        byte[] bytes = process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        String out = new String(bytes, StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), out);
        return out;
    }

    private void write(String file, String text) throws Exception {
        Path path = repo.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }

    private void commit() throws Exception {
        git("add", "--all");
        git("commit", "-m", "baseline");
    }

    private ToolContext ctx() {
        return new ToolContext(repo, new ToolSandbox(repo, List.of()), "invalid-shell-not-used",
                new AtomicReference<>(), () -> false, tmp.resolve("session.archive.jsonl"), 8000);
    }

    private String status(ObjectNode args) throws Exception {
        ToolContext ctx = ctx();
        String result = new GitStatusTool().execute(args, ctx);
        assertNull(ctx.currentProcess().get());
        return result;
    }

    private String diff(ObjectNode args) throws Exception {
        ToolContext ctx = ctx();
        String result = new GitDiffTool().execute(args, ctx);
        assertNull(ctx.currentProcess().get());
        return result;
    }

    private List<com.fasterxml.jackson.databind.JsonNode> entries(String out) throws Exception {
        List<com.fasterxml.jackson.databind.JsonNode> result = new ArrayList<>();
        for (String line : out.lines().filter(line -> line.startsWith("{\"index_status\"")).toList()) {
            result.add(MAPPER.readTree(line));
        }
        return result;
    }

    @Test
    void cleanAndUnbornRepositoriesAreSupported() throws Exception {
        String out = status(MAPPER.createObjectNode());
        assertTrue(out.contains("clean: true"));
        assertTrue(out.contains("main"));
        write("new.txt", "first\n");
        git("add", "new.txt");
        out = diff(MAPPER.createObjectNode().put("staged", true));
        assertTrue(out.contains("+first"));
        commit();
        assertTrue(status(MAPPER.createObjectNode()).contains("clean: true"));
        assertTrue(diff(MAPPER.createObjectNode()).contains("(no differences)"));
    }

    @Test
    void distinguishesStagedWorktreeAndUntrackedChanges() throws Exception {
        write("tracked.txt", "base\n");
        commit();
        write("tracked.txt", "staged\n");
        git("add", "tracked.txt");
        write("tracked.txt", "work\n");
        write("new.txt", "untracked\n");
        var records = entries(status(MAPPER.createObjectNode()));
        var tracked = records.stream().filter(e -> e.path("path").asText().equals("tracked.txt")).findFirst().orElseThrow();
        assertEquals("M", tracked.path("index_status").asText());
        assertEquals("M", tracked.path("worktree_status").asText());
        assertTrue(records.stream().anyMatch(e -> e.path("index_status").asText().equals("?")));
        String working = diff(MAPPER.createObjectNode());
        assertTrue(working.contains("-staged"));
        assertTrue(working.contains("+work"));
        assertFalse(working.contains("untracked"));
        String staged = diff(MAPPER.createObjectNode().put("staged", true));
        assertTrue(staged.contains("-base"));
        assertTrue(staged.contains("+staged"));
        assertFalse(staged.contains("+work"));
    }

    @Test
    void handlesRenameAndUnicodePaths() throws Exception {
        write("原文件 with space.txt", "base\n");
        commit();
        git("mv", "原文件 with space.txt", "新文件 with space.txt");
        var records = entries(status(MAPPER.createObjectNode()));
        assertEquals(1, records.size());
        assertEquals("R", records.getFirst().path("index_status").asText());
        assertEquals("新文件 with space.txt", records.getFirst().path("path").asText());
        assertEquals("原文件 with space.txt", records.getFirst().path("original_path").asText());
    }

    @Test
    void treatsPathFiltersLiterallyAndDoesNotInterpretOptions() throws Exception {
        write("[ab].txt", "base\n");
        write("a.txt", "base\n");
        write("--output=owned.txt", "base\n");
        commit();
        write("[ab].txt", "literal\n");
        write("a.txt", "other\n");
        write("--output=owned.txt", "option-like\n");
        ObjectNode args = MAPPER.createObjectNode();
        args.putArray("paths").add("[ab].txt");
        assertEquals("[ab].txt", entries(status(args)).getFirst().path("path").asText());
        String patch = diff(args);
        assertTrue(patch.contains("+literal"));
        assertFalse(patch.contains("+other"));
        args.putArray("paths").add("--output=owned.txt");
        assertTrue(diff(args).contains("+option-like"));
        assertFalse(Files.exists(repo.resolve("owned.txt")));
    }

    @Test
    void pathsAreRelativeToRepositoryRootEvenWhenStartingInSubdirectory() throws Exception {
        write("sub/a.txt", "base\n");
        write("b.txt", "base\n");
        commit();
        write("b.txt", "changed\n");
        ObjectNode args = MAPPER.createObjectNode().put("repo_path", "sub");
        args.putArray("paths").add("b.txt");
        assertEquals("b.txt", entries(status(args)).getFirst().path("path").asText());
        assertTrue(diff(args).contains("+changed"));
    }

    @Test
    void untrackedCanBeExcludedAndIgnoredFilesStayHidden() throws Exception {
        write(".gitignore", "ignored.txt\n");
        commit();
        write("new.txt", "new\n");
        write("ignored.txt", "ignored\n");
        var records = entries(status(MAPPER.createObjectNode()));
        assertEquals(1, records.size());
        assertEquals("new.txt", records.getFirst().path("path").asText());
        assertTrue(status(MAPPER.createObjectNode().put("include_untracked", false)).contains("clean: true"));
    }

    @Test
    void statusQueriesDoNotRefreshIndexOrMutateConfiguration() throws Exception {
        write("tracked.txt", "base\n");
        commit();
        byte[] index = Files.readAllBytes(repo.resolve(".git/index"));
        byte[] config = Files.readAllBytes(repo.resolve(".git/config"));
        write("tracked.txt", "changed and longer\n");
        status(MAPPER.createObjectNode());
        diff(MAPPER.createObjectNode());
        assertArrayEquals(index, Files.readAllBytes(repo.resolve(".git/index")));
        assertArrayEquals(config, Files.readAllBytes(repo.resolve(".git/config")));
        assertFalse(Files.exists(repo.resolve(".git/index.lock")));
    }

    @Test
    void disablesExternalDiffTextconvFsmonitorAndCleanFilters() throws Exception {
        write("tracked.txt", "base\n");
        commit();
        Path marker = tmp.resolve("hook-was-run");
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of(Probe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String probe = String.join(" ", List.of(executable, "-cp", classes, Probe.class.getName(), marker.toString()).stream()
                .map(text -> "'" + text.replace('\\', '/').replace("'", "'\\''") + "'").toList());
        git("config", "diff.external", probe);
        git("config", "diff.trap.textconv", probe);
        git("config", "core.fsmonitor", probe);
        git("config", "filter.trap.clean", probe);
        git("config", "filter.trap.process", probe);
        git("config", "filter.trap.required", "true");
        Files.writeString(repo.resolve(".git/info/attributes"), "*.txt diff=trap filter=trap\n");
        write("tracked.txt", "after\n");
        assertFalse(entries(status(MAPPER.createObjectNode())).isEmpty());
        assertTrue(diff(MAPPER.createObjectNode()).contains("+after"));
        assertFalse(Files.exists(marker), "只读查询执行了仓库外部程序");
    }

    @Test
    void outputTruncationIsExplicitAndFullDiffIsArchived() throws Exception {
        write("long.txt", "base\n");
        commit();
        write("long.txt", "changed\n".repeat(2000));
        String out = diff(MAPPER.createObjectNode().put("max_chars", 1000));
        assertTrue(out.contains("diff_truncated: true"));
        assertTrue(out.contains("archive_id:"));
        assertTrue(out.length() < 2500);
        assertTrue(Files.readString(ctx().archiveFile()).contains("changed"));
        write("new1.txt", "x");
        write("new2.txt", "x");
        out = status(MAPPER.createObjectNode().put("max_entries", 1));
        assertEquals(1, entries(out).size());
        assertTrue(out.contains("status_truncated: true"));
        assertTrue(out.contains("clean: false"));
    }

    @Test
    void respectsStatusOutputBudgetWithoutCuttingJsonEntries() throws Exception {
        for (int i = 0; i < 30; i++) write("file-" + i + ".txt", "x");
        ToolContext ctx = ctx();
        ToolContext limited = new ToolContext(repo, ctx.sandbox(), ctx.shellChoice(), ctx.currentProcess(),
                ctx.cancelled(), ctx.archiveFile(), 1000);
        String out = new GitStatusTool().execute(MAPPER.createObjectNode(), limited);
        assertTrue(out.length() <= 1000, "输出超限: " + out.length());
        assertTrue(out.contains("status_truncated: true"));
        assertFalse(entries(out).isEmpty());
    }

    @Test
    void validatesInputsAndRejectsPathsOutsideTheRepository() throws Exception {
        for (String json : List.of("[]", "{\"include_untracked\":\"true\"}", "{\"max_entries\":0}",
                "{\"repo_path\":\"..\"}", "{\"paths\":\"*.java\"}", "{\"paths\":[null]}", "{\"extra\":true}")) {
            assertThrows(ToolException.class, () -> new GitStatusTool().prepare(MAPPER.readTree(json), ctx()), json);
        }
        for (String json : List.of("null", "{\"staged\":1}", "{\"context_lines\":21}", "{\"max_chars\":999}",
                "{\"paths\":[false]}", "{\"command\":\"checkout\"}")) {
            assertThrows(ToolException.class, () -> new GitDiffTool().prepare(MAPPER.readTree(json), ctx()), json);
        }
        ObjectNode args = MAPPER.createObjectNode();
        args.putArray("paths").add("../outside.txt");
        assertThrows(ToolException.class, () -> status(args));
        assertThrows(ToolException.class, () -> diff(args));
    }

    @Test
    void rejectsNonRepositoriesAndRootsOutsideTheAllowedWorkspace() throws Exception {
        Path other = Files.createDirectory(tmp.resolve("plain"));
        ToolContext plain = new ToolContext(other, new ToolSandbox(other, List.of()), "auto", new AtomicReference<>(),
                () -> false, null);
        assertThrows(ToolException.class, () -> new GitStatusTool().execute(MAPPER.createObjectNode(), plain));
        Path sub = Files.createDirectory(repo.resolve("sub"));
        ToolContext restricted = new ToolContext(sub, new ToolSandbox(sub, List.of()), "auto", new AtomicReference<>(),
                () -> false, null);
        assertThrows(ToolException.class, () -> new GitDiffTool().execute(MAPPER.createObjectNode(), restricted));
    }

    @Test
    void cancellationDoesNotLeaveAProcessReference() {
        ToolContext base = ctx();
        ToolContext cancelled = new ToolContext(repo, base.sandbox(), "auto", base.currentProcess(), () -> true, null);
        ToolException error = assertThrows(ToolException.class,
                () -> new GitStatusTool().execute(MAPPER.createObjectNode(), cancelled));
        assertTrue(error.getMessage().contains("cancelled"));
        assertNull(cancelled.currentProcess().get());
    }

    @Test
    void incompleteOutputCannotBeReportedAsASuccessfulCleanQuery() {
        CommandOutput output = new CommandOutput(null, false, 1000);
        char[] branch = "## main\0".toCharArray();
        output.append(branch, branch.length);
        output.readFailed(new java.io.IOException("read failed"));
        output.finish();
        RunCommandTool.Result result = new RunCommandTool.Result("exited", 0, 1,
                new RunCommandTool.Options("git status", repo, 30, StandardCharsets.UTF_8), "", output);
        ToolException error = assertThrows(ToolException.class, () -> new GitSupport().requireSuccess(result));
        assertTrue(error.getMessage().contains("输出读取不完整"));
    }

    @Test
    void readOnlyGitPathsDoNotTriggerTheCommandDenyList() {
        PermissionHook permission = new PermissionHook();
        String args = "{\"paths\":[\"shutdown.txt\",\"reboot.md\"]}";
        assertTrue(permission.onBeforeToolCall(call("git_status", args)).proceed());
        assertTrue(permission.onBeforeToolCall(call("git_diff", args)).proceed());
        assertFalse(permission.onBeforeToolCall(call("run_command", "{\"command\":\"shutdown\"}")).proceed());
        assertFalse(permission.onBeforeToolCall(call("process_start", "{\"command\":\"reboot\"}")).proceed());
    }

    private ToolCall call(String name, String args) {
        return new ToolCall("id", "function", new ToolCall.FunctionCall(name, args));
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]), "invoked");
            System.in.transferTo(System.out);
        }
    }
}
