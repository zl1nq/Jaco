package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaco.tool.JsonSchema;
import com.jaco.tool.Tool;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** 搜索当前会话的 JSONL 归档，或按条目 ID 分页读取原文。 */
public final class RecallTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_ENTRY_DISPLAY = 1200;
    private static final int DEFAULT_CONTENT_LIMIT = 2000;
    // 为条目元信息、范围和翻页提示预留空间。
    private static final int METADATA_RESERVE = 400;

    private record Entry(int id, String source, String content, int matchStart) {
    }

    @Override
    public String name() {
        return "recall";
    }

    @Override
    public String description() {
        return "检索本会话归档：query 按关键词/Java 正则搜索，offset/limit 对匹配条目分页，"
                + "返回条目 ID、长度和命中附近预览。"
                + "读取原文：entry_id 指定搜索返回的 #ID，content_offset/content_limit 按字符分页，保留换行。"
                + "单次输出受工具回填预算限制，请按返回的下一页参数继续读取。";
    }

    @Override
    public JsonNode schema() {
        return JsonSchema.object()
                .string("query", "关键词或 Java 正则；省略则搜索全部条目")
                .integer("offset", "搜索结果列表偏移（0 起），不是内容字符偏移")
                .integer("limit", "搜索最多返回条数，默认 20，上限 100；也受输出预算限制")
                .integer("entry_id", "读取模式：归档条目 #ID（0 起）；不要同时传 query/offset/limit")
                .integer("content_offset", "读取模式：原文字符偏移（0 起，Java UTF-16 单位），默认 0")
                .integer("content_limit", "读取模式：最多读取字符数，默认 2000；按输出预算自动缩小")
                .build();
    }

    @Override
    public String summary(JsonNode args) {
        if (args.has("entry_id")) {
            return "条目 #" + args.path("entry_id").asText() + "，字符偏移 " + args.path("content_offset").asInt(0);
        }
        String query = args.path("query").asText(null);
        return query == null ? "全部归档" : "/" + query + "/";
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws IOException {
        boolean reading = args.has("entry_id");
        int id = reading ? nonNegativeInt(args, "entry_id", 0) : -1;
        int contentOffset = reading ? nonNegativeInt(args, "content_offset", 0) : 0;
        int contentLimit = reading ? nonNegativeInt(args, "content_limit", DEFAULT_CONTENT_LIMIT) : 0;
        if (reading && contentLimit == 0) {
            throw new ToolException("content_limit 必须大于 0");
        }
        if (reading && (args.has("query") || args.has("offset") || args.has("limit"))) {
            throw new ToolException("entry_id 读取模式不能同时使用 query/offset/limit");
        }
        if (!reading && (args.has("content_offset") || args.has("content_limit"))) {
            throw new ToolException("读取内容分页需要 entry_id");
        }
        Path archive = ctx.archiveFile();
        if (archive == null || !Files.exists(archive)) {
            return "(归档为空：本会话还没有内容被压缩)";
        }
        Pattern query = null;
        String q = args.path("query").asText(null);
        if (!reading && q != null && !q.isBlank()) {
            try {
                query = Pattern.compile(q);
            } catch (PatternSyntaxException e) {
                throw new ToolException("query 正则无效: " + e.getMessage());
            }
        }

        int budget = ctx.maxToolResultChars() >= 1000 ? ctx.maxToolResultChars() : 8000;
        List<Entry> matches = new ArrayList<>();
        int total = 0;
        try (var reader = Files.newBufferedReader(archive, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode node;
                try {
                    node = MAPPER.readTree(line);
                } catch (IOException e) {
                    continue;
                }
                if (node == null || !node.isObject()) {
                    continue;
                }
                String text = node.path("content").asText("");
                String source = node.has("archived") ? "tool_output" : node.path("role").asText("message");
                // 来源只用作标签，长度受限以确保总输出预算。
                source = source.replace('\n', ' ').replace('\r', ' ');
                source = source.substring(0, Math.min(20, source.length()));
                Entry entry = new Entry(total++, source, text, 0);
                if (reading) {
                    if (entry.id() == id) {
                        return readEntry(entry, contentOffset, contentLimit, budget);
                    }
                    continue;
                }
                int matchStart = 0;
                if (query != null) {
                    Matcher matcher = query.matcher(text);
                    if (!matcher.find()) {
                        continue;
                    }
                    matchStart = matcher.start();
                }
                matches.add(new Entry(entry.id(), source, text, matchStart));
            }
        }
        if (reading) {
            return "(entry_id " + id + " 超出范围，归档共 " + total + " 条)";
        }
        if (matches.isEmpty()) {
            return "(无匹配条目，归档共 " + total + " 条)";
        }
        int offset = Math.max(0, args.path("offset").asInt(0));
        if (offset >= matches.size()) {
            return "(offset " + offset + " 超出范围，命中共 " + matches.size() + " 条)";
        }
        int limit = Math.min(MAX_LIMIT, Math.max(1, args.path("limit").asInt(DEFAULT_LIMIT)));
        int end = offset;
        StringBuilder rows = new StringBuilder();
        while (end < matches.size() && end - offset < limit) {
            String row = preview(matches.get(end), Math.min(MAX_ENTRY_DISPLAY, budget - METADATA_RESERVE));
            if (rows.length() + row.length() + 1 > budget - 200) {
                break;
            }
            rows.append(row).append("\n");
            end++;
        }
        String header = "命中 " + matches.size() + " / 归档共 " + total + " 条，显示匹配结果 "
                + offset + "–" + (end - 1);
        if (end < matches.size()) {
            header += "（用 offset=" + end + " 翻页）";
        }
        return header + "\n" + rows.toString().stripTrailing();
    }

    private String preview(Entry entry, int maxChars) {
        String text = entry.content();
        int start = Math.max(0, entry.matchStart() - Math.min(120, maxChars / 4));
        if (splitsSurrogate(text, start)) {
            start--;
        }
        int end = (int) Math.min(text.length(), (long) start + maxChars);
        if (splitsSurrogate(text, end)) {
            end--;
        }
        return "[#" + entry.id() + "] (" + entry.source() + ") "
                + (start > 0 ? "…" : "") + text.substring(start, end).replace('\n', ' ')
                + (end < text.length() ? "…" : "")
                + "（共 " + text.length() + " 字符，预览 [" + start + ", " + end
                + ")；原文用 entry_id=" + entry.id() + ", content_offset=" + start + " 读取）";
    }

    private String readEntry(Entry entry, int offset, int limit, int budget) {
        String text = entry.content();
        if (offset > text.length()) {
            return "(content_offset " + offset + " 超出范围，条目 #" + entry.id()
                    + " 共 " + text.length() + " 字符)";
        }
        if (splitsSurrogate(text, offset)) {
            throw new ToolException("content_offset 位于 Unicode 字符中间，请使用上一页返回的偏移");
        }
        int count = Math.min(limit, budget - METADATA_RESERVE);
        int end = (int) Math.min(text.length(), (long) offset + count);
        if (splitsSurrogate(text, end)) {
            end--;
        }
        if (end == offset && offset < text.length()) {
            throw new ToolException("content_limit 太小，无法读取完整 Unicode 字符，请至少设为 2");
        }
        String next = end < text.length()
                ? "下一页：entry_id=" + entry.id() + ", content_offset=" + end + ", content_limit=" + Math.max(2, count)
                : "已到条目末尾";
        return "[#" + entry.id() + "] (" + entry.source() + ") 共 " + text.length()
                + " 字符，本次读取 [" + offset + ", " + end + ")\n"
                + text.substring(offset, end) + "\n" + next;
    }

    private int nonNegativeInt(JsonNode args, String name, int fallback) {
        JsonNode value = args.get(name);
        if (value == null) {
            return fallback;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw new ToolException(name + " 必须是非负整数，且不超过 " + Integer.MAX_VALUE);
        }
        return value.intValue();
    }

    private boolean splitsSurrogate(String text, int offset) {
        return offset > 0 && offset < text.length()
                && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset));
    }
}
