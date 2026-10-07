package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.tool.JsonSchema;
import com.jaco.tool.Tool;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** 列出目录内容。 */
public final class ListDirTool implements Tool {

    private static final int MAX_ENTRIES = 500;

    @Override
    public String name() {
        return "list_dir";
    }

    @Override
    public String description() {
        return "列出目录内容（不递归），目录以 / 结尾标注。";
    }

    @Override
    public JsonNode schema() {
        return JsonSchema.object()
                .string("path", "目录路径，默认工作目录")
                .build();
    }

    @Override
    public String summary(JsonNode args) {
        return args.path("path").asText(".");
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws IOException {
        Path dir = ctx.sandbox().resolve(args.path("path").asText("."));
        if (!Files.isDirectory(dir)) {
            throw new ToolException("不是目录: " + dir);
        }
        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> sorted = entries
                    .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (Path p : sorted) {
                if (count == MAX_ENTRIES) {
                    sb.append("(共 ").append(sorted.size()).append(" 项，仅显示前 ").append(MAX_ENTRIES).append(" 项)");
                    break;
                }
                if (Files.isDirectory(p)) {
                    sb.append(p.getFileName()).append("/\n");
                } else {
                    try {
                        sb.append(p.getFileName())
                                .append("  (").append(Files.size(p)).append(" 字节)\n");
                    } catch (IOException e) {
                        sb.append(p.getFileName()).append("  (?)\n");
                    }
                }
                count++;
            }
            return sb.isEmpty() ? "(空目录)" : sb.toString();
        }
    }
}
