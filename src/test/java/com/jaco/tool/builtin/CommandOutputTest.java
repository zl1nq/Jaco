package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaco.session.Session;
import com.jaco.session.SessionStore;
import com.jaco.tool.ToolException;
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
    void pagesUnicodeAndArchivesSmallBackgroundLogs() throws Exception {
        Path archive = tmp.resolve("small.jsonl");
        CommandOutput output = new CommandOutput(archive, true);
        output.append("😀AB".toCharArray(), 4);
        CommandOutput.Page first = output.page(0, 2);
        assertEquals("😀", first.text());
        assertEquals(2, first.nextOffset());
        assertTrue(first.hasMore());
        assertEquals("AB", output.page(first.nextOffset(), 2).text());
        assertThrows(ToolException.class, () -> output.page(1, 2));
        assertThrows(ToolException.class, () -> output.page(0, 1));
        output.finish();
        assertEquals(1, Files.readAllLines(archive).size());
        assertTrue(output.metadata().contains("archive_id:"));
    }

    @Test
    void reportsEvictedLogRangeAndRejectsFutureOffsets() {
        CommandOutput output = new CommandOutput(null);
        for (int i = 0; i < 6; i++) output.append("x".repeat(10000).toCharArray(), 10000);
        CommandOutput.Page page = output.page(0, 20);
        assertEquals(10000, page.availableFrom());
        assertTrue(page.truncated());
        assertEquals(10020, page.nextOffset());
        assertEquals("x".repeat(20), page.text());
        assertFalse(output.page(10000, 20).truncated());
        assertThrows(ToolException.class, () -> output.page(60001, 20));
    }

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
