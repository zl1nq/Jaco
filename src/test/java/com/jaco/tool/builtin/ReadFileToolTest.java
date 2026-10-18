package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;
import com.jaco.tool.ToolSandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ReadFileToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ReadFileTool tool = new ReadFileTool();
    @TempDir Path tmp;

    private ToolContext context(int budget, BooleanSupplier cancelled) {
        return new ToolContext(tmp, new ToolSandbox(tmp, List.of()), "auto", null, cancelled, null, budget);
    }
    private ObjectNode args() { return MAPPER.createObjectNode().put("path", "code.txt"); }
    private String read(ObjectNode args, int budget) throws Exception {
        return tool.execute(args, context(budget, () -> false));
    }
    private String version(String out) { return out.lines().findFirst().orElseThrow().substring("文件版本: ".length()); }
    private int cursor(String out, String field) {
        var matcher = Pattern.compile(field + "=(\\d+)").matcher(out);
        assertTrue(matcher.find(), out);
        return Integer.parseInt(matcher.group(1));
    }
    private String body(String out) {
        StringBuilder text = new StringBuilder();
        out.lines().filter(line -> line.matches(" *\\d+\\| .*"))
                .forEach(line -> text.append(line.substring(line.indexOf("| ") + 2)));
        return text.toString();
    }

    @Test void longUnicodeLinesCanBeReassembledWithinBudget() throws Exception {
        String original = "前言" + "汉😀字".repeat(1700) + "末尾";
        Files.writeString(tmp.resolve("code.txt"), original);
        ObjectNode args = args();
        StringBuilder restored = new StringBuilder();
        int previous = -1, pages = 0;
        while (true) {
            String out = read(args, 600);
            assertTrue(out.length() <= 600);
            String chunk = body(out);
            assertFalse(chunk.isEmpty());
            assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)));
            assertFalse(Character.isLowSurrogate(chunk.charAt(0)));
            restored.append(chunk);
            if (!out.contains("has_more=true")) break;
            int column = cursor(out, "next_column_offset");
            assertTrue(column > previous);
            previous = column;
            args.put("offset", cursor(out, "next_line")).put("column_offset", column)
                    .put("expected_version", version(out));
            assertTrue(++pages < 200);
        }
        assertEquals(original, restored.toString());
    }

    @Test void mixedNewlinesBlankLinesAndLineLimitHaveCorrectCursors() throws Exception {
        Files.writeString(tmp.resolve("code.txt"), "a\r\n\r\nb\rc\n");
        String first = read(args().put("limit", 2), 8000);
        assertTrue(first.contains("文件共 4 行"));
        assertTrue(first.contains("     1| a\n     2| \n"));
        assertEquals(3, cursor(first, "next_line"));
        assertEquals(0, cursor(first, "next_column_offset"));
        String second = read(args().put("offset", 3).put("limit", 2).put("expected_version", version(first)), 8000);
        assertTrue(second.contains("     3| b\n     4| c\n"));
        assertTrue(second.contains("has_more=false"));
        assertEquals(version(first), version(second));
    }

    @Test void budgetPagingAcrossManyLinesHasNoMissingOrRepeatedCharacters() throws Exception {
        StringBuilder original = new StringBuilder();
        for (int i = 0; i < 200; i++) original.append("行").append(i).append('\n');
        Files.writeString(tmp.resolve("code.txt"), original);
        ObjectNode args = args();
        StringBuilder restored = new StringBuilder();
        int pages = 0;
        while (true) {
            String out = read(args, 600);
            assertTrue(out.length() <= 600);
            restored.append(body(out));
            if (!out.contains("has_more=true")) break;
            args.put("offset", cursor(out, "next_line")).put("column_offset", cursor(out, "next_column_offset"))
                    .put("expected_version", version(out));
            assertTrue(++pages < 200);
        }
        assertEquals(original.toString().replace("\n", ""), restored.toString());
    }

    @Test void columnOnlyAffectsFirstLineAndRejectsInvalidBoundaries() throws Exception {
        Files.writeString(tmp.resolve("code.txt"), "a😀b\nnext");
        assertTrue(read(args().put("column_offset", 3), 8000).contains("     1| b\n     2| next\n"));
        assertThrows(ToolException.class, () -> read(args().put("column_offset", 2), 8000));
        assertThrows(ToolException.class, () -> read(args().put("column_offset", 5), 8000));
        assertTrue(read(args().put("column_offset", 4).put("limit", 1), 8000).contains("     1| \n"));
    }

    @Test void bomIsHiddenButIncludedInOriginalByteVersion() throws Exception {
        byte[] original = "\uFEFF中文\r\n末行".getBytes(StandardCharsets.UTF_8);
        Files.write(tmp.resolve("code.txt"), original);
        String out = read(args(), 8000);
        assertFalse(out.contains("\uFEFF"));
        assertEquals(FileVersion.of(original), version(out));
        assertTrue(out.contains("文件共 2 行"));
    }

    @Test void emptyFilesAndTrailingDelimitersFollowLineSemantics() throws Exception {
        for (String content : List.of("", "\uFEFF", "\n", "\n\n", "a", "a\n")) {
            Files.writeString(tmp.resolve("code.txt"), content);
            String out = read(args(), 8000);
            assertTrue(out.contains("文件共 " + content.replace("\uFEFF", "").lines().count() + " 行"), out);
            assertTrue(out.contains("has_more=false"));
        }
        Files.writeString(tmp.resolve("code.txt"), "");
        assertThrows(ToolException.class, () -> read(args().put("offset", 2), 8000));
        assertThrows(ToolException.class, () -> read(args().put("column_offset", 1), 8000));
    }

    @Test void changedContentOutsideRequestedPageRejectsExpectedVersion() throws Exception {
        Path file = tmp.resolve("code.txt");
        Files.writeString(file, "first\nlast");
        String old = version(read(args().put("limit", 1), 8000));
        var timestamp = Files.getLastModifiedTime(file);
        Files.writeString(file, "first\nLAST");
        Files.setLastModifiedTime(file, timestamp);
        assertTrue(assertThrows(ToolException.class,
                () -> read(args().put("limit", 1).put("expected_version", old), 8000))
                .getMessage().contains("版本不匹配"));
    }

    @Test void metadataChangeDuringScanIsRejected() throws Exception {
        Path file = tmp.resolve("code.txt");
        Files.writeString(file, "x".repeat(20000));
        AtomicInteger checks = new AtomicInteger();
        ToolException e = assertThrows(ToolException.class, () -> tool.execute(args(), context(8000, () -> {
            if (checks.incrementAndGet() == 100) {
                try { Files.setLastModifiedTime(file, FileTime.fromMillis(1000)); }
                catch (Exception ex) { throw new AssertionError(ex); }
            }
            return false;
        })));
        assertTrue(e.getMessage().contains("读取期间发生变化"));
    }

    @Test void rejectsInvalidUtf8AndBinaryEvenOutsideReturnedPage() throws Exception {
        Path file = tmp.resolve("code.txt");
        Files.write(file, new byte[] {'o', 'k', '\n', (byte) 0xC3, 0x28});
        assertTrue(assertThrows(ToolException.class, () -> read(args().put("limit", 1), 8000))
                .getMessage().contains("UTF-8"));
        Files.write(file, new byte[] {'o', 'k', '\n', 0});
        assertTrue(assertThrows(ToolException.class, () -> read(args().put("limit", 1), 8000))
                .getMessage().contains("二进制"));
    }

    @Test void rejectsOversizedFileWithoutReadingIt() throws Exception {
        try (var file = new RandomAccessFile(tmp.resolve("code.txt").toFile(), "rw")) {
            file.setLength(ReadFileTool.MAX_FILE_BYTES + 1);
        }
        assertTrue(assertThrows(ToolException.class, () -> read(args(), 8000)).getMessage().contains("64 MiB"));
    }

    @Test void largeFileSmallPageStillHashesAndValidatesEntireStream() throws Exception {
        byte[] chunk = ("源码😀\n".repeat(1000)).getBytes(StandardCharsets.UTF_8);
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var output = Files.newOutputStream(tmp.resolve("code.txt"))) {
            for (int i = 0; i < 500; i++) {
                output.write(chunk);
                digest.update(chunk);
            }
        }
        String out = read(args().put("limit", 1), 600);
        assertEquals("sha256:" + java.util.HexFormat.of().formatHex(digest.digest()), version(out));
        assertTrue(out.contains("文件共 500000 行"));
        assertTrue(out.contains("     1| 源码😀\n"));
        assertEquals(2, cursor(out, "next_line"));
        assertTrue(out.length() <= 600);
    }

    @Test void cancellationBeforeAndDuringScanAndThreadInterruption() throws Exception {
        Files.writeString(tmp.resolve("code.txt"), "x".repeat(20000));
        assertThrows(ToolException.class, () -> tool.execute(args(), context(8000, () -> true)));
        AtomicInteger checks = new AtomicInteger();
        assertThrows(ToolException.class, () -> tool.execute(args(), context(8000, () -> checks.incrementAndGet() > 100)));
        Thread.currentThread().interrupt();
        try { assertThrows(ToolException.class, () -> read(args(), 8000)); }
        finally { Thread.interrupted(); }
    }

    @Test void validatesArgumentsPathsRangesAndBudget() throws Exception {
        Files.writeString(tmp.resolve("code.txt"), "one");
        for (String json : List.of("null", "[]", "{}", "{\"path\":false}", "{\"path\":\" \"}",
                "{\"path\":\"code.txt\",\"offset\":0}", "{\"path\":\"code.txt\",\"offset\":1.5}",
                "{\"path\":\"code.txt\",\"offset\":2147483648}", "{\"path\":\"code.txt\",\"limit\":20001}",
                "{\"path\":\"code.txt\",\"limit\":\"2\"}", "{\"path\":\"code.txt\",\"column_offset\":-1}",
                "{\"path\":\"code.txt\",\"expected_version\":null}", "{\"path\":\"code.txt\",\"expected_version\":\"bad\"}",
                "{\"path\":\"code.txt\",\"extra\":1}")) {
            assertThrows(ToolException.class, () -> tool.execute(MAPPER.readTree(json), context(8000, () -> false)), json);
        }
        assertThrows(ToolException.class, () -> read(args().put("path", "missing"), 8000));
        assertThrows(ToolException.class, () -> read(args().put("path", "."), 8000));
        assertThrows(ToolException.class, () -> read(args().put("path", "../outside.txt"), 8000));
        assertThrows(ToolException.class, () -> read(args().put("offset", 2), 8000));
        assertThrows(ToolException.class, () -> read(args(), 100));
    }
}
