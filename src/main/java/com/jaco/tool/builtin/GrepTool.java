package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.tool.JsonSchema;
import com.jaco.tool.Tool;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** 流式搜索文本；结果上限按命中行计数，上下文不重复输出。 */
public final class GrepTool implements Tool {
    private static final int MAX_LINE_DISPLAY = 200;

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description() {
        return "搜索 UTF-8 文件内容，默认 Java 正则，可用 literal 进行纯文本搜索、ignore_case 忽略大小写。"
                + "目录递归，默认跳过常见依赖/构建目录，不跟随子项符号链接；include/exclude 支持 glob。"
                + "输出路径:行号:内容；上下文使用路径-行号-内容，长行围绕命中位置截取并标明列号。"
                + "最多返回 max_results 个命中行，达到上限提前停止；更多内容请缩小范围或 read_file。";
    }

    @Override
    public JsonNode schema() {
        return JsonSchema.object()
                .string("pattern", "非空白搜索文本，默认 Java 正则")
                .string("path", "文件或目录，默认工作目录")
                .string("include", "文件 glob：无斜杠匹配文件名，有斜杠匹配相对路径，如 *.java 或 **/*Test.java")
                .strings("exclude", "排除文件或目录的 glob 数组，如 [\"generated\", \"*.min.js\"]")
                .bool("include_ignored", "是否搜索默认排除目录，默认 false；不影响 exclude")
                .bool("literal", "按普通文本搜索，默认 false")
                .bool("ignore_case", "忽略大小写，默认 false")
                .integer("context_lines", "命中行前后上下文行数，默认 0，范围 0-10")
                .integer("max_results", "命中行上限，默认 100，范围 1-1000")
                .required("pattern").build();
    }

    @Override
    public String summary(JsonNode args) {
        return "/" + args.path("pattern").asText("?") + "/ in " + args.path("path").asText(".");
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws IOException {
        SearchSupport.validate(args, "pattern", "path", "include", "exclude", "include_ignored",
                "literal", "ignore_case", "context_lines", "max_results");
        String expression = SearchSupport.string(args, "pattern", null);
        if (expression == null) {
            throw new ToolException("pattern 必须提供非空白字符串");
        }
        boolean literal = SearchSupport.bool(args, "literal");
        boolean ignoreCase = SearchSupport.bool(args, "ignore_case");
        Pattern pattern;
        try {
            pattern = Pattern.compile(literal ? Pattern.quote(expression) : expression,
                    ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
        } catch (PatternSyntaxException e) {
            throw new ToolException("正则表达式无效: " + e.getMessage());
        }
        Path root = ctx.sandbox().resolve(SearchSupport.string(args, "path", "."));
        String include = SearchSupport.string(args, "include", null);
        SearchSupport.Glob filter = include == null ? null : new SearchSupport.Glob(include);
        int max = SearchSupport.integer(args, "max_results", 100, 1, 1000);
        int context = SearchSupport.integer(args, "context_lines", 0, 0, 10);
        boolean includeIgnored = SearchSupport.bool(args, "include_ignored");
        List<String> hits = new ArrayList<>();
        SearchSupport.Scan scan = new SearchSupport.Scan();
        int[] count = {0};
        SearchSupport.walk(root, ctx, SearchSupport.exclusions(args), includeIgnored, scan, (file, relative) -> {
            if (filter != null && !filter.matches(Path.of(relative))) {
                return true;
            }
            return searchFile(file, relative, pattern, context, max, count, hits, ctx);
        });
        return scan.finish(hits);
    }

    private record Line(int number, String text) {
    }

    private boolean searchFile(Path file, String relative, Pattern pattern, int context, int max,
                               int[] count, List<String> hits, ToolContext ctx) throws IOException {
        try (var in = Files.newInputStream(file)) {
            for (byte b : in.readNBytes(8192)) {
                if (b == 0) {
                    return true;
                }
            }
        }
        ArrayDeque<Line> before = new ArrayDeque<>();
        int number = 0;
        int lastPrinted = 0;
        int after = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            while (true) {
                SearchSupport.checkCancelled(ctx);
                if (count[0] >= max && after == 0) {
                    return false;
                }
                String text = reader.readLine();
                if (text == null) {
                    break;
                }
                number++;
                Matcher matcher = pattern.matcher(text);
                boolean matched = count[0] < max && matcher.find();
                if (matched) {
                    for (Line line : before) {
                        if (line.number() > lastPrinted) {
                            hits.add(relative + "-" + line.number() + "- " + line.text());
                        }
                    }
                    hits.add(relative + ":" + number + ": " + preview(text, matcher.start()));
                    lastPrinted = number;
                    count[0]++;
                    after = context;
                } else if (after > 0) {
                    hits.add(relative + "-" + number + "- " + preview(text, -1));
                    lastPrinted = number;
                    after--;
                }
                if (context > 0) {
                    before.addLast(new Line(number, preview(text, -1)));
                    if (before.size() > context) {
                        before.removeFirst();
                    }
                }
            }
        }
        return count[0] < max;
    }

    private String preview(String text, int matchStart) {
        if (text.length() <= MAX_LINE_DISPLAY) {
            return text;
        }
        int start = Math.max(0, matchStart - MAX_LINE_DISPLAY / 2);
        int end = Math.min(text.length(), start + MAX_LINE_DISPLAY);
        // 不在 UTF-16 代理对中间截断。
        if (start > 0 && Character.isLowSurrogate(text.charAt(start))) {
            start--;
        }
        if (end < text.length() && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return (start > 0 ? "…" : "") + text.substring(start, end)
                + (end < text.length() ? "…" : "")
                + (matchStart >= 0 ? " (内容已截断，命中列 " + (matchStart + 1) + ")" : " (内容已截断)");
    }
}
