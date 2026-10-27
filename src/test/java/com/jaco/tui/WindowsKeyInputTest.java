package com.jaco.tui;

import org.jline.terminal.Cursor;
import org.jline.terminal.Size;
import org.jline.terminal.impl.AbstractWindowsTerminal;
import org.jline.terminal.spi.SystemStream;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.*;

class WindowsKeyInputTest {
    /** 在独立隐藏控制台中执行的烟雾检查，不读取用户实际键盘，不启动模型。 */
    public static void main(String[] args) throws Exception {
        var report = java.nio.file.Path.of(args[0]);
        try {
            String backend;
            String result;
            try (var terminal = InteractiveTerminal.open()) {
                backend = terminal.getClass().getName();
                if (!(terminal instanceof org.jline.terminal.impl.jni.win.ShiftEnterWindowsTerminal))
                    throw new IllegalStateException("未选择原生适配器: " + backend);
                terminal.enterRawMode();
                var method = AbstractWindowsTerminal.class.getDeclaredMethod("processKeyEvent", boolean.class, short.class, char.class, int.class);
                method.setAccessible(true);
                method.invoke(terminal, true, (short) 'A', 'a', 0);
                method.invoke(terminal, true, (short) 13, '\r', 0x0010);
                method.invoke(terminal, true, (short) 'B', 'b', 0);
                method.invoke(terminal, true, (short) 13, '\r', 0);
                result = new PromptReader(terminal, null).readLine("> ");
                if (!result.equals("a\nb")) throw new IllegalStateException("按键未保留为多行: " + result);
            }
            java.nio.file.Files.writeString(report, "PASS: " + backend + "; native Shift+Enter -> multiline prompt; terminal closed");
        } catch (Throwable e) {
            java.nio.file.Files.writeString(report, "FAIL: " + e);
            System.exit(1);
        }
    }

    /** 真正调用 JLine 的 Win32 KEY_EVENT 处理路径，控制台句柄和模式操作用内存实现。 */
    private static final class WindowsInput extends AbstractWindowsTerminal<Integer> {
        private final boolean patched;
        WindowsInput(boolean patched) throws IOException {
            super(null, SystemStream.Output, new StringWriter(), "test", TYPE_WINDOWS_VTP,
                    StandardCharsets.UTF_8, false, SignalHandler.SIG_DFL, 0, 0, 1, 0);
            this.patched = patched;
            enterRawMode();
        }
        void key(boolean down, int code, char text, int state) throws IOException { processKeyEvent(down, (short) code, text, state); }
        void text(String text) throws IOException {
            for (char c : text.toCharArray()) key(true, Character.toUpperCase(c), c, 0);
        }
        @Override protected String getEscapeSequence(short code, int modifiers) {
            String shifted = patched ? WindowsKeySequences.shiftEnter(code, modifiers) : null;
            return shifted != null ? shifted : super.getEscapeSequence(code, modifiers);
        }
        @Override protected int getConsoleMode(Integer console) { return 0; }
        @Override protected void setConsoleMode(Integer console, int mode) {}
        @Override protected boolean processConsoleInput() { return false; }
        @Override public Size getSize() { return new Size(80, 24); }
        @Override public Cursor getCursorPosition(IntConsumer discarded) { return null; }
    }

    @Test void reproducesOriginalJlineLossOfShiftModifier() throws Exception {
        try (var terminal = new WindowsInput(false)) {
            terminal.key(true, 13, '\r', 0x0010);
            assertEquals('\r', terminal.reader().read(100));
            assertEquals(NonBlockingReader.READ_EXPIRED, terminal.reader().read(1));
        }
    }

    @Test void rawNativeShiftEnterProducesDistinctSequenceAndKeyUpIsIgnored() throws Exception {
        try (var terminal = new WindowsInput(true)) {
            terminal.key(false, 13, '\r', 0x0010);
            assertEquals(NonBlockingReader.READ_EXPIRED, terminal.reader().read(1));
            terminal.key(true, 13, '\r', 0x0010 | 0x0080); // Shift + Caps Lock
            StringBuilder keys = new StringBuilder();
            for (int i = 0; i < 7; i++) keys.append((char) terminal.reader().read(100));
            assertEquals("\033[13;2u", keys.toString());
            var input = new RunningCommandInput(2000, true);
            "first".chars().forEach(input::accept);
            keys.codePoints().forEach(key -> assertNotEquals(RunningCommandInput.Action.SUBMIT, input.accept(key)));
            assertEquals("first\n", input.text());
            terminal.key(true, 13, '\r', 0);
            assertEquals(RunningCommandInput.Action.SUBMIT, input.accept(terminal.reader().read(100)));
        }
    }

    @Test void realWindowsEventsReachJlineEditorAsOneMultilinePrompt() throws Exception {
        try (var terminal = new WindowsInput(true)) {
            terminal.text("第一行");
            terminal.key(true, 13, '\r', 0x0010);
            terminal.text("第二行");
            terminal.key(true, 13, '\r', 0);
            assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertEquals("第一行\n第二行", new PromptReader(terminal, null).readLine("> ")));
        }
    }

    @Test void onlyShiftEnterChangesWhileOtherModifiersAndKeysKeepExistingBehavior() {
        assertEquals("\033[13;2u", WindowsKeySequences.shiftEnter((short) 13, 1));
        for (int modifiers : new int[] {0, 2, 3, 4, 5, 6, 7}) assertNull(WindowsKeySequences.shiftEnter((short) 13, modifiers));
        assertNull(WindowsKeySequences.shiftEnter((short) 'A', 1));
    }
}
