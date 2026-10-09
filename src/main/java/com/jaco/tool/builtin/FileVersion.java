package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.tool.ToolException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 以完整原始字节的 SHA-256 标识文件内容版本，不依赖时间戳或大小。 */
final class FileVersion {
    private FileVersion() {
    }

    static String of(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 SHA-256", e);
        }
    }

    static void verify(JsonNode args, byte[] snapshot) {
        JsonNode version = args.get("expected_version");
        if (version == null && snapshot == null) {
            return; // 新建文件可以省略版本，执行前仍检查目标没有出现。
        }
        if (version == null || !version.isTextual() || !version.textValue().matches("sha256:[0-9a-f]{64}")) {
            throw new ToolException("修改已有文件必须提供 read_file 返回的 expected_version（sha256: 加 64 位小写十六进制）；请重新读取");
        }
        if (snapshot == null || !version.textValue().equals(of(snapshot))) {
            throw new ToolException("文件自读取后已变化或已删除，版本不匹配，拒绝修改；请重新 read_file 并使用最新版本");
        }
    }
}
