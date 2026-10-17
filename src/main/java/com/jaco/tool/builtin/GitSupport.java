package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 仅允许固定 Git 子命令；原样参数列表启动，不经过 shell。 */
final class GitSupport {
    private final RunCommandTool runner = new RunCommandTool();

    Path directory(JsonNode args, ToolContext ctx) {
        Path path = ctx.sandbox().resolve(CommandArguments.string(args, "repo_path", "."));
        if (!Files.isDirectory(path)) {
            throw new ToolException("repo_path 不存在或不是目录: " + path);
        }
        return path;
    }

    Path root(Path directory, ToolContext ctx) throws Exception {
        RunCommandTool.Result result = execute(directory, List.of("rev-parse", "--show-toplevel"), ctx, 32768, false);
        requireSuccess(result);
        String text = result.output().prefix();
        if (result.output().length() != text.length() || !text.endsWith("\n")) {
            throw new ToolException("Git 仓库根路径输出不完整");
        }
        text = text.substring(0, text.length() - 1);
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") && text.endsWith("\r")) {
            text = text.substring(0, text.length() - 1);
        }
        Path root = ctx.sandbox().resolve(text);
        if (!Files.isDirectory(root)) {
            throw new ToolException("Git 仓库根路径不存在或不是目录");
        }
        return root;
    }

    List<String> paths(JsonNode args, Path root, ToolContext ctx) {
        validatePaths(args);
        JsonNode paths = args.get("paths");
        List<String> result = new ArrayList<>();
        if (paths == null) return result;
        for (JsonNode path : paths) {
            Path relative = Path.of(path.textValue());
            Path target = root.resolve(relative).normalize();
            if (relative.isAbsolute() || !target.startsWith(root)
                    || !ctx.sandbox().resolve(target.toString()).startsWith(root)) {
                throw new ToolException("paths 必须位于当前仓库内，并相对于仓库根目录: " + path.textValue());
            }
            String value = root.relativize(target).toString().replace('\\', '/');
            result.add(value.isEmpty() ? "." : value);
        }
        return result;
    }

    void validatePaths(JsonNode args) {
        JsonNode paths = args.get("paths");
        if (paths == null) return;
        if (!paths.isArray() || paths.size() > 100) {
            throw new ToolException("paths 必须是最多 100 项的字符串数组");
        }
        for (JsonNode path : paths) {
            if (!path.isTextual() || path.textValue().isBlank()) {
                throw new ToolException("paths 中的路径必须是非空白字符串");
            }
        }
    }

    boolean bool(JsonNode args, String field, boolean fallback) {
        JsonNode value = args.get(field);
        if (value == null) return fallback;
        if (!value.isBoolean()) throw new ToolException(field + " 必须是布尔值");
        return value.booleanValue();
    }

    RunCommandTool.Result execute(Path cwd, List<String> arguments, ToolContext ctx, int prefixLimit, boolean archive)
            throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "--no-pager", "--no-optional-locks", "--no-replace-objects",
                "--literal-pathspecs", "-c", "color.ui=false", "-c", "core.quotePath=false", "-c", "core.fsmonitor=false"));
        if (arguments.getFirst().equals("status") || arguments.getFirst().equals("diff")) {
            // diff/status 在检查工作区时也可能调用 clean/process 过滤器，不能只禁用 textconv。
            RunCommandTool.Result filters = execute(cwd, List.of("config", "--null", "--name-only", "--get-regexp",
                    "^filter\\..*\\.(clean|process|required)$"), ctx, 200000, false);
            if (!filters.status().equals("exited") || filters.output().readIncomplete() || filters.exitCode() == null
                    || (filters.exitCode() != 0 && filters.exitCode() != 1)) {
                requireSuccess(filters);
            }
            String raw = filters.output().prefix();
            if (raw.length() != filters.output().length() || (!raw.isEmpty() && !raw.endsWith("\0"))) {
                throw new ToolException("Git 过滤器配置输出不完整，已拒绝执行查询");
            }
            var drivers = new LinkedHashSet<String>();
            for (String key : raw.split("\0")) {
                if (key.isEmpty()) continue;
                int end = key.lastIndexOf('.');
                if (!key.startsWith("filter.") || end <= 7) {
                    throw new ToolException("无法识别 Git 过滤器配置");
                }
                drivers.add(key.substring(7, end));
            }
            if (drivers.size() > 256) throw new ToolException("Git 过滤器过多，已拒绝执行查询");
            for (String driver : drivers) {
                for (String setting : List.of("clean=", "process=", "required=false")) {
                    command.add("-c");
                    command.add("filter." + driver + "." + setting);
                }
            }
        }
        command.addAll(arguments);
        Map<String, String> environment = new LinkedHashMap<>();
        // 防止继承环境把命令重定向至其他仓库、执行自定义 diff 或写入 trace 文件。
        System.getenv().keySet().stream().filter(key -> key.toUpperCase(Locale.ROOT).startsWith("GIT_"))
                .forEach(key -> environment.put(key, null));
        environment.put("GIT_OPTIONAL_LOCKS", "0");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("LC_ALL", "C");
        environment.put("LANG", "C");
        return runner.run(new RunCommandTool.Options("git " + arguments.getFirst(), cwd, 30, StandardCharsets.UTF_8),
                ctx, new CommandOutput(archive ? ctx.archiveFile() : null, archive, prefixLimit), command, environment);
    }

    void requireSuccess(RunCommandTool.Result result) {
        if (!result.status().equals("exited") || result.output().readIncomplete() || result.exitCode() == null || result.exitCode() != 0) {
            String hint = result.status().equals("start_failed") ? "请确认已安装 Git 且可通过 PATH 启动。\n" : "";
            throw new ToolException(hint + result.render());
        }
    }

    String header(Path root, RunCommandTool.Result result) {
        return "status: " + result.status() + "\nexit_code: " + result.exitCode() + "\nrepo: " + root
                + "\nduration_ms: " + result.durationMs()
                + (result.warning().isBlank() ? "" : "\nwarning: " + result.warning()) + "\n";
    }
}
