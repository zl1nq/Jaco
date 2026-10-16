package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaco.session.Session;
import com.jaco.session.SessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CommandOutputTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    @Test
    void preservesUnicodeAcrossReadAndArchiveChunkBoundaries() throws Exception {
        Path archive = tmp.resolve("archive.jsonl");
        CommandOutput output = new CommandOutput(archive);
        String text = "😀".repeat(26_000) + "END";
        for (int offset = 0; offset < text.length(); offset += 4095) {
            char[] chars = text.substring(offset, Math.min(text.length(), offset + 4095)).toCharArray();
            output.append(chars, chars.length);
        }
        output.finish();
        StringBuilder restored = new StringBuilder();
        for (String line : Files.readAllLines(archive)) {
            String content = MAPPER.readTree(line).path("content").asText();
            restored.append(content.substring(content.indexOf('\n') + 1));
        }
        assertEquals(text, restored.toString());
        assertTrue(output.render().contains("archive_truncated: false"));
        assertTrue(output.render().endsWith("END"));
        assertFalse(output.render().contains("�"));
        assertFalse(output.append("late".toCharArray(), 4));
        assertTrue(output.render().endsWith("END"));
    }

    @Test
    void commandAndSessionRecordsDoNotInterleave() throws Exception {
        SessionStore store = new SessionStore(tmp);
        Session session = Session.create("concurrent", 1);
        Path archive = store.archivePath(session);
        CommandOutput output = new CommandOutput(archive);
        CompletableFuture<Void> commands = CompletableFuture.runAsync(() -> {
            char[] block = "command\n".repeat(3000).toCharArray();
            for (int i = 0; i < 20; i++) {
                output.append(block, block.length);
            }
            output.finish();
        });
        CompletableFuture<Void> messages = CompletableFuture.runAsync(() -> {
            for (int i = 0; i < 20; i++) {
                store.appendArchivedOutput(session, "session-" + i + "x".repeat(20_000));
            }
        });
        CompletableFuture.allOf(commands, messages).get(10, TimeUnit.SECONDS);
        int sessionCount = 0;
        int commandChars = 0;
        for (String line : Files.readAllLines(archive)) {
            String content = MAPPER.readTree(line).path("content").asText();
            if (content.startsWith("session-")) {
                sessionCount++;
            } else {
                assertTrue(content.startsWith("[command-"));
                commandChars += content.length() - content.indexOf('\n') - 1;
            }
        }
        assertEquals(20, sessionCount);
        assertEquals(480_000, commandChars);
    }
}
