package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaco.tool.JsonSchema;
import com.jaco.tool.Tool;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;
import com.jaco.tool.PreparedToolCall;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** 用唯一的精确文本匹配定位，原子替换已有 UTF-8 文件中的一个片段。 */
public final class EditFileTool implements Tool {

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description() {
        return "局部修改已有 UTF-8 文件：先 read_file，再提供 path、old_text、new_text。"
                + "old_text 必须非空且在文件中精确唯一匹配（包括空白和换行，不要带读取输出的行号）。"
                + "未找到或多处匹配时拒绝修改，请重新读取或增加定位上下文。"
                + "new_text 可为空字符串以删除片段；使用原子替换，文件其他内容保持原样。";
    }

    @Override
    public JsonNode schema() {
        ObjectNode schema = (ObjectNode) JsonSchema.object()
                .string("path", "已有文件路径，必须是非空白字符串")
                .string("old_text", "要替换的非空原文，需精确唯一匹配；包含足够上下文，不含行号")
                .string("new_text", "替换文本，空字符串表示删除匹配片段")
                .required("path", "old_text", "new_text").build();
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public String summary(JsonNode args) {
        return args.path("path").asText("?");
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws IOException {
        try {
            return prepare(args, ctx).execute();
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public PreparedToolCall prepare(JsonNode args, ToolContext ctx) throws IOException {
        if (args == null || !args.isObject()) {
            throw new ToolException("edit_file 参数必须是 JSON 对象");
        }
        String path = stringArg(args, "path");
        String oldText = stringArg(args, "old_text");
        String newText = stringArg(args, "new_text");
        if (path.isBlank()) {
            throw new ToolException("path 必须是非空白字符串");
        }
        if (oldText.isEmpty()) {
            throw new ToolException("old_text 不能为空，请提供唯一定位片段");
        }
        var fields = args.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!field.equals("path") && !field.equals("old_text") && !field.equals("new_text")) {
                throw new ToolException("未知的 edit_file 参数: " + field);
            }
        }
        Path file;
        try {
            file = ctx.sandbox().resolve(path);
        } catch (InvalidPathException e) {
            throw new ToolException("path 不是合法路径: " + e.getReason());
        }
        if (!Files.isRegularFile(file)) {
            throw new ToolException("文件不存在或不是普通文件: " + file);
        }
        byte[] snapshot = FileChange.snapshot(file);
        if (snapshot == null) {
            throw new ToolException("文件已不存在: " + file);
        }
        String original = FileChange.text(snapshot);
        int start = original.indexOf(oldText);
        if (start < 0) {
            throw new ToolException("未找到 old_text 的精确匹配，文件未修改；请重新读取并检查空白和换行");
        }
        if (original.indexOf(oldText, start + 1) >= 0) {
            throw new ToolException("old_text 存在多处匹配，文件未修改；请增加定位上下文");
        }
        String updated = original.substring(0, start) + newText + original.substring(start + oldText.length());
        return FileChange.prepare(file, snapshot, updated, "已修改");
    }

    private String stringArg(JsonNode args, String name) {
        JsonNode value = args.get(name);
        if (value == null || !value.isTextual()) {
            throw new ToolException(name + " 必须显式提供字符串");
        }
        return value.textValue();
    }
}
