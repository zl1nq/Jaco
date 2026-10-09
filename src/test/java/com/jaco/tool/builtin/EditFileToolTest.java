package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;
import com.jaco.tool.ToolRegistry;
import com.jaco.tool.ToolSandbox;
import com.jaco.llm.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditFileToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final EditFileTool tool = new EditFileTool();

    @TempDir
    Path tmp;

    private ToolContext context() {
        return new ToolContext(tmp, new ToolSandbox(tmp, List.of()), "auto",
                new AtomicReference<>(), () -> false, null);
    }

    private String edit(String path, String oldText, String newText) throws Exception {
        return tool.execute(MAPPER.createObjectNode().put("path", path)
                .put("old_text", oldText).put("new_text", newText), context());
    }

    private void assertNoTemporaryFiles() throws IOException {
        try (var files = Files.list(tmp)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(".jaco-write-")));
        }
    }

    @Test
    void uniqueMultilineReplacementPreservesOtherContentAndLineEndings() throws Exception {
        Path file = tmp.resolve("code.txt");
        String prefix = "\uFEFF开头😀\r\n";
        String suffix = "\r\n结尾\n";
        Files.writeString(file, prefix + "old\r\n  block" + suffix);
        assertTrue(edit("code.txt", "old\r\n  block", "new\r\n  block").startsWith("已修改"));
        assertEquals(prefix + "new\r\n  block" + suffix, Files.readString(file));
        assertNoTemporaryFiles();
    }

    @Test
    void absentDuplicateAndOverlappingMatchesNeverModifyFile() throws Exception {
        Path file = tmp.resolve("code.txt");
        for (String text : List.of("one old two old", "aaa")) {
            Files.writeString(file, text);
            assertThrows(ToolException.class, () -> edit("code.txt", "missing", "new"));
            String pattern = text.equals("aaa") ? "aa" : "old";
            assertThrows(ToolException.class, () -> edit("code.txt", pattern, "new"));
            assertEquals(text, Files.readString(file));
        }
        assertNoTemporaryFiles();
    }

    @Test
    void supportsLiteralCharactersDeletionAndWhitespaceOnlyMatch() throws Exception {
        Path file = tmp.resolve("code.txt");
        Files.writeString(file, "start[$.*]end");
        edit("code.txt", "[$.*]", "");
        assertEquals("startend", Files.readString(file));
        Files.writeString(file, "start\tend");
        edit("code.txt", "\t", " ");
        assertEquals("start end", Files.readString(file));
    }

    @Test
    void invalidArgumentsNeverModifyFileOrCreateDirectories() throws Exception {
        Path file = tmp.resolve("code.txt");
        Files.writeString(file, "old");
        for (String json : List.of("null", "[]", "{}",
                "{\"path\":\"code.txt\",\"old_text\":\"old\"}",
                "{\"path\":\"code.txt\",\"new_text\":\"new\"}",
                "{\"path\":\"code.txt\",\"old_text\":\"\",\"new_text\":\"new\"}",
                "{\"path\":\"code.txt\",\"old_text\":1,\"new_text\":\"new\"}",
                "{\"path\":\"code.txt\",\"old_text\":\"old\",\"new_text\":null}",
                "{\"path\":\"code.txt\",\"old_text\":\"old\",\"new_text\":false}",
                "{\"path\":\"code.txt\",\"old_text\":\"old\",\"new_text\":[]}",
                "{\"path\":42,\"old_text\":\"old\",\"new_text\":\"new\"}",
                "{\"path\":\"code.txt\",\"old_text\":\"old\",\"new_text\":\"new\",\"replace_all\":true}")) {
            assertThrows(ToolException.class, () -> tool.execute(MAPPER.readTree(json), context()), json);
            assertEquals("old", Files.readString(file));
        }
        assertThrows(ToolException.class, () -> tool.execute(null, context()));
        assertThrows(ToolException.class, () -> edit("   ", "old", "new"));
        assertThrows(ToolException.class, () -> edit("invalid\u0000path", "old", "new"));
        assertThrows(ToolException.class, () -> edit("a/b/missing.txt", "old", "new"));
        assertFalse(Files.exists(tmp.resolve("a")));
        assertNoTemporaryFiles();
    }

    @Test
    void rejectsDirectoryAndOutOfBoundsPath() throws Exception {
        Files.createDirectory(tmp.resolve("directory"));
        assertThrows(ToolException.class, () -> edit("directory", "old", "new"));
        assertThrows(ToolException.class, () -> edit("../outside.txt", "old", "new"));
        assertNoTemporaryFiles();
    }

    @Test
    void unchangedReplacementDoesNotRewriteFile() throws Exception {
        Path file = tmp.resolve("code.txt");
        Files.writeString(file, "unique");
        var time = Files.getLastModifiedTime(file);
        assertTrue(edit("code.txt", "unique", "unique").startsWith("无需修改"));
        assertEquals(time, Files.getLastModifiedTime(file));
        assertEquals("unique", Files.readString(file));
        assertNoTemporaryFiles();
    }

    @Test
    void failedEncodingPreservesFileAndRemovesTemporaryFile() throws Exception {
        Path file = tmp.resolve("code.txt");
        Files.writeString(file, "prefix old suffix");
        assertThrows(IOException.class, () -> edit("code.txt", "old", "\uD800"));
        assertEquals("prefix old suffix", Files.readString(file));
        assertNoTemporaryFiles();
    }

    @Test
    void registryAdvertisesAndDispatchesEditTool() throws Exception {
        Files.writeString(tmp.resolve("code.txt"), "old");
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        assertEquals("edit_file", registry.definitions().get(0).function().name());
        var args = MAPPER.createObjectNode().put("path", "code.txt").put("old_text", "old").put("new_text", "new");
        registry.execute(new ToolCall("c1", "function", new ToolCall.FunctionCall("edit_file", args.toString())), context());
        assertEquals("new", Files.readString(tmp.resolve("code.txt")));
    }
}
