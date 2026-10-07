package com.jaco.render;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流式 markdown 渲染器（按行状态机）：只对完整行做渲染。
 * 代码块围栏之间与表格行会缓冲；跨行结构降级为原文——"流式体验优先"的取舍。
 * 每个 turn 新建一个实例（代码块/表格状态不跨轮）。
 *
 * <p>返回值契约：renderLine 返回 null 表示该行已进入表格缓冲；
 * 否则返回若干"行"（每行是 Span 序列）。flush() 在收尾/中断/工具事件前调用，
 * 输出所有未决缓冲（未闭合的表格、挂起的行）。
 */
public final class MarkdownRenderer {

    private static final Pattern HR = Pattern.compile("\\s*(---+|===+|\\*\\*\\*+|___+)\\s*");
    private static final Pattern LIST_ITEM = Pattern.compile("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)");
    private static final Pattern LINK = Pattern.compile("\\[([^]\\n]+)]\\(([^)\\n]+)\\)");
    private static final Pattern SEPARATOR_ROW =
            Pattern.compile("^\\s*\\|?(\\s*:?-{3,}:?\\s*\\|)+\\s*:?-{3,}:?\\s*\\|?\\s*$");

    private boolean inCodeBlock;
    private CodeHighlighter highlighter = new CodeHighlighter("");

    private boolean inTable;
    private String pendingRow;          // 等待下一行确认是否为表格
    private final List<String> tableRows = new ArrayList<>();

    /**
     * 渲染一行完整文本。返回 null 表示已进入表格缓冲；
     * 否则返回一至多行 Span 序列（表格触发时是多行）。
     */
    public List<List<Span>> renderLine(String line) {
        if (inCodeBlock) {
            if (isFence(line)) {
                inCodeBlock = false;
                highlighter = new CodeHighlighter("");
                return List.of(line(Span.of(line.strip(), Color.FENCE)));
            }
            return List.of(highlighter.highlight(line));
        }
        if (isFence(line)) {
            // 围栏终止未决的表格/挂起行，先输出它们
            List<List<Span>> flushed = flushBuffered();
            inCodeBlock = true;
            highlighter = new CodeHighlighter(fenceLang(line));
            List<Span> fence = line(Span.of(line.strip(), Color.FENCE));
            if (flushed == null) {
                return List.of(fence);
            }
            flushed.add(fence);
            return flushed;
        }

        // 表格缓冲状态机
        if (inTable) {
            if (isTableRow(line)) {
                tableRows.add(line);
                return null;
            }
            List<List<Span>> table = renderTable();
            inTable = false;
            tableRows.clear();
            List<List<Span>> rest = processLine(line);
            if (rest != null) {
                table.addAll(rest);
            }
            return table;
        }
        if (pendingRow != null) {
            if (isSeparatorRow(line)) {
                inTable = true;
                tableRows.add(pendingRow); // 分隔线只用于确认，不进数据行
                pendingRow = null;
                return null;
            }
            List<List<Span>> out = new ArrayList<>();
            out.add(renderInline(pendingRow, false, false, false));
            pendingRow = isTableRow(line) ? line : null;
            if (pendingRow == null) {
                out.addAll(processLine(line));
            }
            return out;
        }
        if (isTableRow(line)) {
            pendingRow = line;
            return null;
        }
        return processLine(line);
    }

    /** 输出所有未决缓冲（工具事件/Done/中断前调用）。无缓冲时返回 null。 */
    public List<List<Span>> flush() {
        return flushBuffered();
    }

    private List<List<Span>> flushBuffered() {
        if (inTable) {
            List<List<Span>> table = renderTable();
            inTable = false;
            tableRows.clear();
            return table;
        }
        if (pendingRow != null) {
            List<List<Span>> out = List.of(renderInline(pendingRow, false, false, false));
            pendingRow = null;
            return out;
        }
        return null;
    }

    // ---- 表格 ----

    private boolean isTableRow(String line) {
        String s = line.strip();
        return s.startsWith("|") && s.length() > 1;
    }

    private boolean isSeparatorRow(String line) {
        return SEPARATOR_ROW.matcher(line).matches();
    }

    private List<String> parseCells(String row) {
        String s = row.strip();
        if (s.startsWith("|")) {
            s = s.substring(1);
        }
        if (s.endsWith("|")) {
            s = s.substring(0, s.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String c : s.split("\\|", -1)) {
            cells.add(c.strip());
        }
        return cells;
    }

    /** 对齐渲染：表头加粗 + 暗色 ├─┼┤ 分隔线；列宽按去标记后的显示宽度计算。 */
    private List<List<Span>> renderTable() {
        List<List<String>> rows = new ArrayList<>();
        for (String r : tableRows) {
            rows.add(parseCells(r));
        }
        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        int[] widths = new int[cols];
        for (List<String> cells : rows) {
            for (int i = 0; i < cols; i++) {
                String cell = i < cells.size() ? cells.get(i) : "";
                widths[i] = Math.max(widths[i], DisplayWidth.width(plainText(cell)));
            }
        }

        List<List<Span>> out = new ArrayList<>();
        // 表头（加粗）
        out.add(tableRowSpans(rows.get(0), widths, true));
        // 表头下分隔线
        List<Span> separator = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cols; i++) {
            line.append(i == 0 ? "├" : "┼").append("─".repeat(widths[i] + 2));
        }
        line.append("┤");
        separator.add(Span.of(line.toString(), Color.FENCE));
        out.add(separator);
        // 表体
        for (int r = 1; r < rows.size(); r++) {
            out.add(tableRowSpans(rows.get(r), widths, false));
        }
        return out;
    }

    private List<Span> tableRowSpans(List<String> cells, int[] widths, boolean header) {
        List<Span> spans = new ArrayList<>();
        spans.add(Span.of("|", Color.FENCE));
        for (int i = 0; i < widths.length; i++) {
            String cell = i < cells.size() ? cells.get(i) : "";
            // 内容区 = 空格 + 单元格 + 补齐 + 空格，总宽 widths[i]+2，与分隔线 ─(w+2) 严格对齐
            spans.add(Span.plain(" "));
            spans.addAll(renderInline(cell, header, false, false));
            int pad = widths[i] - DisplayWidth.width(plainText(cell));
            if (pad > 0) {
                spans.add(Span.plain(" ".repeat(pad)));
            }
            spans.add(Span.plain(" "));
            spans.add(Span.of("|", Color.FENCE));
        }
        return spans;
    }

    /** 去掉行内标记后的纯文本（列宽测量的依据；与 renderInline 的标记集保持一致）。 */
    private String plainText(String cell) {
        return cell.replace("**", "").replace("__", "").replace("`", "")
                .replace("~~", "");
    }

    // ---- 非表格行 ----

    private List<List<Span>> processLine(String line) {
        if (HR.matcher(line).matches()) {
            return List.of(line(Span.of("─".repeat(40), Color.FENCE)));
        }
        String stripped = stripLeading(line);
        if (!stripped.isEmpty() && stripped.charAt(0) == '#') {
            int level = 0;
            while (level < stripped.length() && stripped.charAt(level) == '#') {
                level++;
            }
            if (level <= 6 && level < stripped.length() && stripped.charAt(level) == ' ') {
                String text = stripped.substring(level).strip();
                return List.of(line(Span.of(text, Color.HEADER).withBold()));
            }
            return List.of(renderInline(line, false, false, false));
        }
        if (!stripped.isEmpty() && stripped.charAt(0) == '>') {
            String text = stripped.substring(1).strip();
            return List.of(line(Span.of("▏ ", Color.QUOTE), Span.of(text, Color.QUOTE).withItalic()));
        }
        Matcher list = LIST_ITEM.matcher(line);
        if (list.matches()) {
            List<Span> spans = new ArrayList<>();
            spans.add(Span.plain(list.group(1)));
            spans.add(Span.of(list.group(2), Color.LIST_MARK));
            spans.add(Span.plain(" "));
            spans.addAll(renderInline(list.group(3), false, false, false));
            return List.of(spans);
        }
        return List.of(renderInline(line, false, false, false));
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
     * 按定界符逐段扫描，行内代码内部不再嵌套解析；嵌套装饰（粗体里的行内代码）通过
     * 递归时给子 Span 叠加标志实现。
     */
    private List<Span> renderInline(String text, boolean bold, boolean italic, boolean strike) {
        List<Span> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '`') {
                int close = text.indexOf('`', i + 1);
                if (close > i) {
                    out.add(decorate(Span.of(text.substring(i + 1, close), Color.INLINE_CODE), bold, italic, strike));
                    i = close + 1;
                    continue;
                }
            }
            if (c == '[') {
                Matcher link = LINK.matcher(text).region(i, text.length());
                if (link.lookingAt()) {
                    out.add(decorate(Span.of(link.group(1), null), true, italic, strike));
                    out.add(decorate(Span.of("(" + link.group(2) + ")", Color.LINK_URL), bold, italic, strike));
                    i = link.end();
                    continue;
                }
            }
            if (c == '*' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int close = text.indexOf("**", i + 2);
                if (close > 0) {
                    out.addAll(renderInline(text.substring(i + 2, close), true, italic, strike));
                    i = close + 2;
                    continue;
                }
            }
            if (c == '~' && i + 1 < text.length() && text.charAt(i + 1) == '~') {
                int close = text.indexOf("~~", i + 2);
                if (close > 0) {
                    out.addAll(renderInline(text.substring(i + 2, close), bold, italic, true));
                    i = close + 2;
                    continue;
                }
            }
            if (c == '*') {
                int close = text.indexOf('*', i + 1);
                if (close > i + 1) {
                    out.addAll(renderInline(text.substring(i + 1, close), bold, true, strike));
                    i = close + 1;
                    continue;
                }
            }
            if (c == '_') {
                int close = text.indexOf('_', i + 1);
                if (close > i + 1) {
                    out.addAll(renderInline(text.substring(i + 1, close), bold, true, strike));
                    i = close + 1;
                    continue;
                }
            }
            // 普通字符：与后续普通字符合并成一个 Span，减少对象数
            int j = i;
            while (j < text.length()) {
                char d = text.charAt(j);
                if (d == '`' || d == '[' || d == '*' || d == '_' || d == '~') {
                    break;
                }
                j++;
            }
            if (j == i) {
                j = i + 1;
            }
            out.add(decorate(Span.of(text.substring(i, j), null), bold, italic, strike));
            i = j;
        }
        return out;
    }

    private Span decorate(Span span, boolean bold, boolean italic, boolean strike) {
        Span result = span;
        if (bold && !result.bold()) {
            result = result.withBold();
        }
        if (italic && !result.italic()) {
            result = result.withItalic();
        }
        if (strike && !result.strike()) {
            result = result.withStrike();
        }
        return result;
    }

    private List<Span> line(Span... spans) {
        return List.of(spans);
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
