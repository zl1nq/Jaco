package com.jaco.render;

/**
 * 终端显示宽度：CJK/全角/多数 emoji 按 2 列，其余按 1 列。
 * Java 标准库没有显示宽度概念，表格对齐必须自带这份计算。
 */
public final class DisplayWidth {

    private DisplayWidth() {
    }

    public static int width(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            w += isWide(cp) ? 2 : 1;
        }
        return w;
    }

    private static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)      // Hangul Jamo
                || (cp >= 0x2E80 && cp <= 0x303E)   // CJK 部首/符号
                || (cp >= 0x3041 && cp <= 0x33FF)   // 假名/注音/兼容符号
                || (cp >= 0x3400 && cp <= 0x4DBF)   // CJK 扩展 A
                || (cp >= 0x4E00 && cp <= 0x9FFF)   // CJK 基本区
                || (cp >= 0xA000 && cp <= 0xA4CF)   // 彝文
                || (cp >= 0xAC00 && cp <= 0xD7A3)   // Hangul 音节
                || (cp >= 0xF900 && cp <= 0xFAFF)   // CJK 兼容表意
                || (cp >= 0xFE30 && cp <= 0xFE4F)   // CJK 兼容形式
                || (cp >= 0xFF00 && cp <= 0xFF60)   // 全角形式
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x1F300 && cp <= 0x1FAFF); // emoji（近似，多数终端按 2 列）
    }
}
