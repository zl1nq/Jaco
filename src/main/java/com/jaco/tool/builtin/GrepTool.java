package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.tool.JsonSchema;
import com.jaco.tool.Tool;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/** 在文件内容中正则搜索（目录则递归），跳过二进制文件。 */
public final class GrepTool implements Tool {

    private static final int MAX_MATCHES = 100;
    private static final int MAX_LINE_DISPLAY = 200;

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description() {
        return "用正则表达式搜索文件内容，输出 相对路径:行号:内容。path 为文件或目录（目录递归），include 按文件名过滤。";
    }

    @Override
    public JsonNode schema() {
        return JsonSchema.object()
                .string("pattern", "正则表达式（Java 语法）")
                .string("path", "搜索的文件或目录，默认工作目录")
                .string("include", "文件名 glob 过滤，如 *.java")
                .required("pattern")
                .build();
    }

    @Override
    public String summary(JsonNode args) {
        return "/" + args.path("pattern").asText("?") + "/ in " + args.path("path").asText(".");
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws IOException {
        Pattern pattern;
        try {
            pattern = Pattern.compile(args.path("pattern").asText());
        } catch (PatternSyntaxException e) {
            throw new ToolException("正则表达式无效: " + e.getMessage());
        }
        Path root = ctx.sandbox().resolve(args.path("path").asText("."));
        String include = args.path("include").asText(null);
        PathMatcher matcher = include == null ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + include);

        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> matcher == null || matcher.matches(p.getFileName()))
                    .forEach(p -> searchFile(p, root, pattern, hits));
        }
        if (hits.isEmpty()) {
            return "(no matches)";
        }
        String note = hits.size() >= MAX_MATCHES ? "\n(已达 " + MAX_MATCHES + " 条上限，请收窄 pattern 或 include)" : "";
        return String.join("\n", hits) + note;
    }

    private void searchFile(Path file, Path root, Pattern pattern, List<String> hits) {
        if (hits.size() >= MAX_MATCHES) {
            return;
        }
        if (isBinary(file)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String rel = root.relativize(file).toString();
            for (int i = 0; i < lines.size() && hits.size() < MAX_MATCHES; i++) {
                if (pattern.matcher(lines.get(i)).find()) {
                    String text = lines.get(i);
                    if (text.length() > MAX_LINE_DISPLAY) {
                        text = text.substring(0, MAX_LINE_DISPLAY) + "…";
                    }
                    hits.add(rel + ":" + (i + 1) + ": " + text);
                }
            }
        } catch (IOException ignored) {
            // 不可读的文件跳过不报错
        }
    }

    private boolean isBinary(Path file) {
        try (var in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(8192);
            for (byte b : head) {
                if (b == 0) {
                    return true;
                }
            }
        } catch (IOException ignored) {
        }
        return false;
    }
}
