package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaco.tool.PreparedToolCall;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;
import com.jaco.tool.ToolSandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class EditFileBatchTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final EditFileTool tool = new EditFileTool();
    @TempDir Path tmp;

    private Path file() { return tmp.resolve("code.txt"); }
    private ToolContext context(BooleanSupplier cancelled) {
        return new ToolContext(tmp, new ToolSandbox(tmp, List.of()), "auto", null, cancelled, null, 8000);
    }
    private ObjectNode args(String original) throws Exception {
        Files.writeString(file(), original);
        return MAPPER.createObjectNode().put("path", "code.txt")
                .put("expected_version", FileVersion.of(Files.readAllBytes(file())));
    }
    private ObjectNode single(String original, String old, String updated) throws Exception {
        return args(original).put("old_text", old).put("new_text", updated);
    }
    private PreparedToolCall prepare(ObjectNode args) throws Exception { return tool.prepare(args, context(() -> false)); }
    private void edit(ObjectNode args) throws Exception { prepare(args).execute(); }
    private void noTemps() throws Exception {
        try (var files = Files.list(tmp)) { assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(".jaco-write-"))); }
    }

    @Test void preservesCrLfCrAndLfIncludingBomAndUnicode() throws Exception {
        for (String newline : List.of("\r\n", "\r", "\n")) {
            String original = "\uFEFF前😀" + newline + "old" + newline + "  value" + newline + "end";
            ObjectNode args = single(original, "old\n  value", "new\n  more\r\n  value").put("newline_mode", "preserve");
            edit(args);
            assertEquals("\uFEFF前😀" + newline + "new" + newline + "  more" + newline + "  value" + newline + "end", Files.readString(file()));
        }
    }

    @Test void normalizedBatchOffsetsReferToOriginalEvenAfterInsertedLines() throws Exception {
        ObjectNode args = args("zero\r\na\r\nb\r\nc\r\nlast").put("newline_mode", "preserve");
        var edits = args.putArray("edits");
        edits.addObject().put("old_text", "c\nlast").put("new_text", "tail");
        edits.addObject().put("old_text", "a\nb").put("new_text", "A\nB\nextra");
        edit(args);
        assertEquals("zero\r\nA\r\nB\r\nextra\r\ntail", Files.readString(file()));
    }

    @Test void exactIsDefaultAndOffersNewlineHintWithoutWriting() throws Exception {
        ObjectNode args = single("a\r\nb\r\n", "a\nb", "new");
        ToolException error = assertThrows(ToolException.class, () -> prepare(args));
        assertTrue(error.getMessage().contains("CRLF"));
        assertTrue(error.getMessage().contains("newline_mode=preserve"));
        assertEquals("a\r\nb\r\n", Files.readString(file()));
        edit(args.put("old_text", "a\r\nb").put("new_text", "A\nB"));
        assertEquals("A\nB\r\n", Files.readString(file()));
    }

    @Test void preserveRejectsMixedEndingsAndNormalizationAmbiguity() throws Exception {
        ObjectNode mixed = single("a\r\nb\nc", "a", "A").put("newline_mode", "preserve");
        assertTrue(assertThrows(ToolException.class, () -> prepare(mixed)).getMessage().contains("混合换行"));
        ObjectNode ambiguous = single("a\r\nb\r\na\r\nb", "a\nb", "new").put("newline_mode", "preserve");
        assertTrue(assertThrows(ToolException.class, () -> prepare(ambiguous)).getMessage().contains("多处匹配"));
    }

    @Test void batchIsBasedOnOriginalNotPreviouslyReplacedText() throws Exception {
        ObjectNode args = args("first second");
        var edits = args.putArray("edits");
        edits.addObject().put("old_text", "first").put("new_text", "second");
        edits.addObject().put("old_text", "second").put("new_text", "last");
        var plan = prepare(args);
        assertEquals("first second", Files.readString(file()));
        String result = plan.execute();
        assertEquals("second last", Files.readString(file()));
        assertTrue(result.contains(FileVersion.of(Files.readAllBytes(file()))));
        assertTrue(plan.preview().contains("first second"));
        assertTrue(plan.preview().contains("second last"));
        noTemps();
    }

    @Test void overlapDuplicateAndMissingItemsRejectEntireBatch() throws Exception {
        for (String old : List.of("bc", "abcd", "missing")) {
            ObjectNode args = args("abcdef");
            var edits = args.putArray("edits");
            edits.addObject().put("old_text", "abcd").put("new_text", "A");
            edits.addObject().put("old_text", old).put("new_text", "B");
            assertThrows(ToolException.class, () -> prepare(args));
            assertEquals("abcdef", Files.readString(file()));
            noTemps();
        }
    }

    @Test void adjacentEditsAndDeletionWork() throws Exception {
        ObjectNode args = args("abcdef");
        var edits = args.putArray("edits");
        edits.addObject().put("old_text", "abc").put("new_text", "");
        edits.addObject().put("old_text", "def").put("new_text", "END");
        edit(args);
        assertEquals("END", Files.readString(file()));
    }

    @Test void duplicateDiagnosticsAreBoundedAndGiveLineNumbers() throws Exception {
        ObjectNode args = single("x\r\n".repeat(20), "x", "new");
        String error = assertThrows(ToolException.class, () -> prepare(args)).getMessage();
        assertTrue(error.contains("修改项 1"));
        assertTrue(error.contains("[1, 2, 3, 4, 5, 6, 7, 8]"));
        assertTrue(error.contains("前 8"));
        assertTrue(error.length() < 300);
    }

    @Test void invalidBatchModeAndItemsDoNotWrite() throws Exception {
        for (String fragment : List.of("\"edits\":[]", "\"edits\":null", "\"edits\":{}", "\"edits\":[null]",
                "\"edits\":[{\"old_text\":\"a\"}]", "\"edits\":[{\"old_text\":\"a\",\"new_text\":\"A\",\"extra\":1}]",
                "\"edits\":[{\"old_text\":\"\",\"new_text\":\"A\"}]", "\"edits\":[],\"old_text\":\"a\",\"new_text\":\"A\"",
                "\"old_text\":\"a\",\"new_text\":\"A\",\"newline_mode\":\"guess\"")) {
            ObjectNode args = args("abc");
            args.setAll((ObjectNode) MAPPER.readTree("{" + fragment + "}"));
            assertThrows(ToolException.class, () -> prepare(args));
            assertEquals("abc", Files.readString(file()));
        }
        ObjectNode tooMany = args("abc");
        for (int i = 0; i <= EditFileTool.MAX_EDITS; i++) tooMany.withArray("edits").addObject().put("old_text", "a").put("new_text", "b");
        assertThrows(ToolException.class, () -> prepare(tooMany));
    }

    @Test void limitsInputTextAndAggregateUtf8Bytes() throws Exception {
        ObjectNode large = single("old", "old", "字".repeat(EditFileTool.MAX_TEXT_BYTES / 3 + 1));
        assertTrue(assertThrows(ToolException.class, () -> prepare(large)).getMessage().contains("1 MiB"));
        ObjectNode total = args("abc");
        for (int i = 0; i < 5; i++) total.withArray("edits").addObject().put("old_text", "a")
                .put("new_text", "x".repeat(EditFileTool.MAX_TEXT_BYTES));
        assertTrue(assertThrows(ToolException.class, () -> prepare(total)).getMessage().contains("4 MiB"));
        assertEquals("abc", Files.readString(file()));
    }

    @Test void limitsOriginalAndFinalFileSizes() throws Exception {
        ObjectNode args = single("old", "old", "new");
        try (var oversized = new RandomAccessFile(file().toFile(), "rw")) { oversized.setLength(EditFileTool.MAX_FILE_BYTES + 1L); }
        assertTrue(assertThrows(ToolException.class, () -> prepare(args)).getMessage().contains("16 MiB"));
        String nearLimit = "old" + "x".repeat(EditFileTool.MAX_FILE_BYTES - 3);
        ObjectNode grows = single(nearLimit, "old", "longer");
        assertTrue(assertThrows(ToolException.class, () -> prepare(grows)).getMessage().contains("修改后的文件超过"));
        assertEquals(EditFileTool.MAX_FILE_BYTES, Files.size(file()));
        noTemps();
    }

    @Test void rejectsBinaryAndInvalidUtf8WithClearErrors() throws Exception {
        ObjectNode args = single("old\0", "old", "new");
        assertTrue(assertThrows(ToolException.class, () -> prepare(args)).getMessage().contains("二进制"));
        ObjectNode nul = single("old", "old", "new\0");
        assertTrue(assertThrows(ToolException.class, () -> prepare(nul)).getMessage().contains("NUL"));
        Files.write(file(), new byte[] {(byte) 0xFF});
        ObjectNode invalid = MAPPER.createObjectNode().put("path", "code.txt").put("old_text", "old").put("new_text", "new")
                .put("expected_version", FileVersion.of(Files.readAllBytes(file())));
        assertTrue(assertThrows(ToolException.class, () -> prepare(invalid)).getMessage().contains("UTF-8"));
    }

    @Test void cancellationBeforeDuringPrepareAndAfterPreviewLeavesOriginal() throws Exception {
        ObjectNode args = single("old\n" + "x".repeat(20000), "old", "new");
        AtomicBoolean cancel = new AtomicBoolean(true);
        assertThrows(ToolException.class, () -> tool.prepare(args, context(cancel::get)));
        AtomicInteger calls = new AtomicInteger();
        assertThrows(ToolException.class, () -> tool.prepare(args, context(() -> calls.incrementAndGet() > 3)));
        cancel.set(false);
        var plan = tool.prepare(args, context(cancel::get));
        cancel.set(true);
        assertThrows(ToolException.class, plan::execute);
        assertTrue(Files.readString(file()).startsWith("old\n"));
        noTemps();
    }

    @Test void changedVersionAndChangesAfterPreviewRejectWholeBatch() throws Exception {
        ObjectNode args = args("a b");
        args.putArray("edits").addObject().put("old_text", "a").put("new_text", "A");
        var plan = prepare(args);
        Files.writeString(file(), "user changed");
        assertThrows(ToolException.class, plan::execute);
        assertThrows(ToolException.class, () -> prepare(args));
        assertEquals("user changed", Files.readString(file()));
        noTemps();
    }

    @Test void previewUsesGlobalLineNumbersAfterEarlierInsertedLines() throws Exception {
        ObjectNode args = args("head\na\n" + "context\n".repeat(10) + "b\ntail\n");
        var edits = args.putArray("edits");
        edits.addObject().put("old_text", "a\n").put("new_text", "A\nextra\n");
        edits.addObject().put("old_text", "b\n").put("new_text", "B\n");
        String preview = prepare(args).preview();
        assertTrue(preview.contains("- 13:- | b"), preview);
        assertTrue(preview.contains("+ -:14 | B"), preview);
        assertTrue(preview.contains("14:15 | tail"), preview);
    }

    @Test void longLinePreviewIsBoundedAndStillShowsReplacement() throws Exception {
        ObjectNode args = single("😀".repeat(50000) + " old\n", "old", "new");
        String preview = prepare(args).preview();
        assertTrue(preview.length() < 8000);
        assertTrue(preview.contains("预览已截短"));
        assertTrue(preview.contains("- 1:- |"));
        assertTrue(preview.contains("+ -:1 |"));
        assertTrue(preview.contains(" old"), preview);
        assertTrue(preview.contains(" new"), preview);
        edit(args);
        assertTrue(Files.readString(file()).endsWith(" new\n"));
    }

    @Test void cancelledTemporaryWriteCleansUpAndNeverReplacesTarget() throws Exception {
        Files.writeString(file(), "old");
        byte[] expected = Files.readAllBytes(file());
        AtomicBoolean partial = new AtomicBoolean();
        assertThrows(ToolException.class, () -> AtomicFileWriter.write(file(), "x".repeat(50000), expected, () -> {
            try (var files = Files.list(tmp)) {
                var temporary = files.filter(p -> p.getFileName().toString().startsWith(".jaco-write-")).findFirst();
                if (temporary.isPresent() && Files.size(temporary.get()) > 0) {
                    partial.set(true);
                    throw new ToolException("取消");
                }
            } catch (java.io.IOException e) { throw new AssertionError(e); }
        }));
        assertTrue(partial.get());
        assertEquals("old", Files.readString(file()));
        noTemps();
    }

    @Test void unchangedBatchDoesNotRewriteAndNoNewlineFileDefaultsToLf() throws Exception {
        ObjectNode args = single("old", "old", "old").put("newline_mode", "preserve");
        var time = Files.getLastModifiedTime(file());
        assertTrue(prepare(args).execute().startsWith("无需修改"));
        assertEquals(time, Files.getLastModifiedTime(file()));
        edit(args.put("new_text", "new\r\nline"));
        assertEquals("new\nline", Files.readString(file()));
    }

    @Test void previewEscapesControlsAndBoundsManyChanges() throws Exception {
        ObjectNode args = args("a\n".repeat(200));
        args.put("old_text", "a\n".repeat(200)).put("new_text", "\u001b[2J\n".repeat(200));
        var plan = prepare(args);
        assertTrue(plan.preview().length() < 8000);
        assertFalse(plan.preview().contains("\u001b"));
        assertTrue(plan.preview().contains("预览已截短"));
        plan.execute();
        assertEquals("\u001b[2J\n".repeat(200), Files.readString(file()));
        String control = prepare(single("old", "old", "\u001b[2J")).preview();
        assertFalse(control.contains("\u001b"));
        assertTrue(control.contains("\\u001b"));
    }

    @Test void previewIncludesJoinedFollowingLineAfterNewlineDeletion() throws Exception {
        var plan = prepare(single("head\na\nb\ntail\n", "a\n", "A"));
        assertTrue(plan.preview().contains("- 2:- | a"), plan.preview());
        assertTrue(plan.preview().contains("- 3:- | b"), plan.preview());
        assertTrue(plan.preview().contains("+ -:2 | Ab"), plan.preview());
        assertTrue(plan.preview().contains("4:3 | tail"), plan.preview());
        assertFalse(plan.preview().contains("[无末尾换行]"), plan.preview());
        plan.execute();
        assertEquals("head\nAb\ntail\n", Files.readString(file()));
    }
}
