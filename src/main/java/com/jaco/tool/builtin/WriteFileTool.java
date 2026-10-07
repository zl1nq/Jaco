package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.tool.JsonSchema;
import com.jaco.tool.Tool;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 写文件（整文件覆盖或新建），父目录自动创建。 */
public final class WriteFileTool implements Tool {

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "把 content 完整写入文件（整文件覆盖或新建，父目录自动创建）。修改前应先 read_file 了解现状。";
    }

    @Override
    public JsonNode schema() {
        return JsonSchema.object()
                .string("path", "目标文件路径（相对于工作目录或绝对路径）")
                .string("content", "要写入的完整内容（UTF-8 文本）")
                .required("path", "content")
                .build();
    }

    @Override
    public String summary(JsonNode args) {
        return args.path("path").asText("?");
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws IOException {
        Path file = ctx.sandbox().resolve(args.path("path").asText());
        if (Files.isDirectory(file)) {
            throw new ToolException("目标是目录: " + file);
        }
        String content = args.path("content").asText("");
        boolean existed = Files.exists(file);
        long oldSize = existed ? Files.size(file) : -1;
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, content);
        long newSize = Files.size(file);
        if (existed) {
            return "已覆盖 " + file + "（" + oldSize + " 字节 → " + newSize + " 字节）";
        }
        return "已创建 " + file + "（" + newSize + " 字节）";
    }
}
