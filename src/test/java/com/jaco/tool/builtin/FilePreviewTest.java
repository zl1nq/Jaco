package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;
import com.jaco.tool.ToolSandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FilePreviewTest {
    @TempDir
    Path tmp;

    private ToolContext context() {
        return new ToolContext(tmp, new ToolSandbox(tmp, List.of()), "auto", null, () -> false, null);
    }

    @Test
    void newFilePreviewDoesNotCreateDirectoriesUntilExecuted() throws Exception {
        var args = new ObjectMapper().createObjectNode().put("path", "a/b/new.txt").put("content", "first\nsecond\n");
        var plan = new WriteFileTool().prepare(args, context());
        assertFalse(Files.exists(tmp.resolve("a")));
        assertTrue(plan.preview().contains("[新建]"));
        assertTrue(plan.preview().contains("新增 2 行，删除 0 行"));
        assertTrue(plan.preview().contains("+ -:1 | first"));
        plan.execute();
        assertEquals("first\nsecond\n", Files.readString(tmp.resolve("a/b/new.txt")));
    }

    @Test
    void localPreviewShowsContextAndLineNumbersWithoutWriting() throws Exception {
        Files.writeString(tmp.resolve("code.txt"), "first\nold\nlast\n");
        var plan = new EditFileTool().prepare(new ObjectMapper().createObjectNode()
                .put("path", "code.txt").put("old_text", "old").put("new_text", "new")
                .put("expected_version", FileVersion.of(Files.readAllBytes(tmp.resolve("code.txt")))), context());
        assertTrue(plan.preview().contains("新增 1 行，删除 1 行"));
        assertTrue(plan.preview().contains("- 2:- | old"));
        assertTrue(plan.preview().contains("+ -:2 | new"));
        assertTrue(plan.preview().contains("1:1 | first"));
        assertTrue(plan.preview().contains("3:3 | last"));
        assertEquals("first\nold\nlast\n", Files.readString(tmp.resolve("code.txt")));
    }

    @Test
    void changedOrDeletedOriginalRejectsPreparedWriteAndEdit() throws Exception {
        Path file = tmp.resolve("code.txt");
        for (boolean edit : List.of(false, true)) {
            Files.writeString(file, "old");
            var args = new ObjectMapper().createObjectNode().put("path", "code.txt");
            args.put("expected_version", FileVersion.of(Files.readAllBytes(file)));
            var plan = edit ? new EditFileTool().prepare(args.put("old_text", "old").put("new_text", "new"), context())
                    : new WriteFileTool().prepare(args.put("content", "new"), context());
            Files.writeString(file, "user changed");
            assertThrows(ToolException.class, plan::execute);
            assertEquals("user changed", Files.readString(file));
            Files.delete(file);
            assertThrows(ToolException.class, plan::execute);
            assertFalse(Files.exists(file));
        }
    }

    @Test
    void newFileAppearingAfterPreviewIsNotOverwritten() throws Exception {
        var plan = new WriteFileTool().prepare(new ObjectMapper().createObjectNode()
                .put("path", "new.txt").put("content", "planned"), context());
        Files.writeString(tmp.resolve("new.txt"), "created by user");
        assertThrows(ToolException.class, plan::execute);
        assertEquals("created by user", Files.readString(tmp.resolve("new.txt")));
    }

    @Test
    void deletionAndLineEndingChangesAreVisible() {
        String deletion = FileDiff.render("file.txt", false, "a\nb\n", "");
        assertTrue(deletion.contains("新增 0 行，删除 2 行"));
        assertTrue(deletion.contains("- 1:- | a"));
        String ending = FileDiff.render("file.txt", false, "a\r\n", "a\n");
        assertTrue(ending.contains("CRLF → LF"));
        assertTrue(FileDiff.render("file.txt", false, "a\n", "a").contains("[无末尾换行]"));
        assertTrue(FileDiff.render("file.txt", false, "same", "same").contains("内容无变化"));
    }

    @Test
    void previewsBoundLargeChangesAndEscapeTerminalControlSequences() {
        String preview = FileDiff.render("file\u001b[31m.txt", false,
                "old\n".repeat(600), "new\u001b[2J\n".repeat(600));
        assertTrue(preview.contains("整段删除/新增对比"));
        assertTrue(preview.contains("新增 600 行，删除 600 行"));
        assertTrue(preview.contains("预览已截短"));
        assertTrue(preview.length() < 8000);
        assertFalse(preview.contains("\u001b"));
        String longLine = FileDiff.render("file.txt", true, "", "😀".repeat(500));
        assertTrue(longLine.contains("预览已截短"));
        assertFalse(longLine.contains("😀".repeat(300)));
    }

    @Test
    void separatedChangesRetainBothHunksAndOmitUnrelatedMiddle() {
        String before = "a\nold1\n" + "middle\n".repeat(20) + "old2\nz\n";
        String after = before.replace("old1", "new1").replace("old2", "new2");
        String preview = FileDiff.render("file.txt", false, before, after);
        assertTrue(preview.contains("新增 2 行，删除 2 行"));
        assertTrue(preview.contains("old1"));
        assertTrue(preview.contains("new2"));
        assertTrue(preview.contains("..."));
    }
}
