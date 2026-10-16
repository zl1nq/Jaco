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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GrepToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    private Path write(String name, String text) throws Exception {
        Path file = tmp.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }

    private ToolContext ctx(BooleanSupplier cancelled) {
        return new ToolContext(tmp, new ToolSandbox(tmp, List.of()), "auto", null, cancelled, null);
    }

    private String grep(ObjectNode args) throws Exception {
        return new GrepTool().execute(args, ctx(() -> false));
    }

    private ObjectNode args(String pattern) {
        return MAPPER.createObjectNode().put("pattern", pattern);
    }

    @Test
    void searchesRegexAndSingleFileHasFilename() throws Exception {
        write("a.txt", "zero\nhello 123\nhello abc\n");
        assertEquals("a.txt:2: hello 123", grep(args("hello \\d+").put("path", "a.txt")));
    }

    @Test
    void literalAndUnicodeCaseInsensitiveSearch() throws Exception {
        write("a.txt", "FOO.BAR()\nfooXbar()\nÄPFEL\n");
        assertEquals("a.txt:1: FOO.BAR()", grep(args("foo.bar()").put("literal", true).put("ignore_case", true)));
        assertEquals("a.txt:3: ÄPFEL", grep(args("äpfel").put("ignore_case", true)));
        assertEquals("(no matches)", grep(args("foo.bar()").put("literal", true)));
    }

    @Test
    void includesRootAndNestedFilesWithLeadingDoubleStar() throws Exception {
        write("Root.java", "needle");
        write("src/Nested.java", "needle");
        write("a.txt", "needle");
        String out = grep(args("needle").put("include", "**/*.java"));
        assertTrue(out.contains("Root.java:1:"));
        assertTrue(out.contains("src/Nested.java:1:"));
        assertFalse(out.contains("a.txt"));
        assertEquals("src/Nested.java:1: needle", grep(args("needle").put("include", "src/*.java")));
    }

    @Test
    void prunesDefaultsAndExplicitExclusions() throws Exception {
        write("src/a.java", "needle");
        write("target/b.java", "needle");
        write(".git/c.java", "needle");
        write("generated/d.java", "needle");
        write("src/a.min.js", "needle");
        ObjectNode params = args("needle");
        params.putArray("exclude").add("generated").add("*.min.js");
        assertEquals("src/a.java:1: needle", grep(params));
        String out = grep(params.put("include_ignored", true));
        assertTrue(out.contains("target/b.java"));
        assertTrue(out.contains(".git/c.java"));
        assertFalse(out.contains("generated"));
        assertFalse(out.contains("a.min.js"));
        assertEquals("b.java:1: needle", grep(args("needle").put("path", "target")));
    }

    @Test
    void mergesOverlappingContextWithoutDuplicatingLines() throws Exception {
        write("a.txt", "before\nhit\nbetween\nhit\nafter\nunused\n");
        assertEquals("a.txt-1- before\na.txt:2: hit\na.txt-3- between\na.txt:4: hit\na.txt-5- after",
                grep(args("hit").put("context_lines", 1)));
    }

    @Test
    void limitCountsHitsAndStillReturnsTrailingContext() throws Exception {
        write("a.txt", "before\nhit\nafter\nhit\n");
        String out = grep(args("hit").put("context_lines", 1).put("max_results", 1));
        assertTrue(out.startsWith("a.txt-1- before\na.txt:2: hit\na.txt-3- after\n"));
        assertTrue(out.contains("搜索提前停止"));
        assertFalse(out.contains(":4:"));
    }

    @Test
    void stopsStreamingAtLimitAndChecksCancellationDuringReading() throws Exception {
        write("large.txt", "hit\n".repeat(100_000));
        AtomicInteger checks = new AtomicInteger();
        ToolContext context = ctx(() -> checks.incrementAndGet() > 30);
        assertTimeout(Duration.ofSeconds(5), () -> {
            String out = new GrepTool().execute(args("hit").put("max_results", 1), context);
            assertTrue(out.contains("搜索提前停止"));
        });
        checks.set(0);
        ToolException e = assertThrows(ToolException.class,
                () -> new GrepTool().execute(args("absent"), context));
        assertTrue(e.getMessage().contains("取消"));
    }

    @Test
    void longLineShowsMatchInsteadOfOnlyLineBeginning() throws Exception {
        write("a.txt", "a".repeat(500) + "NEEDLE" + "z".repeat(500));
        String out = grep(args("NEEDLE"));
        assertTrue(out.contains("NEEDLE"));
        assertTrue(out.contains("命中列 501"));
        assertTrue(out.length() < 300);
    }

    @Test
    void skipsBinaryAndReportsInvalidUtf8() throws Exception {
        Files.write(tmp.resolve("binary.dat"), new byte[]{0, 65, 66});
        Files.write(tmp.resolve("invalid.txt"), new byte[]{(byte) 0xc3, 0x28});
        String out = grep(args("."));
        assertTrue(out.startsWith("(no matches)"));
        assertTrue(out.contains("搜索不完整：跳过 1 项"));
        assertTrue(out.contains("invalid.txt"));
        assertFalse(out.contains("binary.dat"));
    }

    @Test
    void rejectsInvalidArgumentsPatternsAndMissingPaths() {
        for (String json : List.of("{}", "[]", "{\"pattern\":\"[\"}",
                "{\"pattern\":false}", "{\"pattern\":\"x\",\"ignore_case\":\"true\"}",
                "{\"pattern\":\"x\",\"max_results\":0}", "{\"pattern\":\"x\",\"context_lines\":11}",
                "{\"pattern\":\"x\",\"exclude\":\"target\"}", "{\"pattern\":\"x\",\"include\":\"[\"}",
                "{\"pattern\":\"x\",\"path\":\"missing\"}", "{\"pattern\":\"x\",\"unknown\":1}")) {
            assertThrows(ToolException.class, () -> new GrepTool().execute(MAPPER.readTree(json), ctx(() -> false)), json);
        }
    }

    @Test
    void rejectsOutsideSearchRoot() {
        assertThrows(ToolException.class, () -> grep(args("x").put("path", "..")));
    }

    @Test
    void doesNotFollowExternalFileOrDirectorySymlinks() throws Exception {
        Path workspace = Files.createDirectory(tmp.resolve("workspace"));
        Path outside = Files.createDirectory(tmp.resolve("outside"));
        Path secret = Files.writeString(outside.resolve("secret.txt"), "SECRET");
        try {
            Files.createSymbolicLink(workspace.resolve("link.txt"), secret);
            Files.createSymbolicLink(workspace.resolve("dir"), outside);
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "当前环境无法创建符号链接: " + e.getMessage());
        }
        ToolContext context = new ToolContext(workspace, new ToolSandbox(workspace, List.of()),
                "auto", null, () -> false, null);
        String out = new GrepTool().execute(args("SECRET"), context);
        assertTrue(out.startsWith("(no matches)"));
        assertTrue(out.contains("符号链接"));
        assertFalse(out.contains("SECRET"));
    }
}
