package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.tool.JsonSchema;
import com.jaco.tool.Tool;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** 流式扫描完整 UTF-8 文件计算版本，仅保留本页内容。 */
public final class ReadFileTool implements Tool {
    static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private static final int METADATA_RESERVE = 512;
    private static final Set<String> FIELDS = Set.of("path", "offset", "limit", "column_offset", "expected_version");

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "流式读取 UTF-8 文本，返回行号和完整原始字节的 SHA-256 版本，最大 64 MiB。"
                + "输出受预算限制，按 next_line/next_column_offset 作为 offset/column_offset 续读。"
                + "后续分页及 edit_file/write_file 应携带 expected_version；长行可完整续读。";
    }

    @Override
    public JsonNode schema() {
        return JsonSchema.object()
                .string("path", "文件路径（相对于工作目录或绝对路径）")
                .integer("offset", "起始行号，从 1 开始，默认 1")
                .integer("limit", "最多读取行数，默认 2000，上限 20000；受输出预算限制")
                .integer("column_offset", "起始行内字符偏移，从 0 开始，按 UTF-16 单位；后续行从 0 开始")
                .string("expected_version", "可选：首次读取返回的 sha256: 版本，拒绝跨版本分页")
                .required("path").build();
    }

    @Override
    public String summary(JsonNode args) {
        return args.path("path").asText("?");
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws IOException {
        if (args == null || !args.isObject()) throw new ToolException("参数必须是对象");
        var fields = args.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!FIELDS.contains(field)) throw new ToolException("未知参数: " + field);
        }
        if (!args.path("path").isTextual() || args.path("path").asText().isBlank())
            throw new ToolException("path 必须是非空字符串");
        int offset = integer(args, "offset", 1, 1, Integer.MAX_VALUE);
        int limit = integer(args, "limit", 2000, 1, 20000);
        int column = integer(args, "column_offset", 0, 0, Integer.MAX_VALUE);
        String expected = null;
        if (args.has("expected_version")) {
            JsonNode node = args.get("expected_version");
            if (!node.isTextual() || !node.asText().matches("sha256:[0-9a-f]{64}"))
                throw new ToolException("expected_version 必须是 sha256: 加 64 位小写十六进制");
            expected = node.asText();
        }
        int budget = ctx.maxToolResultChars();
        if (budget < METADATA_RESERVE + 32) throw new ToolException("工具输出预算过小，无法返回文件版本和分页信息");
        checkCancelled(ctx);
        Path file = ctx.sandbox().resolve(args.path("path").asText());
        try {
            BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class);
            if (!before.isRegularFile()) throw new ToolException("路径不是普通文件: " + file);
            if (before.size() > MAX_FILE_BYTES) throw new ToolException("文件超过 64 MiB 读取上限");
            MessageDigest digest = sha256();
            Page page = new Page(offset, column, limit, budget - METADATA_RESERVE);
            int line = 1, position = 0, totalLines = 0;
            try (var input = new DigestInputStream(Files.newInputStream(file), digest);
                 var bounded = new FilterInputStream(input) {
                     private long count;
                     @Override public int read(byte[] bytes, int off, int len) throws IOException {
                         checkCancelled(ctx);
                         int n = super.read(bytes, off, len);
                         if (n > 0 && (count += n) > MAX_FILE_BYTES)
                             throw new ToolException("文件超过 64 MiB 读取上限");
                         return n;
                     }
                 };
                 var reader = new PushbackReader(new BufferedReader(new InputStreamReader(bounded,
                         StandardCharsets.UTF_8.newDecoder())), 1)) {
                boolean first = true;
                int ch;
                while ((ch = reader.read()) != -1) {
                    checkCancelled(ctx);
                    if (first) {
                        first = false;
                        if (ch == '\uFEFF') continue; // 显示移除 BOM，摘要仍包含原始字节。
                    }
                    if (ch == 0) throw new ToolException("文件包含 NUL，疑似二进制文件");
                    if (ch == '\r' || ch == '\n') {
                        page.endLine(line, position);
                        totalLines = line++;
                        position = 0;
                        if (ch == '\r') {
                            int next = reader.read();
                            if (next != -1 && next != '\n') reader.unread(next);
                        }
                    } else {
                        int codepoint = ch;
                        if (Character.isHighSurrogate((char) ch)) {
                            int low = reader.read();
                            if (low == -1 || !Character.isLowSurrogate((char) low))
                                throw new ToolException("文件包含非法 Unicode 字符");
                            codepoint = Character.toCodePoint((char) ch, (char) low);
                        }
                        page.accept(line, position, codepoint);
                        position += Character.charCount(codepoint);
                    }
                }
                if (position > 0) {
                    page.endLine(line, position);
                    totalLines = line;
                }
            }
            checkCancelled(ctx);
            BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class);
            if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !Objects.equals(before.fileKey(), after.fileKey()))
                throw new ToolException("文件在读取期间发生变化，请重新 read_file");
            String version = "sha256:" + HexFormat.of().formatHex(digest.digest());
            if (expected != null && !expected.equals(version))
                throw new ToolException("文件版本不匹配，请重新 read_file 并使用最新版本");
            if (offset > Math.max(1, totalLines))
                throw new ToolException("offset " + offset + " 超出文件行数 " + totalLines);
            if (totalLines == 0 && column != 0) throw new ToolException("空文件的 column_offset 必须为 0");
            return "文件版本: " + version + "\n文件共 " + totalLines + " 行\n"
                    + "has_more=" + page.stopped + "\n"
                    + (page.stopped ? "next_line=" + page.nextLine + ", next_column_offset=" + page.nextColumn
                    + "（分别作为 offset、column_offset，携带 expected_version 继续读取）\n" : "")
                    + (totalLines == 0 ? "(文件为空)" : page.content);
        } catch (CharacterCodingException e) {
            throw new ToolException("文件不是有效的 UTF-8 文本，请转换编码后读取");
        } catch (AccessDeniedException e) {
            throw new ToolException("没有文件读取权限: " + file);
        } catch (NoSuchFileException e) {
            throw new ToolException("文件不存在或读取期间已删除: " + file);
        }
    }

    private static int integer(JsonNode args, String field, int fallback, int min, int max) {
        if (!args.has(field)) return fallback;
        JsonNode value = args.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max)
            throw new ToolException(field + " 必须是 " + min + " 到 " + max + " 的整数");
        return value.intValue();
    }

    private static void checkCancelled(ToolContext ctx) {
        if (Thread.currentThread().isInterrupted() || ctx.cancelled().getAsBoolean())
            throw new ToolException("文件读取已取消");
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static final class Page {
        private final int offset, column, limit, budget;
        private final StringBuilder content = new StringBuilder();
        private boolean stopped, lineOpen;
        private int nextLine, nextColumn;

        private Page(int offset, int column, int limit, int budget) {
            this.offset = offset; this.column = column; this.limit = limit; this.budget = budget;
        }

        private boolean start(int line, int position, int needed) {
            if (stopped || line < offset) return false;
            if (line == offset && position < column) return false;
            String prefix = lineOpen ? "" : String.format("%6d| ", line);
            // 为本行显示换行保留一位；单行续页的换行仅为展示，不计入文件游标。
            if ((long) line >= (long) offset + limit || content.length() + prefix.length() + needed + 1 > budget) {
                stopped = true; nextLine = line; nextColumn = position;
                if (lineOpen) { content.append('\n'); lineOpen = false; }
                return false;
            }
            if (!lineOpen) { content.append(prefix); lineOpen = true; }
            return true;
        }

        private void accept(int line, int position, int codepoint) {
            int width = Character.charCount(codepoint);
            if (line == offset && position < column && column < position + width)
                throw new ToolException("column_offset 不能位于 Unicode 代理对中间");
            if (start(line, position, width)) content.appendCodePoint(codepoint);
        }

        private void endLine(int line, int length) {
            if (line == offset && column > length)
                throw new ToolException("column_offset 超出起始行长度 " + length);
            if (!stopped && start(line, length, 0)) { content.append('\n'); lineOpen = false; }
        }
    }
}
