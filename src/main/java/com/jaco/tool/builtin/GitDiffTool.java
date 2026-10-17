package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaco.tool.JsonSchema;
import com.jaco.tool.PreparedToolCall;
import com.jaco.tool.Tool;
import com.jaco.tool.ToolContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class GitDiffTool implements Tool {
    private final GitSupport git = new GitSupport();

    @Override
    public String name() {
        return "git_diff";
    }

    @Override
    public String description() {
        return "只读查看 Git 补丁。默认比较工作区与暂存区；staged=true 比较暂存区与 HEAD（无首次提交时也支持）。"
                + "不包含未跟踪文件，忽略子模块内部修改。repo_path 默认工作目录；paths 是相对仓库根的字面路径，不支持 glob。"
                + "禁用分页器、外部 diff 和文本转换；补丁预览受 max_chars 和工具回填预算限制，完整有界输出通过 archive_id 用 recall 读取。";
    }

    @Override
    public JsonNode schema() {
        ObjectNode schema = (ObjectNode) JsonSchema.object()
                .string("repo_path", "仓库或其子目录，默认工作目录")
                .strings("paths", "可选，最多 100 个相对仓库根的字面路径")
                .bool("staged", "是否查看暂存区差异，默认 false")
                .integer("context_lines", "补丁上下文行数，默认 3，范围 0-20")
                .integer("max_chars", "最多预览的补丁字符数，默认 8000，范围 1000-50000，也受工具回填预算限制").build();
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public String summary(JsonNode args) {
        return args.path("repo_path").asText(".") + (args.path("staged").asBoolean(false) ? " (staged)" : " (worktree)");
    }

    @Override
    public PreparedToolCall prepare(JsonNode args, ToolContext ctx) {
        CommandArguments.validate(args, name(), "repo_path", "paths", "staged", "context_lines", "max_chars");
        Path directory = git.directory(args, ctx);
        git.validatePaths(args);
        boolean staged = git.bool(args, "staged", false);
        int context = (int) CommandArguments.number(args, "context_lines", 3, 0, 20);
        int max = (int) CommandArguments.number(args, "max_chars", 8000, 1000, 50000);
        JsonNode snapshot = args.deepCopy();
        return new PreparedToolCall(null, () -> {
            Path root = git.root(directory, ctx);
            List<String> command = new ArrayList<>(List.of("diff", "--no-ext-diff", "--no-textconv", "--no-color",
                    "--no-relative", "--ignore-submodules=all", "--src-prefix=a/", "--dst-prefix=b/", "--unified=" + context));
            if (staged) command.add("--cached");
            command.add("--");
            command.addAll(git.paths(snapshot, root, ctx));
            RunCommandTool.Result result = git.execute(root, command, ctx, max + 1, true);
            git.requireSuccess(result);
            String header = git.header(root, result) + "comparison: " + (staged ? "staged" : "worktree") + "\n"
                    + result.output().metadata();
            int budget = ctx.maxToolResultChars() >= 1000 ? ctx.maxToolResultChars() : 8000;
            int keep = Math.min(max, Math.max(0, budget - header.length() - 160));
            String text = result.output().prefix();
            int end = Math.min(keep, text.length());
            if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            boolean truncated = result.output().length() > end;
            return header + "diff_truncated: " + truncated
                    + (truncated ? "\n(补丁预览已截短，请缩小 paths，或用 recall 检索 archive_id 读取原文)" : "")
                    + "\n\n" + (text.isEmpty() ? "(no differences)" : text.substring(0, end));
        });
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws Exception {
        return prepare(args, ctx).execute();
    }
}
