package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaco.llm.ToolCall;
import com.jaco.tool.PermissionHook;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;
import com.jaco.tool.ToolSandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FindFilesToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    private void file(String name) throws Exception {
        Path file = tmp.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "content");
    }

    private ToolContext ctx() {
        return new ToolContext(tmp, new ToolSandbox(tmp, List.of()), "auto", null, () -> false, null);
    }

    private String find(ObjectNode args) throws Exception {
        return new FindFilesTool().execute(args, ctx());
    }

    @Test
    void findsRootAndNestedFilesByNameOrPath() throws Exception {
        file("RootTool.java");
        file("src/ReadTool.java");
        file("src/nested/WriteTool.java");
        file("src/Other.txt");
        String out = find(MAPPER.createObjectNode().put("pattern", "**/*Tool.java"));
        assertEquals(3, out.lines().count());
        assertTrue(out.contains("RootTool.java"));
        assertTrue(out.contains("src/ReadTool.java"));
        assertTrue(out.contains("src/nested/WriteTool.java"));
        assertEquals("src/ReadTool.java", find(MAPPER.createObjectNode().put("pattern", "src/*Tool.java")));
        assertEquals(3, find(MAPPER.createObjectNode().put("pattern", "*Tool.java")).lines().count());
    }

    @Test
    void excludesDirectoriesAndFilesWithOverrideForDefaults() throws Exception {
        file("src/a.java");
        file("target/b.java");
        file("generated/c.java");
        ObjectNode args = MAPPER.createObjectNode().put("pattern", "*.java");
        args.putArray("exclude").add("generated");
        assertEquals("src/a.java", find(args));
        String out = find(args.put("include_ignored", true));
        assertEquals(2, out.lines().count());
        assertTrue(out.contains("target/b.java"));
        assertFalse(out.contains("generated"));
        args.putArray("exclude").add("target").add("*.java");
        assertEquals("(no matches)", find(args));
    }

    @Test
    void handlesSingleFileAndResultLimit() throws Exception {
        file("a.txt");
        file("b.txt");
        assertEquals("a.txt", find(MAPPER.createObjectNode().put("pattern", "*.txt").put("path", "a.txt")));
        String out = find(MAPPER.createObjectNode().put("pattern", "*.txt").put("max_results", 1));
        assertEquals(1, out.lines().filter(s -> s.endsWith(".txt")).count());
        assertTrue(out.contains("搜索提前停止"));
    }

    @Test
    void rejectsBadArgumentsAndOutsidePaths() throws Exception {
        for (String json : List.of("{}", "null", "{\"pattern\":\"[\"}",
                "{\"pattern\":\"*\",\"path\":\"..\"}", "{\"pattern\":\"*\",\"max_results\":1001}",
                "{\"pattern\":\"*\",\"max_results\":1.5}", "{\"pattern\":\"*\",\"exclude\":[null]}",
                "{\"pattern\":\"*\",\"include_ignored\":1}")) {
            assertThrows(ToolException.class, () -> new FindFilesTool().execute(MAPPER.readTree(json), ctx()), json);
        }
    }

    @Test
    void cancellationStopsSearch() {
        ToolContext context = new ToolContext(tmp, new ToolSandbox(tmp, List.of()), "auto", null, () -> true, null);
        assertThrows(ToolException.class, () -> new FindFilesTool().execute(
                MAPPER.createObjectNode().put("pattern", "*"), context));
    }

    @Test
    void readOnlyToolDoesNotNeedApproval() {
        ToolCall call = new ToolCall("id", "function", new ToolCall.FunctionCall("find_files", "{\"pattern\":\"*.java\"}"));
        assertTrue(new PermissionHook().onBeforeToolCall(call).proceed());
    }

    @Test
    void doesNotListExternalSymlinks() throws Exception {
        Path workspace = Files.createDirectory(tmp.resolve("workspace"));
        Path external = Files.writeString(tmp.resolve("secret.txt"), "secret");
        try {
            Files.createSymbolicLink(workspace.resolve("link.txt"), external);
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "当前环境无法创建符号链接: " + e.getMessage());
        }
        ToolContext context = new ToolContext(workspace, new ToolSandbox(workspace, List.of()), "auto", null, () -> false, null);
        String out = new FindFilesTool().execute(MAPPER.createObjectNode().put("pattern", "*.txt"), context);
        assertTrue(out.startsWith("(no matches)"));
        assertTrue(out.contains("符号链接"));
    }
}
