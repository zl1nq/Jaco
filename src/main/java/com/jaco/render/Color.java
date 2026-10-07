package com.jaco.render;

/**
 * 语义色：渲染器只表达"这是什么角色"，具体颜色由后端映射
 * （REPL 用 ANSI 转义，GUI 用 Lanterna TextColor）。
 * 每个语义色携带 RGB 值与 256 色回退索引，两种后端共用同一份颜色数据。
 */
public enum Color {
    KEYWORD(255, 135, 90, 209),      // 暖橙
    STRING(135, 215, 135, 108),      // 草绿
    COMMENT(130, 130, 130, 243),     // 中灰
    NUMBER(180, 150, 255, 140),      // 淡紫
    ANNOTATION(230, 200, 110, 179),  // 金黄
    JSON_KEY(120, 190, 255, 117),    // 天蓝
    FENCE(110, 110, 110, 240),       // 暗灰
    HEADER(105, 205, 255, 81),       // 亮青
    LIST_MARK(220, 130, 230, 176),   // 品红
    INLINE_CODE(255, 200, 110, 179), // 琥珀
    QUOTE(140, 140, 140, 244),       // 灰
    LINK_URL(120, 120, 120, 243),    // 暗灰
    ACCENT(105, 205, 255, 81),       // 界面强调（标签/标题）
    WARNING(250, 200, 95, 214),      // 界面警示（工具行/确认框）
    ERROR(255, 105, 97, 203),        // 错误
    SUCCESS(130, 220, 130, 114);     // 成功

    private final int r;
    private final int g;
    private final int b;
    private final int fallback256;

    Color(int r, int g, int b, int fallback256) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.fallback256 = fallback256;
    }

    public int r() {
        return r;
    }

    public int g() {
        return g;
    }

    public int b() {
        return b;
    }

    public int fallback256() {
        return fallback256;
    }
}
