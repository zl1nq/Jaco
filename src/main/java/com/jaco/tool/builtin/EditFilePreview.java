package com.jaco.tool.builtin;

import java.util.ArrayList;
import java.util.List;

/** 根据已知修改位置生成预览，不对完整文件拆行或执行 LCS。 */
final class EditFilePreview {
    private record Hunk(int start, int end) {}
    private final StringBuilder out = new StringBuilder();
    private final Runnable check;
    private int rows;
    private boolean clipped, exhausted;

    private EditFilePreview(Runnable check) { this.check = check; }

    static String render(String path, String before, String after,
                         List<EditFileTool.Replacement> changes, Runnable check) {
        EditFilePreview preview = new EditFilePreview(check);
        preview.out.append("文件修改预览: ").append(safe(path, 300)).append(" [修改]\n");
        if (before.equals(after)) return preview.out.append("内容无变化").toString();
        List<Hunk> hunks = new ArrayList<>();
        for (var change : changes) {
            check.run();
            Hunk hunk = new Hunk(lineStart(before, change.start(), check), lineEnd(before, change.end() - 1, check));
            if (!hunks.isEmpty() && hunk.start() <= hunks.getLast().end()) {
                Hunk previous = hunks.removeLast();
                hunk = new Hunk(previous.start(), Math.max(previous.end(), hunk.end()));
            }
            hunks.add(hunk);
        }
        // 删除换行可能把后面的原文行接入新行，预览必须一起展示，不能把区间边界误报成文件末尾。
        for (int i = 0; i < hunks.size(); i++) {
            Hunk hunk = hunks.get(i);
            while (true) {
                int mappedEnd = hunk.end();
                for (var change : changes) {
                    if (change.start() >= hunk.end()) break;
                    mappedEnd += change.text().length() - (change.end() - change.start());
                }
                if (hunk.end() < before.length() && mappedEnd > 0 && mappedEnd < after.length()
                        && (after.charAt(mappedEnd - 1) != '\n' && after.charAt(mappedEnd - 1) != '\r'
                        || after.charAt(mappedEnd - 1) == '\r' && after.charAt(mappedEnd) == '\n')) {
                    hunk = new Hunk(hunk.start(), lineEnd(before, hunk.end(), check));
                } else if (i + 1 < hunks.size() && hunks.get(i + 1).start() <= hunk.end()) {
                    hunk = new Hunk(hunk.start(), Math.max(hunk.end(), hunks.remove(i + 1).end()));
                } else break;
                check.run();
            }
            hunks.set(i, hunk);
        }
        int removed = 0, added = 0, delta = 0, index = 0;
        List<Hunk> updatedHunks = new ArrayList<>();
        List<EditFileTool.Replacement> updatedChanges = new ArrayList<>();
        for (Hunk hunk : hunks) {
            int start = hunk.start() + delta;
            while (index < changes.size() && changes.get(index).start() < hunk.end()) {
                var change = changes.get(index++);
                int changeStart = change.start() + delta;
                updatedChanges.add(new EditFileTool.Replacement(change.index(), changeStart,
                        changeStart + change.text().length(), change.text()));
                delta += change.text().length() - (change.end() - change.start());
            }
            Hunk updated = new Hunk(start, hunk.end() + delta);
            updatedHunks.add(updated);
            removed += countLines(before, hunk.start(), hunk.end(), check);
            added += countLines(after, updated.start(), updated.end(), check);
        }
        preview.out.append("新增 ").append(added).append(" 行，删除 ").append(removed)
                .append(" 行；换行 ").append(ending(before)).append(" → ").append(ending(after))
                .append("\n按修改区间展示，区间内的完整行作为删除/新增；格式: +/- 旧行号:新行号 | 内容\n");
        int scan = 0, oldLine = 1, lineDelta = 0;
        for (int i = 0; i < hunks.size(); i++) {
            Hunk hunk = hunks.get(i), updated = updatedHunks.get(i);
            int contextStart = hunk.start();
            for (int j = 0; j < 2 && contextStart > 0; j++) contextStart = lineStart(before, contextStart - 1, check);
            oldLine += countBreaks(before, scan, contextStart, check);
            int changedLine = oldLine + countBreaks(before, contextStart, hunk.start(), check);
            if (i > 0 || contextStart > 0) preview.out.append("...\n");
            preview.emit(before, contextStart, hunk.start(), ' ', oldLine, oldLine + lineDelta, List.of());
            preview.emit(before, hunk.start(), hunk.end(), '-', changedLine, 0, changes);
            preview.emit(after, updated.start(), updated.end(), '+', 0, changedLine + lineDelta, updatedChanges);
            lineDelta += countBreaks(after, updated.start(), updated.end(), check)
                    - countBreaks(before, hunk.start(), hunk.end(), check);
            int contextEnd = hunk.end();
            for (int j = 0; j < 2 && contextEnd < before.length(); j++) contextEnd = lineEnd(before, contextEnd, check);
            int followingLine = changedLine + countBreaks(before, hunk.start(), hunk.end(), check);
            preview.emit(before, hunk.end(), contextEnd, ' ', followingLine, followingLine + lineDelta, List.of());
            // 下一区间的上下文可能重叠，行号仍由原始位置计算。
            scan = contextStart;
            if (preview.exhausted) break;
        }
        if (preview.clipped) preview.out.append("[预览已截短，确认后执行完整修改；最多 80 行，长行最多 200 个字符]\n");
        return preview.out.toString().stripTrailing();
    }

    private void emit(String text, int start, int end, char kind, int oldLine, int newLine,
                      List<EditFileTool.Replacement> changes) {
        while (start < end && !exhausted) {
            check.run();
            int next = Math.min(end, lineEnd(text, start, check));
            int bodyEnd = next;
            boolean terminated = text.charAt(bodyEnd - 1) == '\n' || text.charAt(bodyEnd - 1) == '\r';
            if (terminated) {
                if (text.charAt(--bodyEnd) == '\n' && bodyEnd > start && text.charAt(bodyEnd - 1) == '\r') bodyEnd--;
            }
            // 只复制预览所需字符；巨型单行也不会构造完整的行字符串。
            int clipStart = start;
            for (var change : changes) {
                if (change.start() >= start && change.start() <= bodyEnd) {
                    if (change.start() - start > 100) {
                        clipStart = Math.max(start, change.start() - 80);
                        if (clipStart > start && Character.isLowSurrogate(text.charAt(clipStart))) clipStart--;
                    }
                    break;
                }
            }
            int clipEnd = clipStart;
            for (int n = 0; n < 200 && clipEnd < bodyEnd; n++) clipEnd += Character.charCount(text.codePointAt(clipEnd));
            String body = (clipStart > start ? "…" : "") + safe(text.substring(clipStart, clipEnd), 200);
            if (clipStart > start) clipped = true;
            if (clipEnd < bodyEnd) { body += "…"; clipped = true; }
            if (!terminated) body += " [无末尾换行]";
            String row = kind + " " + (oldLine == 0 ? "-" : oldLine) + ":"
                    + (newLine == 0 ? "-" : newLine) + " | " + body + "\n";
            if (rows >= 80 || out.length() + row.length() > 7400) { clipped = true; exhausted = true; return; }
            out.append(row); rows++;
            if (oldLine != 0) oldLine++;
            if (newLine != 0) newLine++;
            start = next;
        }
    }

    private static int lineStart(String text, int position, Runnable check) {
        if (position > 0 && position < text.length() && text.charAt(position) == '\n' && text.charAt(position - 1) == '\r') position--;
        while (position > 0 && text.charAt(position - 1) != '\n' && text.charAt(position - 1) != '\r') {
            if ((position & 4095) == 0) check.run();
            position--;
        }
        return position;
    }

    private static int lineEnd(String text, int position, Runnable check) {
        while (position < text.length() && text.charAt(position) != '\n' && text.charAt(position) != '\r') {
            if ((position & 4095) == 0) check.run();
            position++;
        }
        if (position < text.length() && text.charAt(position++) == '\r'
                && position < text.length() && text.charAt(position) == '\n') position++;
        return position;
    }

    private static int countBreaks(String text, int start, int end, Runnable check) {
        int lines = 0;
        for (int i = start; i < end; i++) {
            if ((i & 4095) == 0) check.run();
            char c = text.charAt(i);
            if (c == '\r') { lines++; if (i + 1 < end && text.charAt(i + 1) == '\n') i++; }
            else if (c == '\n') lines++;
        }
        return lines;
    }

    private static int countLines(String text, int start, int end, Runnable check) {
        return countBreaks(text, start, end, check)
                + (end > start && text.charAt(end - 1) != '\n' && text.charAt(end - 1) != '\r' ? 1 : 0);
    }

    static String ending(String text) {
        if (text.indexOf('\n') < 0 && text.indexOf('\r') < 0) return "无换行";
        return switch (EditFileTool.newline(text)) { case "\r\n" -> "CRLF"; case "\r" -> "CR"; case "\n" -> "LF"; default -> "混合"; };
    }

    private static String safe(String text, int limit) {
        StringBuilder result = new StringBuilder();
        text.codePoints().limit(limit).forEach(c -> {
            if (c == '\t') result.append("    ");
            else if (Character.isISOControl(c)) result.append(String.format("\\u%04x", c));
            else result.appendCodePoint(c);
        });
        if (text.codePointCount(0, text.length()) > limit) result.append('…');
        return result.toString();
    }
}
