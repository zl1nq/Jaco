package com.jaco.tui;

/** JLine 的 modifier 位为 Shift=1、Alt=2、Ctrl=4；不改变其他 Enter 组合。 */
public final class WindowsKeySequences {
    private WindowsKeySequences() {}
    public static String shiftEnter(short keyCode, int modifiers) {
        return keyCode == 0x0D && modifiers == 1 ? "\033[13;2u" : null;
    }
}
