package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;
import com.jaco.tool.ToolSandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileVersionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir
    Path tmp;

    private ToolContext context() {
        return new ToolContext(tmp, new ToolSandbox(tmp, List.of()), "auto", null, () -> false, null);
    }

    private String readVersion(ObjectNode args) throws Exception {
        return versionIn(new ReadFileTool().execute(args, context()));
    }

    private String versionIn(String result) {
        int start = result.indexOf("文件版本: ") + "文件版本: ".length();
        return result.substring(start).split("\n", 2)[0];
    }

    private ObjectNode writeArgs(String version) {
        return MAPPER.createObjectNode().put("path", "code.txt").put("content", "new")
                .put("expected_version", version);
    }

    private ObjectNode editArgs(String version) {
        return MAPPER.createObjectNode().put("path", "code.txt").put("old_text", "old")
                .put("new_text", "new").put("expected_version", version);
    }

    @Test
    void paginatedReadsHaveSameWholeFileVersionAndDetectChangesOutsidePage() throws Exception {
        Path file = tmp.resolve("code.txt");
        Files.writeString(file, "old\r\nsecond\nthird\n");
        ObjectNode args = MAPPER.createObjectNode().put("path", "code.txt").put("limit", 1);
        String first = readVersion(args);
        assertTrue(first.matches("sha256:[0-9a-f]{64}"));
        assertEquals(FileVersion.of(Files.readAllBytes(file)), first);
        assertEquals(first, readVersion(args.put("offset", 2)));
        Files.writeString(file, "old\r\nsecond\nTHIRD\n");
        assertNotEquals(first, readVersion(args.put("offset", 1)));
        assertThrows(ToolException.class, () -> new EditFileTool().prepare(editArgs(first), context()));
    }

    @Test
    void sameSizeAndTimestampChangeRejectsOldVersionForBothTools() throws Exception {
        Path file = tmp.resolve("code.txt");
        Files.writeString(file, "old\nA");
        var timestamp = Files.getLastModifiedTime(file);
        String version = readVersion(MAPPER.createObjectNode().put("path", "code.txt"));
        Files.writeString(file, "old\nB");
        Files.setLastModifiedTime(file, timestamp);
        assertThrows(ToolException.class, () -> new WriteFileTool().prepare(writeArgs(version), context()));
        assertThrows(ToolException.class, () -> new EditFileTool().prepare(editArgs(version), context()));
        assertEquals("old\nB", Files.readString(file));
        String fresh = readVersion(MAPPER.createObjectNode().put("path", "code.txt"));
        new EditFileTool().execute(editArgs(fresh), context());
        assertEquals("new\nB", Files.readString(file));
    }

    @Test
    void missingAndMalformedVersionsCannotOverwriteExistingFile() throws Exception {
        Files.writeString(tmp.resolve("code.txt"), "old");
        for (ObjectNode args : List.of(writeArgs(null), editArgs(null))) {
            args.remove("expected_version");
            assertThrows(ToolException.class, () -> (args.has("content") ? new WriteFileTool() : new EditFileTool())
                    .prepare(args, context()));
            for (String value : List.of("", "wrong", "sha256:123")) {
                args.put("expected_version", value);
                assertThrows(ToolException.class, () -> (args.has("content") ? new WriteFileTool() : new EditFileTool())
                        .prepare(args, context()));
            }
            args.put("expected_version", 42);
            assertThrows(ToolException.class, () -> (args.has("content") ? new WriteFileTool() : new EditFileTool())
                    .prepare(args, context()));
            args.putNull("expected_version");
            assertThrows(ToolException.class, () -> (args.has("content") ? new WriteFileTool() : new EditFileTool())
                    .prepare(args, context()));
        }
        assertEquals("old", Files.readString(tmp.resolve("code.txt")));
    }

    @Test
    void emptyFileCanBeReadAndVersionedBeforeOverwrite() throws Exception {
        Files.writeString(tmp.resolve("code.txt"), "");
        String version = readVersion(MAPPER.createObjectNode().put("path", "code.txt"));
        assertEquals(FileVersion.of(new byte[0]), version);
        new WriteFileTool().execute(writeArgs(version), context());
        assertEquals("new", Files.readString(tmp.resolve("code.txt")));
    }

    @Test
    void successfulWriteReturnsVersionForNextEditAndRejectsPreviousVersion() throws Exception {
        Files.writeString(tmp.resolve("code.txt"), "old");
        String original = readVersion(MAPPER.createObjectNode().put("path", "code.txt"));
        String result = new WriteFileTool().execute(writeArgs(original), context());
        String current = versionIn(result);
        assertEquals(readVersion(MAPPER.createObjectNode().put("path", "code.txt")), current);
        assertThrows(ToolException.class, () -> new WriteFileTool().execute(writeArgs(original), context()));
        new EditFileTool().execute(editArgs(current).put("old_text", "new").put("new_text", "next"), context());
        assertEquals("next", Files.readString(tmp.resolve("code.txt")));
    }

    @Test
    void newFileNeedsNoVersionButStaleVersionCannotRecreateDeletedFile() throws Exception {
        ObjectNode args = writeArgs(null);
        args.remove("expected_version");
        new WriteFileTool().execute(args, context());
        String version = readVersion(MAPPER.createObjectNode().put("path", "code.txt"));
        Files.delete(tmp.resolve("code.txt"));
        assertThrows(ToolException.class, () -> new WriteFileTool().prepare(writeArgs(version), context()));
        assertFalse(Files.exists(tmp.resolve("code.txt")));
    }
}
