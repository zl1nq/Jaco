package com.jaco.tui;

import org.jline.terminal.impl.DumbTerminal;
import org.jline.terminal.Size;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class PromptReaderTest {
    private static DumbTerminal terminal(java.io.InputStream input) throws Exception {
        var terminal = new DumbTerminal("test", "xterm", input, new ByteArrayOutputStream(), StandardCharsets.UTF_8);
        terminal.setSize(new Size(80, 24));
        return terminal;
    }

    @Test void realJlinePastePreservesParagraphsAndDoesNotConsumeNextSubmission() throws Exception {
        for (String ending : List.of("\n", "\r\n", "\r")) {
            String text = "第一行😀" + ending + ending + "/help" + ending + "最后一行";
            String keys = "\033[200~" + text + "\033[201~\rNEXT\r";
            try (var terminal = terminal(new ByteArrayInputStream(keys.getBytes(StandardCharsets.UTF_8)))) {
                var reader = new PromptReader(terminal, null);
                assertEquals("第一行😀\n\n/help\n最后一行", reader.readLine("> "));
                assertEquals("NEXT", reader.readLine("> "));
            }
        }
    }

    @Test void shiftEnterAndAltEnterAddNewlineAndQuotesDoNotRequireShellContinuation() throws Exception {
        for (String newlineKey : List.of("\033[13;2u", "\033[27;2;13~", "\033\r", "\033\n")) {
            String keys = "第一行" + newlineKey + "\"未闭合的引号!\\\r";
            try (var terminal = terminal(new ByteArrayInputStream(keys.getBytes(StandardCharsets.UTF_8)))) {
                assertEquals("第一行\n\"未闭合的引号!\\", new PromptReader(terminal, null).readLine("> "));
            }
        }
    }

    @Test void pasteAloneNeverSubmitsUntilUserPressesEnter() throws Exception {
        try (var input = new PipedInputStream(); var writer = new PipedOutputStream(input); var terminal = terminal(input)) {
            var reader = new PromptReader(terminal, null);
            var result = new CompletableFuture<String>();
            Thread worker = Thread.ofVirtual().start(() -> {
                try { result.complete(reader.readLine("> ")); }
                catch (Throwable e) { result.completeExceptionally(e); }
            });
            try {
                writer.write("\033[200~第一行\n第二行\033[201~".getBytes(StandardCharsets.UTF_8)); writer.flush();
                assertThrows(TimeoutException.class, () -> result.get(150, TimeUnit.MILLISECONDS));
                writer.write('\r'); writer.flush();
                assertEquals("第一行\n第二行", result.get(3, TimeUnit.SECONDS));
            } finally { worker.interrupt(); worker.join(1000); }
        }
    }

    @Test void rawPasteNeverSubmitsAndIgnoresEmbeddedControlSequences() {
        for (String ending : List.of("\n", "\r\n", "\r")) {
            var input = new RunningCommandInput(2000, true);
            String paste = "\033[200~第一行" + ending + ending + "第二行\t内容\033[2J\033[201~";
            paste.codePoints().forEach(key -> assertNotEquals(RunningCommandInput.Action.SUBMIT, input.accept(key)));
            assertEquals("第一行\n\n第二行\t内容", input.text());
            assertEquals(RunningCommandInput.Action.SUBMIT, input.accept('\r'));
        }
    }

    @Test void rawShiftEnterInsertsNewlineWithoutSubmission() {
        for (String newlineKey : List.of("\033[13;2u", "\033[27;2;13~")) {
            var input = new RunningCommandInput(2000, true);
            "第一行".codePoints().forEach(input::accept);
            newlineKey.codePoints().forEach(key -> assertNotEquals(RunningCommandInput.Action.SUBMIT, input.accept(key)));
            "第二行".codePoints().forEach(input::accept);
            assertEquals("第一行\n第二行", input.text());
            assertEquals(RunningCommandInput.Action.SUBMIT, input.accept('\r'));
        }
    }

    @Test void rawAltEnterInsertsNewlineAndCancellationClearsPasteState() {
        var input = new RunningCommandInput(2000, true);
        "第一行\033\r第二行".codePoints().forEach(input::accept);
        assertEquals("第一行\n第二行", input.text());
        input.clear();
        "\033[200~尚未结束".codePoints().forEach(input::accept);
        assertEquals(RunningCommandInput.Action.CANCEL, input.accept(3));
        assertTrue(input.isEmpty());
        assertEquals(RunningCommandInput.Action.SUBMIT, input.accept('\r'));
    }
}
