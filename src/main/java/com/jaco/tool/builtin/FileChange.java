package com.jaco.tool.builtin;

import com.jaco.tool.PreparedToolCall;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 文件原文快照、修改预览和确认后执行共享一份计划。 */
final class FileChange {
    private FileChange() {
    }

    static byte[] snapshot(Path file) throws IOException {
        return Files.exists(file) ? Files.readAllBytes(file) : null;
    }

    static String text(byte[] bytes) throws IOException {
        return bytes == null ? "" : StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    static PreparedToolCall prepare(Path file, byte[] original, String updated, String operation) throws IOException {
        String before = text(original);
        String preview = FileDiff.render(file.toString(), original == null, before, updated);
        return new PreparedToolCall(preview, () -> {
            AtomicFileWriter.checkUnchanged(file, original);
            if (original != null && before.equals(updated)) {
                return "无需修改 " + file + "（内容相同）";
            }
            long size = AtomicFileWriter.write(file, updated, original);
            return operation + " " + file + "（" + (original == null ? 0 : original.length)
                    + " 字节 → " + size + " 字节）";
        });
    }
}
