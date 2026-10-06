package com.jaco.tui;

/** 流式输出前的等待动画。只在首个 token 到达前显示。 */
final class Spinner {

    private static final char[] FRAMES = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏".toCharArray();
    private static final String LABEL = " thinking…";

    private final org.jline.terminal.Terminal terminal;
    private int frame;

    Spinner(org.jline.terminal.Terminal terminal) {
        this.terminal = terminal;
    }

    void tick() {
        terminal.writer().print('\r' + String.valueOf(FRAMES[frame % FRAMES.length]) + LABEL);
        terminal.writer().flush();
        frame++;
    }

    void stop() {
        terminal.writer().print("\r\033[K");
        terminal.writer().flush();
    }
}
