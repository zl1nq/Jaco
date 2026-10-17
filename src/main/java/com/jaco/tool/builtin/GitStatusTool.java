package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaco.tool.JsonSchema;
import com.jaco.tool.PreparedToolCall;
import com.jaco.tool.Tool;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GitStatusTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CAPTURE_LIMIT = 200_000;
    private final GitSupport git = new GitSupport();

    @Override
    public String name() {
        return "git_status";
    }

    @Override
    public String description() {
        return "只读查看 Git 状态，返回分支描述和 JSON 记录（index_status 为暂存区状态、worktree_status 为工作区状态）。"
                + "repo_path 默认工作目录，仓库根必须在允许目录内。paths 是相对仓库根的字面路径，不支持 glob。"
                + "默认包含未跟踪文件；重命名记录包含 original_path。忽略文件和子模块内部修改不展示。"
                + "结果受 max_entries、输出捕获和回填预算限制，截断会明确提示，可缩小 paths 或通过 recall 读取归档。";
    }

    @Override
    public JsonNode schema() {
        ObjectNode schema = (ObjectNode) JsonSchema.object()
                .string("repo_path", "仓库或其子目录，默认工作目录")
                .strings("paths", "可选，最多 100 个相对仓库根的字面路径")
                .bool("include_untracked", "是否包含未跟踪文件，默认 true")
                .integer("max_entries", "最多返回状态记录数，默认 200，范围 1-1000").build();
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public String summary(JsonNode args) {
        return args.path("repo_path").asText(".");
    }

    @Override
    public PreparedToolCall prepare(JsonNode args, ToolContext ctx) {
        CommandArguments.validate(args, name(), "repo_path", "paths", "include_untracked", "max_entries");
        Path directory = git.directory(args, ctx);
        git.validatePaths(args);
        boolean untracked = git.bool(args, "include_untracked", true);
        int max = (int) CommandArguments.number(args, "max_entries", 200, 1, 1000);
        JsonNode snapshot = args.deepCopy();
        return new PreparedToolCall(null, () -> {
            Path root = git.root(directory, ctx);
            List<String> command = new ArrayList<>(List.of("status", "--porcelain=v1", "-z", "--branch",
                    "--ignore-submodules=all", untracked ? "--untracked-files=all" : "--untracked-files=no", "--"));
            command.addAll(git.paths(snapshot, root, ctx));
            RunCommandTool.Result result = git.execute(root, command, ctx, CAPTURE_LIMIT, true);
            git.requireSuccess(result);
            return format(root, result, max, ctx.maxToolResultChars());
        });
    }

    private String format(Path root, RunCommandTool.Result result, int max, int resultBudget) throws Exception {
        String raw = result.output().prefix();
        int cursor = 0;
        String branch = "";
        List<String> entries = new ArrayList<>();
        boolean truncated = result.output().length() > raw.length();
        while (cursor < raw.length()) {
            int end = raw.indexOf('\0', cursor);
            if (end < 0) {
                truncated = true;
                break;
            }
            String token = raw.substring(cursor, end);
            cursor = end + 1;
            if (token.startsWith("## ") && branch.isEmpty()) {
                branch = token.substring(3);
                continue;
            }
            if (token.length() < 4 || token.charAt(2) != ' ') {
                throw new ToolException("Git 状态输出含诊断信息或不完整记录，不能判定工作区状态");
            }
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("index_status", token.substring(0, 1));
            entry.put("worktree_status", token.substring(1, 2));
            entry.put("path", token.substring(3));
            if (token.substring(0, 2).matches(".*[RC].*")) {
                int originalEnd = raw.indexOf('\0', cursor);
                if (originalEnd < 0) {
                    truncated = true;
                    break;
                }
                entry.put("original_path", raw.substring(cursor, originalEnd));
                cursor = originalEnd + 1;
            }
            if (entries.size() >= max) {
                truncated = true;
                break;
            }
            entries.add(MAPPER.writeValueAsString(entry));
        }
        String header = git.header(root, result) + "branch: " + MAPPER.writeValueAsString(branch) + "\n"
                + result.output().metadata();
        int budget = resultBudget >= 1000 ? resultBudget : 8000;
        StringBuilder rows = new StringBuilder();
        int count = 0;
        for (String entry : entries) {
            if (header.length() + rows.length() + entry.length() > budget - 200) {
                truncated = true;
                break;
            }
            rows.append(entry).append('\n');
            count++;
        }
        return header + "entries_returned: " + count + "\nclean: " + (entries.isEmpty() && !truncated)
                + "\nstatus_truncated: " + truncated
                + (truncated ? "\n(状态列表不完整，请缩小 paths，或用 recall 检索 archive_id 读取原始 Git 输出)" : "")
                + "\n\n" + (rows.isEmpty() ? "(no entries returned)" : rows.toString());
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws Exception {
        return prepare(args, ctx).execute();
    }
}
