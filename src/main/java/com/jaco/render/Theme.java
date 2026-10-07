package com.jaco.render;

/**
 * 终端调色板。所有颜色集中于此，想配置化时把它换成可绑定对象即可，零重构。
 * 自动探测 truecolor（Windows Terminal / mintty 均支持），否则退到 256 色。
 */
public final class Theme {

    private static final boolean TRUECOLOR = detectTruecolor();

    private Theme() {
    }

    private static boolean detectTruecolor() {
        String colorterm = System.getenv("COLORTERM");
        if (colorterm != null && (colorterm.contains("truecolor") || colorterm.contains("24bit"))) {
            return true;
        }
        return System.getenv("WT_SESSION") != null; // Windows Terminal
    }

    private static String rgb(int r, int g, int b, int fallback256) {
        return TRUECOLOR ? "\u001b[38;2;" + r + ";" + g + ";" + b + "m"
                : "\u001b[38;5;" + fallback256 + "m";
    }

    // ---- 代码块 ----

    /** 关键字：暖橙 */
    public static final String KEYWORD = rgb(255, 135, 90, 209);
    /** 字符串：草绿 */
    public static final String STRING = rgb(135, 215, 135, 108);
    /** 注释：中灰（斜体由使用方叠加） */
    public static final String COMMENT = rgb(130, 130, 130, 243);
    /** 数字：淡紫 */
    public static final String NUMBER = rgb(180, 150, 255, 140);
    /** 注解/装饰器：金黄 */
    public static final String ANNOTATION = rgb(230, 200, 110, 179);
    /** JSON 键：天蓝 */
    public static final String JSON_KEY = rgb(120, 190, 255, 117);
    /** 围栏行 ``` 本身：暗灰 */
    public static final String FENCE = rgb(110, 110, 110, 240);

    // ---- markdown 结构 ----

    /** 标题：亮青加粗 */
    public static final String HEADER = rgb(105, 205, 255, 81);
    /** 列表符号：品红 */
    public static final String LIST_MARK = rgb(220, 130, 230, 176);
    /** 行内代码：琥珀 */
    public static final String INLINE_CODE = rgb(255, 200, 110, 179);
    /** 引用竖线与文字：灰 */
    public static final String QUOTE = rgb(140, 140, 140, 244);
    /** 链接 url：暗灰 */
    public static final String LINK_URL = rgb(120, 120, 120, 243);

    // ---- 通用修饰 ----

    public static final String BOLD = "\u001b[1m";
    public static final String ITALIC = "\u001b[3m";
    public static final String UNDERLINE = "\u001b[4m";
    public static final String STRIKETHROUGH = "\u001b[9m";
    public static final String RESET = "\u001b[0m";
}
