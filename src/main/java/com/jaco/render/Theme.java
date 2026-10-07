package com.jaco.render;

/**
 * 调色板后端：语义色 → ANSI SGR 序列。
 * truecolor 自动探测（Windows Terminal / mintty），否则退到 256 色。
 * 颜色数据在 Color 枚举上，此处只做格式化。
 */
public final class Theme {

    private static final boolean TRUECOLOR = detectTruecolor();

    private Theme() {
    }

    public static boolean isTruecolor() {
        return TRUECOLOR;
    }

    private static boolean detectTruecolor() {
        String colorterm = System.getenv("COLORTERM");
        if (colorterm != null && (colorterm.contains("truecolor") || colorterm.contains("24bit"))) {
            return true;
        }
        return System.getenv("WT_SESSION") != null; // Windows Terminal
    }

    /** SGR 颜色参数（不含 \033[ 和 m），如 "38;5;209" 或 "38;2;255;135;90"。 */
    public static String ansiCode(Color color) {
        return TRUECOLOR ? "38;2;" + color.r() + ";" + color.g() + ";" + color.b()
                : "38;5;" + color.fallback256();
    }
}
