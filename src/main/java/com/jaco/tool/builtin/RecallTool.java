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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 检索当前会话的压缩归档（JSONL，一行一条）。两类条目混在同一文件里：
 * 超大工具输出 {"archived":"tool_output","content":...} 与被压缩的整轮 Message（有 role 字段）。
 * 归档文件在会话目录内、不在沙箱范围内，因此本工具不走 sandbox，凭 ctx 携带的归档路径直读。
 */
public final class RecallTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_ENTRY_DISPLAY = 1200;

    @Override
    public String name() {
        return "recall";
    }

    @Override
    public String description() {
        return "检索本会话被压缩归档的历史（被省略的大工具输出、早前对话）。query 按关键词/正则过滤，offset/limit 分页。输出 [#序号] (来源) 内容。";
    }

    @Override
    public JsonNode schema() {
        return JsonSchema.object()
                .string("query", "关键词或正则（Java 语法），匹配条目内容；省略则返回全部")
                .integer("offset", "起始条目序号（0 起，作用于过滤后的列表）")
                .integer("limit", "最多返回条数，默认 20，上限 100")
                .build();
    }

    @Override
    public String summary(JsonNode args) {
        String query = args.path("query").asText(null);
        return query == null ? "全部归档" : "/" + query + "/";
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws IOException {
        Path archive = ctx.archiveFile();
        if (archive == null || !Files.exists(archive)) {
            return "(归档为空：本会话还没有内容被压缩)";
        }

        Pattern query = null;
        String q = args.path("query").asText(null);
        if (q != null && !q.isBlank()) {
            try {
                query = Pattern.compile(q);
            } catch (PatternSyntaxException e) {
                throw new ToolException("query 正则无效: " + e.getMessage());
            }
        }

        List<String> rendered = new ArrayList<>();
        int total = 0;
        for (String line : Files.readAllLines(archive, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node;
            try {
                node = MAPPER.readTree(line);
            } catch (IOException e) {
                continue;
            }
            total++;
            String text = contentOf(node);
            if (query != null && !query.matcher(text).find()) {
                continue;
            }
            rendered.add("[#" + (total - 1) + "] (" + sourceOf(node) + ") " + clip(text));
        }

        if (rendered.isEmpty()) {
            return "(无匹配条目，归档共 " + total + " 条)";
        }

        int offset = Math.max(0, args.path("offset").asInt(0));
        if (offset >= rendered.size()) {
            return "(offset " + offset + " 超出范围，命中共 " + rendered.size() + " 条)";
        }
        int limit = Math.min(MAX_LIMIT, Math.max(1, args.path("limit").asInt(DEFAULT_LIMIT)));
        int end = Math.min(rendered.size(), offset + limit);

        StringBuilder out = new StringBuilder();
        out.append("命中 ").append(rendered.size()).append(" / 归档共 ").append(total).append(" 条，显示 #")
                .append(offset).append("–").append(end - 1);
        if (end < rendered.size()) {
            out.append("（用 offset=").append(end).append(" 翻页）");
        }
        out.append("\n");
        for (int i = offset; i < end; i++) {
            out.append(rendered.get(i)).append("\n");
        }
        return out.toString().stripTrailing();
    }

    private String sourceOf(JsonNode node) {
        return node.has("archived") ? "tool_output" : node.path("role").asText("message");
    }

    private String contentOf(JsonNode node) {
        return node.path("content").asText("");
    }

    private String clip(String text) {
        String flat = text.replace('\n', ' ');
        if (flat.length() > MAX_ENTRY_DISPLAY) {
            return flat.substring(0, MAX_ENTRY_DISPLAY) + "…(共 " + text.length() + " 字符，可 narrower query 再取)";
        }
        return flat;
    }
}
