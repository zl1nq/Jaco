package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaco.tool.JsonSchema;
import com.jaco.tool.Tool;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;

/** 严格校验参数后，通过同目录临时文件原子覆盖或新建 UTF-8 文件。 */
public final class WriteFileTool implements Tool {

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "把 content 完整写入文件（整文件覆盖或新建，父目录自动创建，UTF-8 原子写入）。"
                + "path 必须是非空白字符串，content 必须显式提供字符串（可为空）。修改前应先 read_file 了解现状。";
    }

    @Override
    public JsonNode schema() {
        ObjectNode schema = (ObjectNode) JsonSchema.object()
                .string("path", "目标文件路径（相对于工作目录或绝对路径）")
                .string("content", "要写入的完整内容（UTF-8 文本）")
                .required("path", "content")
                .build();
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public String summary(JsonNode args) {
        return args.path("path").asText("?");
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws IOException {
        if (args == null || !args.isObject()) {
            throw new ToolException("write_file 参数必须是 JSON 对象");
        }
        JsonNode path = args.get("path");
        if (path == null || !path.isTextual() || path.textValue().isBlank()) {
            throw new ToolException("path 必须显式提供非空白字符串");
        }
        JsonNode contentNode = args.get("content");
        if (contentNode == null || !contentNode.isTextual()) {
            throw new ToolException("content 必须显式提供字符串；写入空文件请传空字符串");
        }
        var names = args.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!name.equals("path") && !name.equals("content")) {
                throw new ToolException("未知的 write_file 参数: " + name);
            }
        }
        Path file;
        try {
            file = ctx.sandbox().resolve(path.textValue());
        } catch (InvalidPathException e) {
            throw new ToolException("path 不是合法路径: " + e.getReason());
        }
        if (Files.isDirectory(file)) {
            throw new ToolException("目标是目录: " + file);
        }
        String content = contentNode.textValue();
        boolean existed = Files.exists(file);
        if (existed && !Files.isRegularFile(file)) {
            throw new ToolException("目标不是普通文件: " + file);
        }
        long oldSize = existed ? Files.size(file) : -1;
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".jaco-write-", ".tmp");
        long newSize;
        try {
            Files.writeString(temporary, content);
            // 替换文件会改变 inode，保留已有文件的 POSIX 权限（尤其是可执行位）。
            if (existed && Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
                Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(file));
            }
            newSize = Files.size(temporary);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("文件系统不支持原子替换，未覆盖目标文件: " + file, e);
            }
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            throw e;
        }
        if (existed) {
            return "已覆盖 " + file + "（" + oldSize + " 字节 → " + newSize + " 字节）";
        }
        return "已创建 " + file + "（" + newSize + " 字节）";
    }
}
