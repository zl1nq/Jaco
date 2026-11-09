package com.jaco.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaco.hook.HookChain;
import com.jaco.llm.ToolCall;
import com.jaco.session.*;
import com.jaco.tool.*;
import com.jaco.tool.builtin.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class SkillResourceTest {
    @TempDir Path dir;
    final ObjectMapper json = new ObjectMapper();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private ToolContext context() throws Exception {
        Path skill = dir.resolve("user-skills/review");
        Files.createDirectories(skill.resolve("references"));
        Files.writeString(skill.resolve("SKILL.md"), "---\nname: review\ndescription: Review\n---\nRead references/check.md");
        Files.writeString(skill.resolve("references/check.md"), "😀".repeat(2000) + "\nEND");
        Path work = dir.resolve("workspace"); Files.createDirectories(work);
        var store = new SessionStore(dir.resolve("sessions"));
        var owner = store.createNew();
        var service = new SkillService(new SkillRegistry(dir.resolve("user-skills"), null), owner, store, "t", active::get, 100000);
        var workflow = new WorkflowService(owner, store, active::get);
        return new ToolContext(work, new ToolSandbox(work, List.of()), "auto", null, () -> !active.get(), store.archivePath(owner), 1000, workflow, service);
    }
    private ObjectNode args(String path) { return json.createObjectNode().put("name", "review").put("path", path); }
    @Test void readsOutsideWorkspaceOnlyThroughLoadedPackageAndPreservesCursors() throws Exception {
        var ctx = context();
        var reader = new SkillReadTool();
        assertThrows(ToolException.class, () -> reader.execute(args("references/check.md"), ctx));
        ctx.skills().load("review", false);
        assertThrows(ToolException.class, () -> ctx.sandbox().resolve(dir.resolve("user-skills/review/references/check.md").toString()));
        String first = reader.execute(args("references/check.md"), ctx).text();
        assertTrue(first.length() <= 1000);
        assertTrue(first.contains("has_more=true"));
        var cursor = java.util.regex.Pattern.compile("next_line=(\\d+), next_column_offset=(\\d+)").matcher(first);
        assertTrue(cursor.find());
        int offset = Integer.parseInt(cursor.group(1)), column = Integer.parseInt(cursor.group(2));
        assertEquals(0, column % 2, "游标不能切断代理对");
        String second = reader.execute(args("references/check.md").put("offset", offset).put("column_offset", column), ctx).text();
        assertTrue(second.contains("has_more=true"));
        Files.writeString(dir.resolve("user-skills/review/references/check.md"), "changed");
        assertThrows(ToolException.class, () -> reader.execute(args("references/check.md"), ctx));
        assertThrows(ToolException.class, () -> reader.execute(args("references/check.md").put("expected_version", "sha256:" + "0".repeat(64)), ctx));
    }
    @Test void rejectsEscapeAbsolutePathsChangedSkillAndExpiredCalls() throws Exception {
        var ctx = context(); ctx.skills().load("review", false);
        var reader = new SkillReadTool();
        for (String path : List.of("../secret", "../../outside", dir.resolve("secret").toString(), "C:secret"))
            assertThrows(Exception.class, () -> reader.execute(args(path), ctx));
        active.set(false);
        assertThrows(ToolException.class, () -> reader.execute(args("references/check.md"), ctx));
        active.set(true);
        Files.writeString(dir.resolve("user-skills/review/SKILL.md"), "changed");
        assertThrows(java.io.IOException.class, () -> reader.execute(args("references/check.md"), ctx));
    }
    @Test void planAllowsSkillReadingButNeverGrantsWritesCommandsOrSubagents() throws Exception {
        var ctx = context(); ctx.workflow().enterPlan();
        var registry = new ToolRegistry(new SkillLoadTool(), new SkillReadTool(), new WriteFileTool(), new RunCommandTool());
        var executor = new ToolCallExecutor(registry, new HookChain(List.of(new PermissionHook(registry))));
        assertTrue(executor.execute(call("skill_load", "{\"name\":\"review\"}"), ctx, null, event -> {}).ok());
        assertTrue(executor.execute(call("skill_read", args("references/check.md").toString()), ctx, null, event -> {}).ok());
        for (String name : List.of("write_file", "run_command"))
            assertEquals(ToolExecution.Status.DENIED, executor.execute(call(name, "{}"), ctx, null, event -> {}).status());
        assertTrue(ctx.workflow().planning());
        assertEquals(ToolExecution.Status.DENIED, executor.execute(call("skill_read", args("references/check.md").toString()), ctx, Set.of("skill_read"), event -> {}).status());
        ctx.workflow().cancelPlan();
        assertEquals(ToolExecution.Status.DENIED, executor.execute(call("write_file", "{\"path\":\"file.txt\",\"content\":\"x\"}"), ctx, null, event -> {}).status());
        assertFalse(Files.exists(ctx.workspaceRoot().resolve("file.txt")));
    }
    @Test void symlinkCannotEscapePackage() throws Exception {
        var ctx = context(); ctx.skills().load("review", false);
        Path outside = dir.resolve("private.txt"); Files.writeString(outside, "PRIVATE");
        try { Files.createSymbolicLink(dir.resolve("user-skills/review/link.txt"), outside); }
        catch (java.io.IOException | UnsupportedOperationException e) { org.junit.jupiter.api.Assumptions.abort("平台不支持符号链接"); }
        assertThrows(ToolException.class, () -> new SkillReadTool().execute(args("link.txt"), ctx));
    }
    private ToolCall call(String name, String args) { return new ToolCall(name, "function", new ToolCall.FunctionCall(name, args)); }
}
