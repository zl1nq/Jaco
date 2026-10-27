package com.jaco.tui;

import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;

import java.nio.file.Path;

/** 自然语言多行编辑；粘贴中的换行只进入缓冲区，不触发提交。 */
final class PromptReader extends LineReaderImpl {
    PromptReader(Terminal terminal, Path history) {
        super(terminal, "jaco", new java.util.HashMap<>());
        setParser(new DefaultParser().eofOnUnclosedQuote(false).eofOnEscapedNewLine(false));
        setOpt(LineReader.Option.BRACKETED_PASTE);
        setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);
        if (history != null) setVariable(LineReader.HISTORY_FILE, history);
        setVariable(LineReader.SECONDARY_PROMPT_PATTERN, "│   ");
        getWidgets().put("jaco-newline", () -> { getBuffer().write('\n'); return true; });
        for (String name : new String[] { LineReader.EMACS, LineReader.VIINS }) {
            var keys = getKeyMaps().get(name);
            if (keys != null) keys.bind(new Reference("jaco-newline"),
                    "\033[13;2u", "\033[27;2;13~", "\033\r", "\033\n");
        }
    }

    @Override public boolean beginPaste() {
        // JLine 3.29 的默认实现把每个 CR 转成 LF，CRLF 粘贴会出现额外空行。
        getBuffer().write(normalize(doReadStringUntil(BRACKETED_PASTE_END)));
        return true;
    }

    static String normalize(String text) { return text.replace("\r\n", "\n").replace('\r', '\n'); }
}
