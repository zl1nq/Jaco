package com.jaco.render;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流式 markdown 渲染器（按行状态机，Q2 决策）：
 * 只对完整行做渲染，跨行结构（嵌套列表、表格）降级为原文——
 * 这是"流式体验优先"的取舍。每个 turn 新建一个实例（代码块状态不跨轮）。
 */
public final class MarkdownRenderer {

    private static final Pattern HR = Pattern.compile("\\s*(---+|===+|\\*\\*\\*+|___+)\\s*");
    private static final Pattern LIST_ITEM = Pattern.compile("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)");
    private static final Pattern LINK = Pattern.compile("\\[([^]\\n]+)]\\(([^)\\n]+)\\)");

    private boolean inCodeBlock;
    private CodeHighlighter highlighter = new CodeHighlighter("");

    /** 渲染一行完整文本（不含行尾换行），返回带 ANSI 样式的行。 */
    public String renderLine(String line) {
        if (inCodeBlock) {
            if (isFence(line)) {
                inCodeBlock = false;
                highlighter = new CodeHighlighter("");
                return Theme.FENCE + line.strip() + Theme.RESET;
            }
            return highlighter.highlight(line);
        }
        if (isFence(line)) {
            inCodeBlock = true;
            highlighter = new CodeHighlighter(fenceLang(line));
            return Theme.FENCE + line.strip() + Theme.RESET;
        }
        if (HR.matcher(line).matches()) {
            return Theme.FENCE + "─".repeat(40) + Theme.RESET;
        }
        String stripped = stripLeading(line);
        if (!stripped.isEmpty() && stripped.charAt(0) == '#') {
            int level = 0;
            while (level < stripped.length() && stripped.charAt(level) == '#') {
                level++;
            }
            if (level <= 6 && level < stripped.length() && stripped.charAt(level) == ' ') {
                String text = stripped.substring(level).strip();
                return Theme.BOLD + Theme.HEADER + text + Theme.RESET;
            }
            return renderInline(line);
        }
        if (!stripped.isEmpty() && stripped.charAt(0) == '>') {
            String text = stripped.substring(1).strip();
            return Theme.QUOTE + "▏ " + Theme.ITALIC + text + Theme.RESET;
        }
        Matcher list = LIST_ITEM.matcher(line);
        if (list.matches()) {
            return list.group(1) + Theme.LIST_MARK + list.group(2) + Theme.RESET + " " + renderInline(list.group(3));
        }
        return renderInline(line);
    }

    private boolean isFence(String line) {
        String s = stripLeading(line);
        return s.startsWith("```") || s.startsWith("~~~");
    }

    private String fenceLang(String line) {
        String s = line.strip();
        String info = s.startsWith("```") ? s.substring(3) : s.substring(3);
        info = info.strip();
        int space = info.indexOf(' ');
        return space >= 0 ? info.substring(0, space) : info;
    }

    /**
     * 行内元素：行内代码 > 链接 > 粗体 > 斜体/删除线。
     * 实现按定界符逐段扫描，行内代码内部不再嵌套解析。
     */
    private String renderInline(String text) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '`') {
                int close = text.indexOf('`', i + 1);
                if (close > i) {
                    out.append(Theme.INLINE_CODE).append(text, i + 1, close).append(Theme.RESET);
                    i = close + 1;
                    continue;
                }
            }
            if (c == '[') {
                Matcher link = LINK.matcher(text).region(i, text.length());
                if (link.lookingAt()) {
                    out.append(Theme.BOLD).append(link.group(1)).append(Theme.RESET)
                            .append(" ").append(Theme.LINK_URL).append("(").append(link.group(2)).append(")")
                            .append(Theme.RESET);
                    i = link.end();
                    continue;
                }
            }
            if (c == '*' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int close = text.indexOf("**", i + 2);
                if (close > 0) {
                    out.append(Theme.BOLD).append(text, i + 2, close).append(Theme.RESET);
                    i = close + 2;
                    continue;
                }
            }
            if (c == '~' && i + 1 < text.length() && text.charAt(i + 1) == '~') {
                int close = text.indexOf("~~", i + 2);
                if (close > 0) {
                    out.append(Theme.STRIKETHROUGH).append(text, i + 2, close).append(Theme.RESET);
                    i = close + 2;
                    continue;
                }
            }
            if (c == '*') {
                int close = text.indexOf('*', i + 1);
                if (close > i + 1) {
                    out.append(Theme.ITALIC).append(text, i + 1, close).append(Theme.RESET);
                    i = close + 1;
                    continue;
                }
            }
            if (c == '_') {
                int close = text.indexOf('_', i + 1);
                if (close > i + 1) {
                    out.append(Theme.ITALIC).append(text, i + 1, close).append(Theme.RESET);
                    i = close + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 最多剥 3 个前导空格（markdown 规范里 4 个空格是代码块，本渲染器不支持该块型）。 */
    private String stripLeading(String line) {
        int i = 0;
        while (i < line.length() && i < 3 && line.charAt(i) == ' ') {
            i++;
        }
        return line.substring(i);
    }
}
