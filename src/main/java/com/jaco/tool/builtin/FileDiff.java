package com.jaco.tool.builtin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 有界的行级修改预览；过大的变化区间降级为整段删除/新增对比。 */
final class FileDiff {
    private record Row(char kind, int oldLine, int newLine, String text) {
    }

    private FileDiff() {
    }

    static String render(String path, boolean created, String before, String after) {
        List<String> a = lines(before);
        List<String> b = lines(after);
        List<Row> rows = new ArrayList<>();
        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) {
            rows.add(new Row(' ', prefix + 1, prefix + 1, a.get(prefix)));
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - suffix - 1).equals(b.get(b.size() - suffix - 1))) {
            suffix++;
        }
        int n = a.size() - prefix - suffix;
        int m = b.size() - prefix - suffix;
        boolean coarse = (long) n * m > 250_000;
        if (coarse) {
            for (int i = prefix; i < a.size() - suffix; i++) {
                rows.add(new Row('-', i + 1, 0, a.get(i)));
            }
            for (int j = prefix; j < b.size() - suffix; j++) {
                rows.add(new Row('+', 0, j + 1, b.get(j)));
            }
        } else {
            int[][] lcs = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--) {
                for (int j = m - 1; j >= 0; j--) {
                    lcs[i][j] = a.get(prefix + i).equals(b.get(prefix + j))
                            ? 1 + lcs[i + 1][j + 1] : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
            int i = 0;
            int j = 0;
            while (i < n || j < m) {
                if (i < n && j < m && a.get(prefix + i).equals(b.get(prefix + j))) {
                    rows.add(new Row(' ', prefix + i + 1, prefix + j + 1, a.get(prefix + i)));
                    i++;
                    j++;
                } else if (i < n && (j == m || lcs[i + 1][j] >= lcs[i][j + 1])) {
                    rows.add(new Row('-', prefix + i + 1, 0, a.get(prefix + i++)));
                } else {
                    rows.add(new Row('+', 0, prefix + j + 1, b.get(prefix + j++)));
                }
            }
        }
        for (int k = suffix; k > 0; k--) {
            rows.add(new Row(' ', a.size() - k + 1, b.size() - k + 1, a.get(a.size() - k)));
        }
        long added = rows.stream().filter(r -> r.kind() == '+').count();
        long removed = rows.stream().filter(r -> r.kind() == '-').count();
        StringBuilder out = new StringBuilder("文件修改预览: ").append(safe(path, 300))
                .append(created ? " [新建]" : " [修改]").append("\n新增 ").append(added)
                .append(" 行，删除 ").append(removed).append(" 行；换行 ")
                .append(ending(before)).append(" → ").append(ending(after)).append("\n");
        if (coarse) {
            out.append("变化区间较大，以下按整段删除/新增对比\n");
        }
        if (added == 0 && removed == 0) {
            return out.append("内容无变化").toString();
        }
        out.append("格式: +/- 旧行号:新行号 | 内容\n");
        int displayed = 0;
        boolean gap = false;
        boolean clipped = false;
        for (int k = 0; k < rows.size(); k++) {
            boolean show = false;
            for (int neighbour = Math.max(0, k - 2); neighbour <= Math.min(rows.size() - 1, k + 2); neighbour++) {
                if (rows.get(neighbour).kind() != ' ') {
                    show = true;
                    break;
                }
            }
            if (!show) {
                gap = true;
                continue;
            }
            Row row = rows.get(k);
            String raw = row.text();
            String body = raw.replaceFirst("\\r?\\n$", "");
            if (!raw.endsWith("\n")) {
                body += " [无末尾换行]";
            }
            if (body.codePointCount(0, body.length()) > 200) {
                clipped = true;
            }
            String line = row.kind() + " " + (row.oldLine() == 0 ? "-" : row.oldLine()) + ":"
                    + (row.newLine() == 0 ? "-" : row.newLine()) + " | " + safe(body, 200) + "\n";
            if (displayed >= 80 || out.length() + line.length() + 20 > 7600) {
                clipped = true;
                break;
            }
            if (gap) {
                out.append("...\n");
                gap = false;
            }
            out.append(line);
            displayed++;
        }
        if (clipped) {
            out.append("[预览已截短，确认后执行完整修改；长行最多显示 200 个字符]\n");
        }
        return out.toString().stripTrailing();
    }

    private static List<String> lines(String text) {
        return text.isEmpty() ? List.of() : Arrays.asList(text.split("(?<=\\n)"));
    }

    private static String ending(String text) {
        if (text.isEmpty()) {
            return "空内容";
        }
        int lf = 0;
        int crlf = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                if (i > 0 && text.charAt(i - 1) == '\r') {
                    crlf++;
                } else {
                    lf++;
                }
            }
        }
        return lf > 0 && crlf > 0 ? "混合" : crlf > 0 ? "CRLF" : lf > 0 ? "LF" : "无换行";
    }

    private static String safe(String text, int maxCodePoints) {
        StringBuilder out = new StringBuilder();
        text.codePoints().limit(maxCodePoints).forEach(c -> {
            if (c == '\t') {
                out.append("    ");
            } else if (Character.isISOControl(c)) {
                out.append(String.format("\\u%04x", c));
            } else {
                out.appendCodePoint(c);
            }
        });
        if (text.codePointCount(0, text.length()) > maxCodePoints) {
            out.append("…");
        }
        return out.toString();
    }
}
