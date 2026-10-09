package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;
import com.jaco.tool.ToolSandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteFileToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final WriteFileTool tool = new WriteFileTool();

    @TempDir
    Path tmp;

    private ToolContext context() {
        return new ToolContext(tmp, new ToolSandbox(tmp, List.of()), "auto",
                new AtomicReference<>(), () -> false, null);
    }

    private String write(String path, String content) throws Exception {
        return tool.execute(MAPPER.createObjectNode().put("path", path).put("content", content), context());
    }

    private void assertNoTemporaryFiles(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(".jaco-write-")));
        }
    }

    @Test
    void invalidParametersNeverModifyExistingFile() throws Exception {
        Files.writeString(tmp.resolve("keep.txt"), "保留原文");
        for (String json : List.of("null", "[]", "123", "\"text\"", "{}",
                "{\"path\":\"keep.txt\"}", "{\"path\":\"keep.txt\",\"content\":null}",
                "{\"path\":\"keep.txt\",\"content\":42}", "{\"path\":\"keep.txt\",\"content\":false}",
                "{\"path\":\"keep.txt\",\"content\":[]}", "{\"path\":\"keep.txt\",\"content\":{}}",
                "{\"path\":null,\"content\":\"new\"}", "{\"path\":42,\"content\":\"new\"}",
                "{\"path\":[],\"content\":\"new\"}", "{\"path\":\"\",\"content\":\"new\"}",
                "{\"path\":\"   \",\"content\":\"new\"}",
                "{\"path\":\"keep.txt\",\"content\":\"new\",\"append\":true}")) {
            assertThrows(ToolException.class, () -> tool.execute(MAPPER.readTree(json), context()), json);
            assertEquals("保留原文", Files.readString(tmp.resolve("keep.txt")));
        }
        assertThrows(ToolException.class, () -> tool.execute(null, context()));
        assertThrows(ToolException.class, () -> write("invalid\u0000path", "new"));
        assertNoTemporaryFiles(tmp);
    }

    @Test
    void invalidParametersDoNotCreateDirectories() {
        assertThrows(ToolException.class, () -> tool.execute(
                MAPPER.createObjectNode().put("path", "a/b/c.txt"), context()));
        assertFalse(Files.exists(tmp.resolve("a")));
    }

    @Test
    void createsNestedUtf8FileAndReplacesExistingContent() throws Exception {
        String text = "中文😀\r\n第二行\n";
        assertTrue(write("a/b/code.txt", text).startsWith("已创建"));
        Path file = tmp.resolve("a/b/code.txt");
        assertEquals(text, Files.readString(file));
        assertNoTemporaryFiles(file.getParent());
        String result = write("a/b/code.txt", "短内容");
        assertTrue(result.startsWith("已覆盖"));
        assertTrue(result.contains(Files.size(file) + " 字节"));
        assertEquals("短内容", Files.readString(file));
        assertNoTemporaryFiles(file.getParent());
    }

    @Test
    void explicitEmptyContentIsAllowed() throws Exception {
        write("empty.txt", "");
        assertEquals(0, Files.size(tmp.resolve("empty.txt")));
        write("existing.txt", "old");
        write("existing.txt", "");
        assertEquals(0, Files.size(tmp.resolve("existing.txt")));
        assertNoTemporaryFiles(tmp);
    }

    @Test
    void encodingFailurePreservesOriginalAndCleansTemporaryFile() throws Exception {
        Path file = tmp.resolve("existing.txt");
        Files.writeString(file, "不能丢失的原始内容");
        // 未配对的代理字符无法编码为 UTF-8，模拟临时写入失败。
        assertThrows(IOException.class, () -> write("existing.txt", "new\uD800"));
        assertEquals("不能丢失的原始内容", Files.readString(file));
        assertNoTemporaryFiles(tmp);
        assertThrows(IOException.class, () -> write("new.txt", "new\uD800"));
        assertFalse(Files.exists(tmp.resolve("new.txt")));
        assertNoTemporaryFiles(tmp);
    }

    @Test
    void rejectsDirectoryAndOutOfBoundsPaths() throws Exception {
        Path directory = Files.createDirectory(tmp.resolve("directory"));
        assertThrows(ToolException.class, () -> write("directory", "new"));
        assertTrue(Files.isDirectory(directory));
        assertThrows(ToolException.class, () -> write("../outside.txt", "new"));
        assertNoTemporaryFiles(tmp);
    }

    @Test
    void replacementPreservesPosixPermissionsWhenSupported() throws Exception {
        Path file = tmp.resolve("script.sh");
        Files.writeString(file, "old");
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            var permissions = PosixFilePermissions.fromString("rwxr-x---");
            Files.setPosixFilePermissions(file, permissions);
            write("script.sh", "new");
            assertEquals(permissions, Files.getPosixFilePermissions(file));
        } else {
            write("script.sh", "new");
        }
        assertEquals("new", Files.readString(file));
        assertNoTemporaryFiles(tmp);
    }
}
