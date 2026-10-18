package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaco.tool.JsonSchema;
import com.jaco.tool.PreparedToolCall;
import com.jaco.tool.Tool;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.MalformedInputException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** 同一原文中定位唯一、不重叠的片段，一次预览后原子替换。 */
public final class EditFileTool implements Tool {
    static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    static final int MAX_TEXT_BYTES = 1024 * 1024;
    static final int MAX_TOTAL_TEXT_BYTES = 4 * 1024 * 1024;
    static final int MAX_EDITS = 100;
    private static final Set<String> FIELDS = Set.of("path", "old_text", "new_text", "expected_version", "edits", "newline_mode");
    private record Edit(int index, String oldText, String newText) {}
    record Replacement(int index, int start, int end, String text) {}

    @Override public String name() { return "edit_file"; }

    @Override public String description() {
        return "局部修改已有 UTF-8 文件，必须先 read_file 并提供 expected_version。"
                + "使用 old_text/new_text 或 edits 数组（二选一），各项统一在原文中唯一定位，重叠或任意失败均不写入。"
                + "默认 newline_mode=exact 精确匹配；preserve 只兼容换行差异并保留原文件换行风格，混合换行拒绝。"
                + "空白、缩进须一致，不含读取行号；new_text 可为空。最多 100 项、文件 16 MiB，单段文本 1 MiB、合计 4 MiB。"
                + "先展示修改预览，确认后一次原子替换，返回新版本。";
    }

    @Override public JsonNode schema() {
        ObjectNode schema = (ObjectNode) JsonSchema.object()
                .string("path", "已有文件的非空白路径")
                .string("old_text", "单项模式：非空原文，必须唯一匹配")
                .string("new_text", "单项模式：替换文本，空字符串表示删除")
                .string("expected_version", "必填：read_file 或上次写入返回的 sha256: 版本")
                .string("newline_mode", "默认 exact；preserve 兼容换行差异并保留文件换行风格", "exact", "preserve")
                .required("path", "expected_version").build();
        ObjectNode edits = schema.withObject("/properties").putObject("edits");
        edits.put("type", "array").put("minItems", 1).put("maxItems", MAX_EDITS)
                .put("description", "批量模式：所有项在同一原文中定位，不能与顶层 old_text/new_text 混用");
        ObjectNode item = (ObjectNode) JsonSchema.object().string("old_text", "非空、唯一匹配的原文")
                .string("new_text", "替换文本，可为空").required("old_text", "new_text").build();
        item.put("additionalProperties", false);
        edits.set("items", item);
        schema.put("additionalProperties", false);
        var modes = schema.putArray("oneOf");
        var single = modes.addObject();
        single.putArray("required").add("old_text").add("new_text");
        single.putObject("not").putArray("required").add("edits");
        var batch = modes.addObject();
        batch.putArray("required").add("edits");
        var forbidden = batch.putObject("not").putArray("anyOf");
        forbidden.addObject().putArray("required").add("old_text");
        forbidden.addObject().putArray("required").add("new_text");
        return schema;
    }

    @Override public String summary(JsonNode args) { return args.path("path").asText("?"); }

    @Override public String execute(JsonNode args, ToolContext ctx) throws IOException {
        try { return prepare(args, ctx).execute(); }
        catch (IOException | RuntimeException e) { throw e; }
        catch (Exception e) { throw new IOException(e); }
    }

    @Override public PreparedToolCall prepare(JsonNode args, ToolContext ctx) throws IOException {
        Runnable check = () -> {
            if (Thread.currentThread().isInterrupted() || ctx.cancelled().getAsBoolean())
                throw new ToolException("文件修改已取消");
        };
        check.run();
        object(args, FIELDS, "edit_file");
        String path = string(args, "path");
        if (path.isBlank()) throw new ToolException("path 必须是非空白字符串");
        String mode = args.has("newline_mode") ? string(args, "newline_mode") : "exact";
        if (!mode.equals("exact") && !mode.equals("preserve")) throw new ToolException("newline_mode 只允许 exact 或 preserve");
        List<Edit> edits = arguments(args, check);
        Path file;
        try { file = ctx.sandbox().resolve(path); }
        catch (InvalidPathException e) { throw new ToolException("path 不是合法路径: " + e.getReason()); }
        try {
            if (!Files.isRegularFile(file)) {
                if (!Files.exists(file)) throw new NoSuchFileException(file.toString());
                throw new ToolException("路径不是普通文件: " + file);
            }
            byte[] snapshot = snapshot(file, check);
            FileVersion.verify(args, snapshot);
            check.run();
            String original;
            try { original = FileChange.text(snapshot); }
            catch (CharacterCodingException e) { throw new ToolException("文件不是有效的 UTF-8 文本，请转换编码后修改"); }
            if (original.indexOf('\0') >= 0) throw new ToolException("文件包含 NUL，疑似二进制文件");
            String ending = newline(original);
            if (mode.equals("preserve") && ending.equals("mixed"))
                throw new ToolException("文件包含混合换行，无法确定替换文本的换行风格；请使用 newline_mode=exact");
            String source = mode.equals("preserve") ? normalize(original) : original;
            List<Replacement> replacements = new ArrayList<>();
            for (Edit edit : edits) {
                check.run();
                String old = mode.equals("preserve") ? normalize(edit.oldText()) : edit.oldText();
                int start = source.indexOf(old);
                if (start < 0) {
                    String hint = mode.equals("exact") && normalize(original).contains(normalize(old))
                            ? "仅换行格式不同（文件为 " + EditFilePreview.ending(original) + "），可使用 newline_mode=preserve" : "请重新读取并检查空白和缩进";
                    throw new ToolException("修改项 " + edit.index() + " 未找到 old_text 的匹配，文件未修改；" + hint);
                }
                if (source.indexOf(old, start + 1) >= 0)
                    throw new ToolException("修改项 " + edit.index() + " 存在多处匹配，候选行号: "
                            + candidateLines(source, old, check) + "；请增加定位上下文，文件未修改");
                String text = mode.equals("preserve") ? normalize(edit.newText()).replace("\n", ending) : edit.newText();
                replacements.add(new Replacement(edit.index(), start, start + old.length(), text));
            }
            replacements.sort(Comparator.comparingInt(Replacement::start));
            for (int i = 1; i < replacements.size(); i++) {
                if (replacements.get(i).start() < replacements.get(i - 1).end())
                    throw new ToolException("修改项 " + replacements.get(i - 1).index() + " 与 " + replacements.get(i).index()
                            + " 的原文范围重叠，文件未修改");
            }
            if (mode.equals("preserve")) replacements = originalOffsets(original, replacements, check);
            long bytes = snapshot.length;
            for (Replacement replacement : replacements) {
                check.run();
                bytes += utf8Size(replacement.text(), check) - utf8Size(original.substring(replacement.start(), replacement.end()), check);
            }
            if (bytes > MAX_FILE_BYTES) throw new ToolException("修改后的文件超过 16 MiB 上限，文件未修改");
            StringBuilder updated = new StringBuilder();
            int position = 0;
            for (Replacement replacement : replacements) {
                check.run();
                updated.append(original, position, replacement.start()).append(replacement.text());
                position = replacement.end();
            }
            updated.append(original, position, original.length());
            String result = updated.toString();
            check.run();
            String preview = "基于文件版本: " + FileVersion.of(snapshot) + "\n"
                    + EditFilePreview.render(file.toString(), original, result, replacements, check);
            check.run();
            PreparedToolCall plan = FileChange.prepare(file, snapshot, original, result, "已修改", preview, check);
            return new PreparedToolCall(preview, () -> {
                try { return plan.execute(); }
                catch (AccessDeniedException e) { throw new ToolException("没有文件修改权限: " + file); }
                catch (NoSuchFileException e) { throw new ToolException("文件或父目录已删除，请重新读取: " + file); }
            });
        } catch (AccessDeniedException e) {
            throw new ToolException("没有文件读取或修改权限: " + file);
        } catch (NoSuchFileException e) {
            throw new ToolException("文件不存在或已删除，请重新读取: " + file);
        }
    }

    private static List<Edit> arguments(JsonNode args, Runnable check) throws IOException {
        List<Edit> edits = new ArrayList<>();
        if (args.has("edits")) {
            if (args.has("old_text") || args.has("new_text")) throw new ToolException("edits 不能与顶层 old_text/new_text 混用");
            JsonNode array = args.get("edits");
            if (!array.isArray() || array.isEmpty() || array.size() > MAX_EDITS)
                throw new ToolException("edits 必须是 1 到 100 项的数组");
            for (JsonNode item : array) {
                object(item, Set.of("old_text", "new_text"), "修改项 " + (edits.size() + 1));
                edits.add(new Edit(edits.size() + 1, string(item, "old_text"), string(item, "new_text")));
            }
        } else edits.add(new Edit(1, string(args, "old_text"), string(args, "new_text")));
        long total = 0;
        for (Edit edit : edits) {
            if (edit.oldText().isEmpty()) throw new ToolException("修改项 " + edit.index() + " 的 old_text 不能为空");
            for (String text : List.of(edit.oldText(), edit.newText())) {
                if (text.indexOf('\0') >= 0) throw new ToolException("修改项 " + edit.index() + " 的文本不能包含 NUL");
                if (text.length() > MAX_TEXT_BYTES) throw new ToolException("修改项 " + edit.index() + " 的单段文本超过 1 MiB 上限");
                long size;
                try { size = utf8Size(text, check); }
                catch (CharacterCodingException e) {
                    throw new IOException("修改项 " + edit.index() + " 的文本包含无效 Unicode 字符", e);
                }
                if (size > MAX_TEXT_BYTES) throw new ToolException("修改项 " + edit.index() + " 的单段文本超过 1 MiB 上限");
                total += size;
                if (total > MAX_TOTAL_TEXT_BYTES) throw new ToolException("修改文本合计超过 4 MiB 上限");
            }
        }
        return edits;
    }

    private static byte[] snapshot(Path file, Runnable check) throws IOException {
        if (Files.size(file) > MAX_FILE_BYTES) throw new ToolException("文件超过 16 MiB 修改上限");
        try (var input = Files.newInputStream(file); var bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                check.run();
                if (count > MAX_FILE_BYTES - bytes.size()) throw new ToolException("文件超过 16 MiB 修改上限");
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        }
    }

    private static long utf8Size(String text, Runnable check) throws IOException {
        long size = 0;
        for (int i = 0; i < text.length(); i++) {
            if ((i & 4095) == 0) check.run();
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new MalformedInputException(1);
                size += 4;
            } else if (Character.isLowSurrogate(c)) throw new MalformedInputException(1);
            else size += c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
        }
        return size;
    }

    static String normalize(String text) { return text.replace("\r\n", "\n").replace('\r', '\n'); }

    static String newline(String text) {
        boolean lf = false, crlf = false, cr = false;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') { crlf = true; i++; }
                else cr = true;
            } else if (text.charAt(i) == '\n') lf = true;
        }
        return (lf ? 1 : 0) + (crlf ? 1 : 0) + (cr ? 1 : 0) > 1 ? "mixed" : crlf ? "\r\n" : cr ? "\r" : "\n";
    }

    private static List<Replacement> originalOffsets(String original, List<Replacement> changes, Runnable check) {
        List<Replacement> mapped = new ArrayList<>();
        int raw = 0, normalized = 0;
        for (Replacement change : changes) {
            int start = 0;
            for (int target : new int[] { change.start(), change.end() }) {
                while (normalized < target) {
                    if ((raw & 4095) == 0) check.run();
                    char c = original.charAt(raw++);
                    if (c == '\r' && raw < original.length() && original.charAt(raw) == '\n') raw++;
                    normalized++;
                }
                if (target == change.start()) start = raw;
            }
            mapped.add(new Replacement(change.index(), start, raw, change.text()));
        }
        return mapped;
    }

    private static String candidateLines(String source, String pattern, Runnable check) {
        List<Integer> lines = new ArrayList<>();
        int scan = 0, line = 1, match = source.indexOf(pattern);
        while (match >= 0 && lines.size() < 8) {
            while (scan < match) {
                if ((scan & 4095) == 0) check.run();
                char c = source.charAt(scan++);
                if (c == '\r') { line++; if (scan < match && source.charAt(scan) == '\n') scan++; }
                else if (c == '\n') line++;
            }
            lines.add(line);
            check.run();
            match = source.indexOf(pattern, match + 1);
        }
        return lines + (match >= 0 ? "（仅列出前 8 个）" : "");
    }

    private static void object(JsonNode node, Set<String> fields, String label) {
        if (node == null || !node.isObject()) throw new ToolException(label + " 参数必须是 JSON 对象");
        var names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!fields.contains(name)) throw new ToolException(label + " 的未知参数: " + name);
        }
    }

    private static String string(JsonNode args, String name) {
        JsonNode value = args.get(name);
        if (value == null || !value.isTextual()) throw new ToolException(name + " 必须显式提供字符串");
        return value.textValue();
    }
}
