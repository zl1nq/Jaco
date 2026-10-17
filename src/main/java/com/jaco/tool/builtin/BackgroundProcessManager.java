package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.hook.AgentHook;
import com.jaco.tool.PreparedToolCall;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 会话内的后台任务管理器；与前台命令共用执行、归档及进程树清理。 */
public final class BackgroundProcessManager implements AgentHook, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(BackgroundProcessManager.class);
    private static final int MAX_RUNNING = 4;
    private static final int MAX_RETAINED = 32;
    private final RunCommandTool runner = new RunCommandTool();
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private long generation;
    private boolean closed;

    private static final class Task {
        final String id = "process-" + UUID.randomUUID();
        final Path owner;
        final RunCommandTool.Options options;
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicReference<Process> process = new AtomicReference<>();
        final CommandOutput output;
        final long created = System.nanoTime();
        volatile RunCommandTool.Result result;
        Thread worker;

        Task(Path owner, RunCommandTool.Options options) {
            this.owner = owner;
            this.options = options;
            output = new CommandOutput(owner, true);
        }

        String state() {
            return state(result);
        }

        String state(RunCommandTool.Result current) {
            if (current != null) {
                return current.status().equals("cancelled") ? "stopped" : current.status();
            }
            if (stop.get()) {
                return "stopping";
            }
            Process currentProcess = process.get();
            return currentProcess == null ? "starting" : currentProcess.isAlive() ? "running" : "finishing";
        }

        void requestStop() {
            stop.set(true);
            // 执行线程捕获进程树后统一清理，避免先杀 shell 导致子进程失去父关系。
            worker.interrupt();
        }
    }

    public PreparedToolCall prepareStart(JsonNode args, ToolContext ctx) {
        long expectedGeneration;
        synchronized (this) {
            ensureOpen();
            expectedGeneration = generation;
        }
        Path owner = owner(ctx);
        RunCommandTool.Options options = runner.options(args, ctx, 3600, 86400, "process_start");
        return new PreparedToolCall(null, () -> start(options, owner, expectedGeneration, ctx));
    }

    private synchronized String start(RunCommandTool.Options options, Path owner, long expectedGeneration, ToolContext caller)
            throws InterruptedException {
        ensureOpen();
        checkCaller(caller);
        if (generation != expectedGeneration) {
            throw new ToolException("会话已改变，后台任务未启动，请在当前会话重新调用");
        }
        if (tasks.values().stream().anyMatch(task -> !task.owner.equals(owner))) {
            throw new ToolException("后台任务属于其他会话，请先完成会话切换清理");
        }
        if (tasks.values().stream().filter(task -> task.result == null).count() >= MAX_RUNNING) {
            throw new ToolException("最多同时运行 " + MAX_RUNNING + " 个后台任务，请先停止或等待已有任务");
        }
        if (tasks.size() >= MAX_RETAINED) {
            String oldest = tasks.values().stream().filter(task -> task.result != null)
                    .map(task -> task.id).findFirst().orElseThrow();
            tasks.remove(oldest);
        }
        Task task = new Task(owner, options);
        ToolContext context = new ToolContext(caller.workspaceRoot(), caller.sandbox(), caller.shellChoice(),
                task.process, task.stop::get, owner, caller.maxToolResultChars());
        task.worker = new Thread(() -> execute(task, context), "jaco-background-" + task.id);
        task.worker.setDaemon(true);
        tasks.put(task.id, task);
        task.worker.start();
        if (caller.cancelled().getAsBoolean() || Thread.currentThread().isInterrupted()) {
            task.requestStop();
        }
        return header(task) + "\n(后台任务已登记；用 process_poll 查询状态和日志，process_stop 停止)";
    }

    private void execute(Task task, ToolContext context) {
        try {
            task.result = runner.run(task.options, context, task.output);
        } catch (Exception e) {
            task.output.finish();
            task.result = new RunCommandTool.Result(task.stop.get() ? "cancelled" : "execution_failed", null,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - task.created), task.options,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), task.output);
        }
    }

    public PreparedToolCall preparePoll(JsonNode args, ToolContext ctx) {
        CommandArguments.validate(args, "process_poll", "task_id", "offset", "limit", "wait_ms");
        String id = CommandArguments.string(args, "task_id", null);
        if (id == null) {
            if (args.size() != 0) {
                throw new ToolException("读取日志参数需要 task_id；省略全部参数可列出当前会话任务");
            }
            return new PreparedToolCall(null, () -> list(ctx));
        }
        long offset = CommandArguments.number(args, "offset", 0, 0, Long.MAX_VALUE);
        int limit = (int) CommandArguments.number(args, "limit", 2000, 1, 10000);
        int wait = (int) CommandArguments.number(args, "wait_ms", 0, 0, 10000);
        return new PreparedToolCall(null, () -> poll(id, offset, limit, wait, ctx));
    }

    private String poll(String id, long offset, int limit, int wait, ToolContext ctx) throws InterruptedException {
        checkCaller(ctx);
        Task task = task(id, ctx);
        // offset 大于当前长度应立即报错，不能被 wait_ms 掩盖。
        task.output.page(offset, 2);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(wait);
        while (wait > 0 && task.result == null && task.output.length() <= offset && System.nanoTime() < deadline) {
            checkCaller(ctx);
            TimeUnit.MILLISECONDS.sleep(50);
        }
        checkCaller(ctx);
        task(id, ctx);
        String header = header(task);
        int budget = ctx.maxToolResultChars() >= 1000 ? ctx.maxToolResultChars() : 8000;
        int available = Math.max(2, budget - header.length() - task.output.metadata().length() - 200);
        CommandOutput.Page page = task.output.page(offset, Math.min(limit, available));
        return header + "\n" + page.metadata() + "available_from: " + page.availableFrom()
                + "\nnext_offset: " + page.nextOffset() + "\nhas_more: " + page.hasMore()
                + "\nlog_gap: " + page.truncated()
                + (page.truncated() ? "\n(此前日志已离开内存缓冲，可用 recall 检索归档；归档也有存储上限)" : "")
                + "\n\n" + (page.text().isEmpty() ? "(no new output)" : page.text());
    }

    public PreparedToolCall prepareStop(JsonNode args, ToolContext ctx) {
        CommandArguments.validate(args, "process_stop", "task_id");
        String id = CommandArguments.string(args, "task_id", null);
        if (id == null) {
            throw new ToolException("task_id 必须提供非空白字符串");
        }
        task(id, ctx);
        return new PreparedToolCall(null, () -> {
            checkCaller(ctx);
            Task task = task(id, ctx);
            if (task.result == null) {
                task.requestStop();
                await(List.of(task), 6000);
            }
            return header(task) + "\n" + task.output.metadata();
        });
    }

    private synchronized String list(ToolContext ctx) throws InterruptedException {
        checkCaller(ctx);
        Path owner = owner(ctx);
        int budget = ctx.maxToolResultChars() >= 1000 ? ctx.maxToolResultChars() : 8000;
        StringBuilder result = new StringBuilder();
        for (Task task : tasks.values()) {
            if (!task.owner.equals(owner)) continue;
            String row = task.id + " | " + task.state() + " | " + brief(task.options.command(), 100) + "\n";
            if (result.length() + row.length() > budget - 150) {
                result.append("(列表已截短；可用已知 task_id 查询单个任务)\n");
                break;
            }
            result.append(row);
        }
        return result.isEmpty() ? "(当前会话没有后台任务)" : result.toString();
    }

    private synchronized Task task(String id, ToolContext ctx) {
        Task task = tasks.get(id);
        if (task == null || !task.owner.equals(owner(ctx))) {
            throw new ToolException("后台任务不存在或不属于当前会话: " + id);
        }
        return task;
    }

    private Path owner(ToolContext ctx) {
        if (ctx.archiveFile() == null) {
            throw new ToolException("后台任务需要当前会话的归档路径");
        }
        return ctx.archiveFile().toAbsolutePath().normalize();
    }

    private void ensureOpen() {
        if (closed) {
            throw new ToolException("后台任务管理器已关闭");
        }
    }

    private void checkCaller(ToolContext ctx) throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || ctx.cancelled().getAsBoolean()) {
            throw new InterruptedException("后台任务操作已取消");
        }
    }

    private String header(Task task) {
        RunCommandTool.Result result = task.result;
        Process process = task.process.get();
        return "task_id: " + task.id + "\nstatus: " + task.state(result)
                + "\nexit_code: " + (result == null ? "null" : result.exitCode())
                + "\npid: " + (process == null ? "null" : process.pid())
                + "\nduration_ms: " + (result == null ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - task.created) : result.durationMs())
                + "\ncommand: " + brief(task.options.command(), 120) + "\ncwd: " + brief(task.options.cwd().toString(), 160)
                + "\nencoding: " + task.options.encoding().name()
                + (result == null || result.warning().isBlank() ? "" : "\nwarning: " + brief(result.warning(), 200));
    }

    private String brief(String text, int max) {
        String single = text.replace('\n', ' ').replace('\r', ' ');
        return single.length() <= max ? single : single.substring(0, max) + "…";
    }

    @Override
    public void onSessionChanged() {
        stopAll(false);
    }

    @Override
    public void close() {
        stopAll(true);
    }

    private void stopAll(boolean shutdown) {
        List<Task> previous;
        synchronized (this) {
            if (closed && !shutdown) return;
            closed |= shutdown;
            generation++;
            previous = new ArrayList<>(tasks.values());
            previous.stream().filter(task -> task.result == null).forEach(Task::requestStop);
        }
        await(previous, 6000);
        synchronized (this) {
            previous.forEach(task -> tasks.remove(task.id, task));
        }
    }

    private void await(List<Task> tasks, long millis) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        for (Task task : tasks) {
            while (task.worker.isAlive() && System.nanoTime() < deadline) {
                try {
                    TimeUnit.NANOSECONDS.timedJoin(task.worker, Math.max(1, deadline - System.nanoTime()));
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (task.worker.isAlive()) {
                LOG.warn("后台任务 {} 未能在清理窗口内退出", task.id);
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
