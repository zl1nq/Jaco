package com.jaco.tui;

/** raw mode 下的有界行输入；控制序列不进入命令，也不作为下一轮用户输入。 */
final class RunningCommandInput {
    enum Action { NONE, EDITED, SUBMIT, CANCEL }
    private final StringBuilder text = new StringBuilder();
    private boolean escape;
    private boolean sequence;

    Action accept(int key) {
        if (key == 3) { clear(); return Action.CANCEL; }
        if (key < 0) return Action.NONE;
        if (escape) {
            if (!sequence && (key == '[' || key == 'O')) { sequence = true; return Action.NONE; }
            if (!sequence || key >= 0x40 && key <= 0x7e) { escape = false; sequence = false; }
            return Action.NONE;
        }
        if (key == 27) { escape = true; return Action.NONE; }
        if (key == '\r' || key == '\n') return text.isEmpty() ? Action.NONE : Action.SUBMIT;
        if (key == 21) { clear(); return Action.EDITED; } // Ctrl+U
        if (key == 8 || key == 127) {
            if (!text.isEmpty()) text.setLength(text.offsetByCodePoints(text.length(), -1));
            return Action.EDITED;
        }
        if (!Character.isISOControl(key) && text.length() + Character.charCount(key) <= 512) {
            text.appendCodePoint(key);
            return Action.EDITED;
        }
        return Action.NONE;
    }

    String text() { return text.toString(); }
    boolean isEmpty() { return text.isEmpty(); }
    void clear() { text.setLength(0); escape = false; sequence = false; }
}
