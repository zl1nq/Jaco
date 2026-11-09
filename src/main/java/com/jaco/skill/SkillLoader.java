package com.jaco.skill;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

public final class SkillLoader {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION));
    private static final Set<String> FIELDS = Set.of("name", "description", "argument-hint",
            "user-invocable", "disable-model-invocation");
    public static final int MAX_BYTES = 128 * 1024;

    public SkillDefinition load(String source, String directoryName, Path sourceRoot, Path directory) throws IOException {
        Path root = directory.toRealPath();
        if (!root.startsWith(sourceRoot.toRealPath())) throw new IOException("技能目录越界");
        Path file = root.resolve("SKILL.md").toRealPath();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) throw new IOException("SKILL.md 不是包内普通文件");
        byte[] bytes;
        try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) throw new IOException("SKILL.md 超过 128 KiB");
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (!text.startsWith("---\n")) throw new IOException("缺少 YAML frontmatter");
        int end = text.indexOf("\n---\n", 4);
        if (end < 0) throw new IOException("缺少 frontmatter 结束标记或正文");
        JsonNode metadata;
        try (var parser = YAML.createParser(text.substring(4, end))) {
            metadata = YAML.readTree(parser);
            if (parser.nextToken() != null) throw new IOException("不支持多个 YAML 文档");
        }
        if (metadata == null || !metadata.isObject()) throw new IOException("frontmatter 必须是对象");
        var names = metadata.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            if (!FIELDS.contains(field)) throw new IOException("不支持的技能字段: " + field);
        }
        String name = text(metadata, "name", true);
        if (!name.matches("[a-z0-9-]{1,64}") || !name.equals(directoryName))
            throw new IOException("name 须与目录名一致，且为 1～64 位小写字母、数字或连字符");
        String description = text(metadata, "description", true);
        if (description.length() > 1000) throw new IOException("description 超过 1000 字符");
        String body = text.substring(end + 5);
        if (body.isBlank() || body.length() > 20000) throw new IOException("正文须非空且不超过 20000 个 UTF-16 字符");
        return new SkillDefinition(source + ":" + name, name, description, text(metadata, "argument-hint", false),
                bool(metadata, "user-invocable", true), bool(metadata, "disable-model-invocation", false),
                root, file, body, digest(bytes));
    }
    private static String text(JsonNode object, String key, boolean required) throws IOException {
        JsonNode value = object.get(key);
        if (value == null && !required) return "";
        if (value == null || !value.isTextual() || required && value.textValue().isBlank())
            throw new IOException(key + " 必须为" + (required ? "非空" : "") + "字符串");
        return value.textValue();
    }
    private static boolean bool(JsonNode object, String key, boolean fallback) throws IOException {
        JsonNode value = object.get(key);
        if (value == null) return fallback;
        if (!value.isBoolean()) throw new IOException(key + " 必须为布尔值");
        return value.booleanValue();
    }
    private static String digest(byte[] bytes) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
