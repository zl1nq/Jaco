package com.jaco.tui;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.spi.SystemStream;
import org.jline.terminal.spi.TerminalProvider;
import org.jline.terminal.impl.jni.win.ShiftEnterWindowsTerminal;

import java.io.IOException;

final class InteractiveTerminal {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InteractiveTerminal.class);
    private InteractiveTerminal() {}

    static Terminal open() throws IOException {
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            try {
                var provider = TerminalProvider.load("jni");
                if (provider.isSystemStream(SystemStream.Input)) {
                    if (provider.isSystemStream(SystemStream.Output)) return ShiftEnterWindowsTerminal.create(provider, SystemStream.Output);
                    if (provider.isSystemStream(SystemStream.Error)) return ShiftEnterWindowsTerminal.create(provider, SystemStream.Error);
                }
            } catch (IOException | RuntimeException | LinkageError e) {
                log.warn("Windows 原生按键终端不可用，使用默认终端: {}", e.getMessage());
            }
        }
        return TerminalBuilder.builder().system(true).build();
    }
}
