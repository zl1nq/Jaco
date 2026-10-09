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

    @Test
    void searchShowsLateMatchAndUsesArchiveIdRatherThanFilteredOffset() throws Exception {
        Path archive = writeArchive(
                MAPPER.writeValueAsString(java.util.Map.of("content", "unrelated")),
                MAPPER.writeValueAsString(java.util.Map.of("content", "x".repeat(5000) + "LATE_MATCH\nlast line")));
        RecallTool tool = new RecallTool();
        String search = tool.execute(MAPPER.createObjectNode().put("query", "LATE_MATCH"), ctx(archive));
        assertTrue(search.contains("LATE_MATCH"));
        assertTrue(search.contains("[#1]"));
        assertTrue(search.contains("entry_id=1"));
        assertTrue(search.contains("content_offset=4880"));

        String read = tool.execute(MAPPER.createObjectNode().put("entry_id", 1)
                .put("content_offset", 5000), ctx(archive));
        assertTrue(read.contains("LATE_MATCH\nlast line"));
    }

    @Test
    void contentPagesReconstructOriginalIncludingNewlinesAndUnicode() throws Exception {
        String original = "中文\nemoji 😀\r\n".repeat(600) + "\nEND\n";
        Path archive = writeArchive(MAPPER.writeValueAsString(java.util.Map.of("content", original)));
        RecallTool tool = new RecallTool();
        StringBuilder restored = new StringBuilder();
        int offset = 0;
        int pageSize = 137;
        while (offset < original.length()) {
            String out = tool.execute(MAPPER.createObjectNode().put("entry_id", 0)
                    .put("content_offset", offset).put("content_limit", pageSize), ctx(archive));
            var range = java.util.regex.Pattern.compile("本次读取 \\[\\d+, (\\d+)\\)").matcher(out);
            assertTrue(range.find());
            int end = Integer.parseInt(range.group(1));
            assertTrue(end > offset && end - offset <= pageSize);
            assertTrue(out.contains("本次读取 [" + offset + ", " + end + ")"));
            restored.append(out.substring(out.indexOf('\n') + 1, out.lastIndexOf('\n')));
            String page = out.substring(out.indexOf('\n') + 1, out.lastIndexOf('\n'));
            assertFalse(Character.isLowSurrogate(page.charAt(0)));
            assertFalse(Character.isHighSurrogate(page.charAt(page.length() - 1)));
            if (end < original.length()) {
                assertTrue(out.contains("下一页：entry_id=0, content_offset=" + end));
            } else {
                assertTrue(out.endsWith("已到条目末尾"));
            }
            offset = end;
        }
        assertEquals(original, restored.toString());
    }

    @Test
    void contentReadHandlesMissingEmptyAndEndOffsets() throws Exception {
        Path archive = writeArchive("{\"content\":\"abc\"}", "{\"content\":\"\"}");
        RecallTool tool = new RecallTool();
        assertTrue(tool.execute(MAPPER.createObjectNode().put("entry_id", 2), ctx(archive))
                .contains("entry_id 2 超出范围"));
        assertTrue(tool.execute(MAPPER.createObjectNode().put("entry_id", 0)
                .put("content_offset", 4), ctx(archive)).contains("content_offset 4 超出范围"));
        assertTrue(tool.execute(MAPPER.createObjectNode().put("entry_id", 0)
                .put("content_offset", 3), ctx(archive)).contains("本次读取 [3, 3)\n\n已到条目末尾"));
        assertTrue(tool.execute(MAPPER.createObjectNode().put("entry_id", 1), ctx(archive))
                .contains("本次读取 [0, 0)\n\n已到条目末尾"));
    }

    @Test
    void rejectsInvalidReadParametersAndMixedModes() throws Exception {
        Path archive = writeArchive("{\"content\":\"abc\"}");
        RecallTool tool = new RecallTool();
        for (String json : List.of(
                "{\"entry_id\":-1}", "{\"entry_id\":1.5}", "{\"entry_id\":2147483648}",
                "{\"entry_id\":\"0\"}", "{\"entry_id\":0,\"content_offset\":-1}",
                "{\"entry_id\":0,\"content_limit\":0}", "{\"entry_id\":0,\"content_limit\":-1}",
                "{\"entry_id\":0,\"query\":\"abc\"}", "{\"entry_id\":0,\"offset\":1}",
                "{\"entry_id\":0,\"limit\":1}", "{\"content_offset\":1200}")) {
            assertThrows(com.jaco.tool.ToolException.class,
                    () -> tool.execute(MAPPER.readTree(json), ctx(archive)), json);
        }
    }

    @Test
    void unicodeBoundariesAreNotSplit() throws Exception {
        Path archive = writeArchive(MAPPER.writeValueAsString(java.util.Map.of("content", "a😀b")));
        RecallTool tool = new RecallTool();
        String page = tool.execute(MAPPER.createObjectNode().put("entry_id", 0)
                .put("content_limit", 2), ctx(archive));
        assertTrue(page.contains("本次读取 [0, 1)\na\n"));
        assertTrue(page.contains("content_offset=1"));
        assertThrows(com.jaco.tool.ToolException.class, () -> tool.execute(
                MAPPER.createObjectNode().put("entry_id", 0).put("content_offset", 2), ctx(archive)));
        assertThrows(com.jaco.tool.ToolException.class, () -> tool.execute(
                MAPPER.createObjectNode().put("entry_id", 0).put("content_offset", 1)
                        .put("content_limit", 1), ctx(archive)));
    }

    @Test
    void outputBudgetCapsContentAndSearchWithoutLosingNextPage() throws Exception {
        String text = "x".repeat(5000);
        Path archive = writeArchive(MAPPER.writeValueAsString(java.util.Map.of("content", text)),
                MAPPER.writeValueAsString(java.util.Map.of("content", text)));
        ToolContext smallBudget = new ToolContext(tmp, null, "auto", null, () -> false, archive, 1000);
        RecallTool tool = new RecallTool();
        String read = tool.execute(MAPPER.createObjectNode().put("entry_id", 0)
                .put("content_limit", Integer.MAX_VALUE), smallBudget);
        assertTrue(read.length() <= 1000);
        assertTrue(read.contains("本次读取 [0, 600)"));
        assertTrue(read.contains("content_offset=600"));
        String search = tool.execute(MAPPER.createObjectNode(), smallBudget);
        assertTrue(search.length() <= 1000);
        assertTrue(search.contains("[#0]"));
        assertFalse(search.contains("[#1]"));
        assertTrue(search.contains("用 offset=1 翻页"));
        String next = tool.execute(MAPPER.createObjectNode().put("offset", 1), smallBudget);
        assertTrue(next.contains("[#1]"));
        assertTrue(next.length() <= 1000);
    }

    @Test
    void entryIdsRemainStableAfterAppendAndSkipMalformedLines() throws Exception {
        Path archive = writeArchive("bad json", "", "null", "{\"content\":\"first\"}",
                "{\"content\":\"second\"}");
        RecallTool tool = new RecallTool();
        assertTrue(tool.execute(MAPPER.createObjectNode().put("entry_id", 1), ctx(archive)).contains("\nsecond\n"));
        Files.writeString(archive, "{\"content\":\"third\"}\n", java.nio.file.StandardOpenOption.APPEND);
        assertTrue(tool.execute(MAPPER.createObjectNode().put("entry_id", 1), ctx(archive)).contains("\nsecond\n"));
        assertTrue(tool.execute(MAPPER.createObjectNode().put("entry_id", 2), ctx(archive)).contains("\nthird\n"));
    }
}
