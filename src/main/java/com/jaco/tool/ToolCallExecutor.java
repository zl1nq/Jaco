package com.jaco.tool;

import com.jaco.agent.TurnEvent;
import com.jaco.agent.TurnEventSink;
import com.jaco.hook.HookChain;
import com.jaco.llm.ToolCall;
import java.util.Set;
import java.util.function.Consumer;

/** 父子任务共用：范围检查 → 只读准备/预览 → 权限 hook → 同一计划执行。 */
public final class ToolCallExecutor {
    private final ToolRegistry registry;
    private final HookChain hooks;

    public ToolCallExecutor(ToolRegistry registry, HookChain hooks) {
        this.registry = registry;
        this.hooks = hooks;
    }

    public ToolExecution execute(ToolCall call, ToolContext ctx, Set<String> allowed, Consumer<TurnEvent> events) {
        String name = call.function().name() == null ? "?" : call.function().name();
        events.accept(new TurnEvent.ToolCallStart(name, registry.summaryOf(call)));
        try {
            check(ctx);
            if (allowed != null && !allowed.contains(name)) {
                return new ToolExecution(ToolExecution.Status.DENIED, "ERROR: 子 agent 本次只允许工具 " + allowed);
            }
            if (allowed != null && (name.equals("write_file") || name.equals("edit_file"))
                    && TurnEventSink.current() == null) {
                return new ToolExecution(ToolExecution.Status.DENIED, "Permission denied: 没有可用的确认渠道");
            }
            var prepared = registry.prepare(call, ctx);
            if (prepared.preview() != null) events.accept(new TurnEvent.ToolPreview(prepared.preview()));
            hooks.onToolPrepared(call, prepared.preview());
            check(ctx);
            var verdict = hooks.onBeforeToolCall(call);
            hooks.onToolPermission(call, verdict);
            if (!verdict.proceed()) {
                return new ToolExecution(cancelled(ctx) ? ToolExecution.Status.CANCELLED : ToolExecution.Status.DENIED,
                        "Permission denied: " + verdict.denyReason());
            }
            check(ctx);
            String result = prepared.execute();
            ToolExecution.Status status = result.startsWith("ERROR:") ? ToolExecution.Status.FAILED
                    : ToolExecution.Status.SUCCEEDED;
            if (name.equals("task")) {
                if (result.startsWith("子任务失败:")) status = ToolExecution.Status.FAILED;
                else if (result.startsWith("(子任务被中断)") || result.startsWith("子任务超时")
                        || result.startsWith("子任务仍在取消中") || result.startsWith("子任务因应用退出")) {
                    status = ToolExecution.Status.CANCELLED;
                }
            }
            return new ToolExecution(status, result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ToolExecution(ToolExecution.Status.CANCELLED, "ERROR: interrupted by user");
        } catch (Exception e) {
            return new ToolExecution(cancelled(ctx) ? ToolExecution.Status.CANCELLED : ToolExecution.Status.FAILED,
                    "ERROR: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /** 即使审计/后置 hook 失败，也发布真实执行结果；异常交给所属循环报告。 */
    public void completed(ToolCall call, ToolExecution execution, Consumer<TurnEvent> events) {
        try {
            hooks.onToolCompleted(call, execution);
        } finally {
            events.accept(new TurnEvent.ToolCallEnd(call.function().name(), execution.ok(), summary(execution.result())));
        }
    }

    private static boolean cancelled(ToolContext ctx) {
        return Thread.currentThread().isInterrupted() || ctx.cancelled().getAsBoolean();
    }

    private static void check(ToolContext ctx) throws InterruptedException {
        if (cancelled(ctx)) throw new InterruptedException("interrupted by user");
    }

    private static String summary(String result) {
        String first = result.lines().findFirst().orElse("");
        long lines = result.lines().count();
        String text = first.length() > 100 ? first.substring(0, 100) + "…" : first;
        return lines > 1 ? text + " …(" + lines + " 行)" : text;
    }
}
