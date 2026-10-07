package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaco.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecallToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    private ToolContext ctx(Path archive) throws Exception {
        return new ToolContext(tmp, null, "auto", null, () -> false, archive);
    }

    private Path writeArchive(String... lines) throws Exception {
        Path archive = tmp.resolve("s1.archive.jsonl");
        Files.write(archive, List.of(lines), StandardCharsets.UTF_8);
        return archive;
    }

    @Test
    void emptyArchiveReturnsHint() throws Exception {
        String out = new RecallTool().execute(MAPPER.createObjectNode(), ctx(null));
        assertTrue(out.contains("归档为空"));
    }

    @Test
    void missingArchiveReturnsHint() throws Exception {
        String out = new RecallTool().execute(MAPPER.createObjectNode(), ctx(tmp.resolve("nope.jsonl")));
        assertTrue(out.contains("归档为空"));
    }

    @Test
    void returnsBothEntryKindsWithIndexes() throws Exception {
        Path archive = writeArchive(
                MAPPER.writeValueAsString(java.util.Map.of("archived", "tool_output", "content", "命令输出 ABC")),
                MAPPER.writeValueAsString(MAPPER.readTree(
                        "{\"role\":\"user\",\"content\":\"早前的用户消息\"}")));
        String out = new RecallTool().execute(MAPPER.createObjectNode(), ctx(archive));
        assertTrue(out.contains("[#0] (tool_output) 命令输出 ABC"));
        assertTrue(out.contains("[#1] (user) 早前的用户消息"));
        assertTrue(out.contains("归档共 2 条"));
    }

    @Test
    void queryFiltersEntries() throws Exception {
        Path archive = writeArchive(
                MAPPER.writeValueAsString(java.util.Map.of("archived", "tool_output", "content", "mvn test 输出")),
                MAPPER.writeValueAsString(MAPPER.readTree(
                        "{\"role\":\"assistant\",\"content\":\"另一条消息\"}")));
        var args = MAPPER.createObjectNode();
        args.put("query", "mvn");
        String out = new RecallTool().execute(args, ctx(archive));
        assertTrue(out.contains("mvn test 输出"));
        assertFalse(out.contains("另一条消息"));
        assertEquals("命中 1 / 归档共 2 条", out.split("\n")[0].split("，")[0]);
    }

    @Test
    void offsetLimitPaging() throws Exception {
        Path archive = writeArchive(
                MAPPER.writeValueAsString(java.util.Map.of("archived", "tool_output", "content", "条目" + 0)),
                MAPPER.writeValueAsString(java.util.Map.of("archived", "tool_output", "content", "条目" + 1)),
                MAPPER.writeValueAsString(java.util.Map.of("archived", "tool_output", "content", "条目" + 2)));
        var args = MAPPER.createObjectNode();
        args.put("offset", 1);
        args.put("limit", 1);
        String out = new RecallTool().execute(args, ctx(archive));
        assertTrue(out.contains("[#1] (tool_output) 条目1"));
        assertFalse(out.contains("条目0"));
        assertTrue(out.contains("翻页"));
    }

    @Test
    void invalidRegexBecomesToolException() throws Exception {
        Path archive = writeArchive(
                MAPPER.writeValueAsString(java.util.Map.of("archived", "tool_output", "content", "内容")));
        var args = MAPPER.createObjectNode();
        args.put("query", "[invalid");
        assertThrows(com.jaco.tool.ToolException.class,
                () -> new RecallTool().execute(args, ctx(archive)));
    }

    @Test
    void longContentIsClipped() throws Exception {
        String big = "x".repeat(5000);
        Path archive = writeArchive(
                MAPPER.writeValueAsString(java.util.Map.of("archived", "tool_output", "content", big)));
        String out = new RecallTool().execute(MAPPER.createObjectNode(), ctx(archive));
        assertTrue(out.contains("共 5000 字符"));
        assertFalse(out.contains("x".repeat(2000)));
    }
}
