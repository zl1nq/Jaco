package com.jaco.tui;

/** raw mode 下的有界输入；整段粘贴及 Alt+Enter 换行不会自动提交。 */
final class RunningCommandInput {
    enum Action { NONE, EDITED, SUBMIT, CANCEL }
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder escape = new StringBuilder();
    private boolean pasting, previousCr;
    private boolean overflowed;
    private final int maxLength;
    private final boolean submitEmpty;

    RunningCommandInput() { this(512, false); }
    RunningCommandInput(int maxLength, boolean submitEmpty) { this.maxLength = maxLength; this.submitEmpty = submitEmpty; }

    Action accept(int key) {
        if (key == 3) { clear(); return Action.CANCEL; }
        if (key < 0) return Action.NONE;
        if (!escape.isEmpty()) {
            if (escape.length() == 1 && (key == '\r' || key == '\n')) {
                escape.setLength(0);
                return submitEmpty && !pasting ? append('\n') : Action.NONE;
            }
            escape.appendCodePoint(key);
            if (escape.length() == 2 && (key == '[' || key == 'O')) return Action.NONE;
            if (escape.length() < 32 && escape.charAt(1) == '[' && (key < 0x40 || key > 0x7e)) return Action.NONE;
            String sequence = escape.toString();
            escape.setLength(0);
            if (sequence.equals("\033[200~")) { pasting = true; previousCr = false; }
            else if (sequence.equals("\033[201~")) { pasting = false; previousCr = false; return Action.EDITED; }
            else if (sequence.equals("\033[13;2u") && submitEmpty && !pasting) return append('\n');
            return Action.NONE;
        }
        if (key == 27) { escape.append((char) key); return Action.NONE; }
        if (pasting) {
            if (key == '\n' && previousCr) { previousCr = false; return Action.NONE; }
            previousCr = key == '\r';
            if (key == '\r' || key == '\n') return append('\n');
            if (key == '\t' || !Character.isISOControl(key)) return append(key);
            return Action.NONE;
        }
        if (key == '\r' || key == '\n') return text.isEmpty() && !submitEmpty ? Action.NONE : Action.SUBMIT;
        if (key == 21) { clear(); return Action.EDITED; } // Ctrl+U
        if (key == 8 || key == 127) {
            if (!text.isEmpty()) text.setLength(text.offsetByCodePoints(text.length(), -1));
            overflowed = false;
            return Action.EDITED;
        }
        if (!Character.isISOControl(key)) return append(key);
        return Action.NONE;
    }

    private Action append(int key) {
        if (text.length() + Character.charCount(key) <= maxLength) {
            text.appendCodePoint(key);
            return Action.EDITED;
        }
        overflowed = true;
        return Action.NONE;
    }

    String text() { return text.toString(); }
    boolean isEmpty() { return text.isEmpty(); }
    boolean overflowed() { return overflowed; }
    void clear() { text.setLength(0); escape.setLength(0); pasting = false; previousCr = false; overflowed = false; }
}
