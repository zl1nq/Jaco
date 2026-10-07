package com.jaco.render;

/**
 * 渲染器的输出单位：一段带样式的文本。与终端实现无关——
 * ANSI 后端转成转义序列，Lanterna 后端映射为 TextColor + Style。
 * color 为 null 表示继承默认前景色。
 */
public record Span(String text, Color color, boolean bold, boolean italic, boolean underline, boolean strike) {

    public static Span plain(String text) {
        return new Span(text, null, false, false, false, false);
    }

    public static Span of(String text, Color color) {
        return new Span(text, color, false, false, false, false);
    }

    public Span withBold() {
        return new Span(text, color, true, italic, underline, strike);
    }

    public Span withItalic() {
        return new Span(text, color, bold, true, underline, strike);
    }

    public Span withStrike() {
        return new Span(text, color, bold, italic, underline, true);
    }
}
