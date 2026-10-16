package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaco.tool.JsonSchema;
import com.jaco.tool.PreparedToolCall;
import com.jaco.tool.ProcessTree;
import com.jaco.tool.Tool;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 非交互命令执行：有界输出、可配置超时、取消与进程树清理。 */
public final class RunCommandTool implements Tool {
    private volatile String autoShell;

    @Override
    public String name() {
        return "run_command";
    }

    @Override
    public String description() {
        return "执行非交互 shell 命令，标准输入关闭，stdout/stderr 合并。cwd 默认工作目录，启动目录受路径沙箱约束，"
                + "但命令进程本身没有系统级沙箱。timeout_seconds 默认 120，范围 1-600。"
                + "encoding 默认 JVM 字符集，Windows 旧程序输出可显式指定 GBK 等编码。"
                + "返回状态、退出码、耗时和末尾最多 50000 字符；更大的输出最多归档前 2000000 字符，可用 recall 读取。"
                + "超时、取消和执行结束会尽力清理子进程；不要用于后台服务或交互程序。";
    }

    @Override
    public JsonNode schema() {
        ObjectNode schema = (ObjectNode) JsonSchema.object()
                .string("command", "要执行的非空白命令行")
                .string("cwd", "启动目录，默认工作目录，必须位于允许的目录内")
                .integer("timeout_seconds", "超时秒数，默认 120，范围 1-600")
                .string("encoding", "输出字符集，如 UTF-8、GBK，默认 JVM 字符集；不会改变子进程的输出编码")
                .required("command").build();
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public String summary(JsonNode args) {
        String cmd = args.path("command").asText("?");
        return cmd.length() > 80 ? cmd.substring(0, 80) + "…" : cmd;
    }

    private record Options(String command, Path cwd, int timeout, Charset encoding) {
    }

    @Override
    public PreparedToolCall prepare(JsonNode args, ToolContext ctx) {
        if (args == null || !args.isObject()) {
            throw new ToolException("run_command 参数必须是 JSON 对象");
        }
        Set<String> allowed = Set.of("command", "cwd", "timeout_seconds", "encoding");
        args.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new ToolException("未知的 run_command 参数: " + field);
            }
        });
        String command = string(args, "command", null);
        if (command == null) {
            throw new ToolException("command 必须提供非空白字符串");
        }
        Path cwd = ctx.sandbox().resolve(string(args, "cwd", "."));
        checkDirectory(cwd);
        int timeout = 120;
        JsonNode seconds = args.get("timeout_seconds");
        if (seconds != null) {
            if (!seconds.isIntegralNumber() || !seconds.canConvertToInt() || seconds.intValue() < 1 || seconds.intValue() > 600) {
                throw new ToolException("timeout_seconds 必须是 1 到 600 的整数");
            }
            timeout = seconds.intValue();
        }
        Charset encoding;
        try {
            encoding = Charset.forName(string(args, "encoding", Charset.defaultCharset().name()));
        } catch (IllegalArgumentException e) {
            throw new ToolException("encoding 不是支持的字符集");
        }
        Options options = new Options(command, cwd, timeout, encoding);
        return new PreparedToolCall(null, () -> run(options, ctx));
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws Exception {
        return prepare(args, ctx).execute();
    }

    private String string(JsonNode args, String field, String fallback) {
        JsonNode value = args.get(field);
        if (value == null) {
            return fallback;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new ToolException(field + " 必须是非空白字符串");
        }
        return value.textValue();
    }

    private void checkDirectory(Path cwd) {
        if (!Files.isDirectory(cwd)) {
            throw new ToolException("cwd 不存在或不是目录: " + cwd);
        }
    }

    private String run(Options options, ToolContext ctx) throws Exception {
        long start = System.nanoTime();
        CommandOutput output = new CommandOutput(ctx.archiveFile());
        String status = "cancelled";
        Integer exitCode = null;
        String warning = "";
        Process process = null;
        Thread reader = null;
        boolean interrupted = false;
        Map<Long, ProcessHandle> descendants = new LinkedHashMap<>();
        try {
            if (!cancelled(ctx)) {
                List<String> command = shellPrefix(ctx.shellChoice());
                command.add(options.command());
                // 审批期间目录可能改变，启动前再次检查。
                Path cwd = ctx.sandbox().resolve(options.cwd().toString());
                checkDirectory(cwd);
                if (!cancelled(ctx)) {
                    process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
                    ctx.currentProcess().set(process);
                    process.getOutputStream().close();
                    Process running = process;
                    reader = new Thread(() -> drain(running, output, options.encoding()), "jaco-command-output-" + process.pid());
                    reader.setDaemon(true);
                    reader.start();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(options.timeout());
                    while (true) {
                        for (ProcessHandle child : ProcessTree.descendants(process)) {
                            descendants.put(child.pid(), child);
                        }
                        if (cancelled(ctx)) {
                            status = "cancelled";
                            break;
                        }
                        if (process.waitFor(50, TimeUnit.MILLISECONDS)) {
                            status = cancelled(ctx) ? "cancelled" : "exited";
                            exitCode = status.equals("exited") ? process.exitValue() : null;
                            break;
                        }
                        if (System.nanoTime() >= deadline) {
                            status = "timed_out";
                            break;
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            interrupted = true;
            status = "cancelled";
        } catch (IOException e) {
            status = process == null ? "start_failed" : "execution_failed";
            warning = "命令执行失败: " + e.getMessage();
        } finally {
            // 清理阶段暂时清除中断，以便等待进程及读取线程退出，结束后恢复中断标记。
            interrupted |= Thread.interrupted();
            if (process != null) {
                for (ProcessHandle child : ProcessTree.descendants(process)) {
                    descendants.put(child.pid(), child);
                }
                ProcessTree.terminate(descendants.values());
                ProcessTree.terminate(process);
                long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while ((process.isAlive() || descendants.values().stream().anyMatch(ProcessHandle::isAlive))
                        && System.nanoTime() < cleanupDeadline) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(20);
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                boolean alive = process.isAlive() || descendants.values().stream().anyMatch(ProcessHandle::isAlive);
                if (alive) {
                    warning += "\n部分进程未能确认退出";
                }
                if (reader != null) {
                    interrupted |= joinReader(reader, 2000);
                    if (reader.isAlive()) {
                        // 某些平台的 close 会等待正在阻塞的 read，不能让清理无限阻塞执行线程。
                        Process closing = process;
                        Thread closer = new Thread(() -> {
                            try {
                                closing.getInputStream().close();
                            } catch (IOException ignored) {
                                // 下面统一报告输出未正常结束。
                            }
                        }, "jaco-command-output-close-" + process.pid());
                        closer.setDaemon(true);
                        closer.start();
                        interrupted |= joinReader(reader, 1000);
                        output.readFailed(new IOException("输出读取未正常结束"));
                        warning += "\n输出读取未正常结束，预览可能不完整";
                    }
                    // 若操作系统无法及时解除阻塞，也停止接受后续输出，避免返回后继续改写归档。
                    output.finish();
                }
                ctx.currentProcess().compareAndSet(process, null);
            }
            if (ctx.cancelled().getAsBoolean() || interrupted) {
                status = "cancelled";
                exitCode = null;
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return "status: " + status + "\nexit_code: " + (exitCode == null ? "null" : exitCode)
                + "\nduration_ms: " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                + "\ncwd: " + options.cwd() + "\nencoding: " + options.encoding().name()
                + (warning.isBlank() ? "" : "\nwarning: " + warning.strip()) + "\n" + output.render();
    }

    private boolean cancelled(ToolContext ctx) {
        return Thread.currentThread().isInterrupted() || ctx.cancelled().getAsBoolean();
    }

    /** 清理不能因第二次取消而提前跳过；中断标记交给调用方在清理结束后恢复。 */
    private boolean joinReader(Thread reader, long millis) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (reader.isAlive()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                TimeUnit.NANOSECONDS.timedJoin(reader, remaining);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    private void drain(Process process, CommandOutput output, Charset encoding) {
        try (var reader = new InputStreamReader(process.getInputStream(), encoding)) {
            char[] chars = new char[4096];
            int length;
            while ((length = reader.read(chars)) != -1) {
                if (!output.append(chars, length)) {
                    break;
                }
            }
        } catch (IOException e) {
            output.readFailed(e);
        } finally {
            output.finish();
        }
    }

    private List<String> shellPrefix(String choice) throws InterruptedException {
        String shell = choice == null ? "auto" : choice.toLowerCase(Locale.ROOT);
        if (shell.equals("auto")) {
            if (autoShell == null) {
                autoShell = isWindows() && !bashAvailable() ? "cmd" : "bash";
            }
            shell = autoShell;
        }
        return switch (shell) {
            case "cmd" -> new ArrayList<>(List.of("cmd", "/d", "/c"));
            case "powershell" -> new ArrayList<>(List.of("powershell", "-NoProfile", "-NonInteractive", "-Command"));
            case "bash" -> new ArrayList<>(List.of("bash", "-c"));
            default -> throw new ToolException("不支持的 shell: " + shell);
        };
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private boolean bashAvailable() throws InterruptedException {
        Process probe = null;
        try {
            probe = new ProcessBuilder("bash", "-c", "true").start();
            return probe.waitFor(2, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } finally {
            if (probe != null) {
                ProcessTree.terminate(probe);
                try {
                    probe.getInputStream().close();
                    probe.getErrorStream().close();
                    probe.getOutputStream().close();
                } catch (IOException ignored) {
                    // 探测进程已终止，不影响 shell 选择。
                }
            }
        }
    }
}
