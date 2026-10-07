package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.tool.JsonSchema;
import com.jaco.tool.Tool;
import com.jaco.tool.ToolContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 执行 shell 命令。超时 120s 杀进程树；输出截断保留末尾 50k（尾部是最新信息）；
 * stdout/stderr 合并。安全性由权限确认闸门负责，这里只管执行。
 */
public final class RunCommandTool implements Tool {

    private static final long TIMEOUT_SECONDS = 120;
    private static final int MAX_OUTPUT_CHARS = 50_000;

    @Override
    public String name() {
        return "run_command";
    }

    @Override
    public String description() {
        return "在当前工作目录执行 shell 命令，返回 stdout+stderr（合并）与退出码。超时 120 秒。";
    }

    @Override
    public JsonNode schema() {
        return JsonSchema.object()
                .string("command", "要执行的命令行")
                .required("command")
                .build();
    }

    @Override
    public String summary(JsonNode args) {
        String cmd = args.path("command").asText("?");
        return cmd.length() > 80 ? cmd.substring(0, 80) + "…" : cmd;
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws Exception {
        String command = args.path("command").asText();
        if (command.isBlank()) {
            return "ERROR: 命令为空";
        }
        List<String> cmd = shellPrefix(ctx.shellChoice());
        cmd.add(command);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(ctx.workspaceRoot().toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        ctx.currentProcess().set(process);

        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> drain(process, output));
        reader.setDaemon(true);
        reader.start();

        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            output.append("\n(ERROR: 超过 ").append(TIMEOUT_SECONDS)
                    .append(" 秒未结束，进程已终止)");
        }
        reader.join(2000);

        String text = output.toString();
        if (text.length() > MAX_OUTPUT_CHARS) {
            text = "(输出过大，仅保留末尾 " + MAX_OUTPUT_CHARS + " 字符)\n"
                    + text.substring(text.length() - MAX_OUTPUT_CHARS);
        }
        if (text.isBlank()) {
            text = "(no output)";
        }
        return "(exit code " + (finished ? process.exitValue() : -1) + ")\n" + text;
    }

    /** 输出编码：Windows 下 bash/cmd 的控制台输出多为 GBK，UTF-8 环境则按 UTF-8。 */
    private void drain(Process process, StringBuilder output) {
        Charset charset = System.getProperty("os.name").toLowerCase().contains("win")
                ? Charset.defaultCharset()
                : StandardCharsets.UTF_8;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), charset))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) >= 0) {
                output.append(buf, 0, n);
            }
        } catch (Exception ignored) {
            // 进程被杀时流会异常关闭，已有内容仍然有效
        }
    }

    private List<String> shellPrefix(String choice) {
        String shell = choice == null ? "auto" : choice.toLowerCase();
        String os = System.getProperty("os.name").toLowerCase();
        if (shell.equals("cmd") || (shell.equals("auto") && os.contains("win") && !bashAvailable())) {
            return new ArrayList<>(List.of("cmd", "/c"));
        }
        if (shell.equals("powershell")) {
            return new ArrayList<>(List.of("powershell", "-NoProfile", "-Command"));
        }
        return new ArrayList<>(List.of("bash", "-c"));
    }

    private boolean bashAvailable() {
        try {
            Process p = new ProcessBuilder("bash", "-c", "true").start();
            if (p.waitFor(2, TimeUnit.SECONDS)) {
                return p.exitValue() == 0;
            }
            p.destroyForcibly();
        } catch (Exception ignored) {
        }
        return false;
    }
}
