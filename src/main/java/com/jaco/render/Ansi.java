package com.jaco.render;

import java.util.List;

/** ANSI 后端：把 Span 序列转成终端转义字符串（REPL 模式专用）。 */
public final class Ansi {

    private Ansi() {
    }

    public static String render(List<Span> spans) {
        StringBuilder sb = new StringBuilder();
        for (Span span : spans) {
            if (span.text().isEmpty()) {
                continue;
            }
            String sgr = sgr(span);
            if (!sgr.isEmpty()) {
                sb.append("\033[").append(sgr).append("m").append(span.text()).append("\033[0m");
            } else {
                sb.append(span.text());
            }
        }
        return sb.toString();
    }

    private static String sgr(Span span) {
        StringBuilder sgr = new StringBuilder();
        if (span.color() != null) {
            sgr.append(Theme.ansiCode(span.color()));
        }
        if (span.bold()) {
            if (sgr.length() > 0) {
                sgr.append(';');
            }
            sgr.append("1");
        }
        if (span.italic()) {
            if (sgr.length() > 0) {
                sgr.append(';');
            }
            sgr.append("3");
        }
        if (span.underline()) {
            if (sgr.length() > 0) {
                sgr.append(';');
            }
            sgr.append("4");
        }
        if (span.strike()) {
            if (sgr.length() > 0) {
                sgr.append(';');
            }
            sgr.append("9");
        }
        return sgr.toString();
    }
}
