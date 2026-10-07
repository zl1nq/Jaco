package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.tool.JsonSchema;
import com.jaco.tool.Tool;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 读取文本文件，带行号，支持 offset/limit 分页。 */
public final class ReadFileTool implements Tool {

    private static final int MAX_LINE_LENGTH = 2000;

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "读取文本文件内容（带行号）。大文件可用 offset/limit 分页读取。";
    }

    @Override
    public JsonNode schema() {
        return JsonSchema.object()
                .string("path", "文件路径（相对于工作目录或绝对路径）")
                .integer("offset", "起始行号（从 1 开始），默认 1")
                .integer("limit", "最多读取的行数，默认 2000")
                .build();
    }

    @Override
    public String summary(JsonNode args) {
        return args.path("path").asText("?");
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws IOException {
        Path file = ctx.sandbox().resolve(args.path("path").asText());
        if (!Files.isRegularFile(file)) {
            throw new ToolException("文件不存在或不是普通文件: " + file);
        }
        List<String> lines = Files.readAllLines(file);
        int offset = Math.max(1, args.path("offset").asInt(1));
        int limit = Math.max(1, args.path("limit").asInt(2000));
        if (offset > lines.size()) {
            throw new ToolException("offset " + offset + " 超出文件行数 " + lines.size());
        }

        StringBuilder sb = new StringBuilder();
        int last = Math.min(lines.size(), offset - 1 + limit);
        for (int i = offset - 1; i < last; i++) {
            String line = lines.get(i);
            if (line.length() > MAX_LINE_LENGTH) {
                line = line.substring(0, MAX_LINE_LENGTH) + " …(本行截断)";
            }
            sb.append(String.format("%6d| %s%n", i + 1, line));
        }
        if (last < lines.size()) {
            sb.append("(已省略 ").append(lines.size() - last)
                    .append(" 行，可增大 offset 继续读取；文件共 ").append(lines.size()).append(" 行)");
        }
        return sb.isEmpty() ? "(文件为空或区间内没有行)" : sb.toString();
    }
}
