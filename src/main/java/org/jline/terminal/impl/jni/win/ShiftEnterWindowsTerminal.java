package org.jline.terminal.impl.jni.win;

import com.jaco.tui.WindowsKeySequences;
import org.jline.nativ.Kernel32;
import org.jline.terminal.spi.SystemStream;
import org.jline.terminal.spi.TerminalProvider;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;

/**
 * JLine 3.29 的 Windows 原生入口适配。其构造器为包可见，因此仅此桥接类放在相同包，
 * 复用原有输入泵、信号、鼠标及资源恢复，只在修饰键信息丢失前转换 Shift+Enter。
 */
public final class ShiftEnterWindowsTerminal extends NativeWinSysTerminal {
    private ShiftEnterWindowsTerminal(TerminalProvider provider, SystemStream stream, Writer writer,
                                      String type, long input, int inputMode, long output, int outputMode) throws IOException {
        super(provider, stream, writer, "jaco", type, Charset.defaultCharset(), true, SignalHandler.SIG_DFL,
                input, inputMode, output, outputMode);
    }

    public static ShiftEnterWindowsTerminal create(TerminalProvider provider, SystemStream stream) throws IOException {
        long input = Kernel32.GetStdHandle(Kernel32.STD_INPUT_HANDLE);
        long output = NativeWinSysTerminal.getConsole(stream);
        int[] inputMode = new int[1], outputMode = new int[1];
        if (Kernel32.GetConsoleMode(input, inputMode) == 0 || Kernel32.GetConsoleMode(output, outputMode) == 0)
            throw new IOException("Windows 标准输入或输出不是控制台");
        boolean vt = Kernel32.SetConsoleMode(output, outputMode[0] | ENABLE_VIRTUAL_TERMINAL_PROCESSING) != 0;
        ShiftEnterWindowsTerminal terminal = null;
        try {
            Writer writer = new NativeWinConsoleWriter(output);
            if (!vt) writer = new WindowsAnsiWriter(new BufferedWriter(writer));
            terminal = new ShiftEnterWindowsTerminal(provider, stream, writer, vt ? TYPE_WINDOWS_VTP : TYPE_WINDOWS,
                    input, inputMode[0], output, outputMode[0]);
            terminal.resume();
            return terminal;
        } catch (IOException | RuntimeException | Error e) {
            if (terminal != null) {
                try { terminal.close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            } else Kernel32.SetConsoleMode(output, outputMode[0]);
            throw e;
        }
    }

    @Override protected String getEscapeSequence(short keyCode, int modifiers) {
        String sequence = WindowsKeySequences.shiftEnter(keyCode, modifiers);
        return sequence != null ? sequence : super.getEscapeSequence(keyCode, modifiers);
    }
}
