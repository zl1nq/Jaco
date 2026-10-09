package com.jaco.tool.builtin;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;

/** 文件写入工具共用的同目录临时文件与原子替换实现。 */
final class AtomicFileWriter {

    private AtomicFileWriter() {
    }

    static long write(Path file, String content, byte[] expected) throws IOException {
        boolean existed = Files.exists(file);
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".jaco-write-", ".tmp");
        try {
            Files.writeString(temporary, content);
            if (existed && Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
                Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(file));
            }
            long newSize = Files.size(temporary);
            checkUnchanged(file, expected);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("文件系统不支持原子替换，未覆盖目标文件: " + file, e);
            }
            return newSize;
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            throw e;
        }
    }

    static void checkUnchanged(Path file, byte[] expected) throws IOException {
        boolean exists = Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (expected == null ? exists : !exists || !Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || !java.util.Arrays.equals(expected, Files.readAllBytes(file))) {
            throw new com.jaco.tool.ToolException("文件在预览后发生变化，已拒绝写入；请重新读取并生成修改: " + file);
        }
    }
}
