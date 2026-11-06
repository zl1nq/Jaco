package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.agent.TurnEvent;
import com.jaco.agent.TurnEventSink;
import com.jaco.tool.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

public final class SubmitPlanTool implements Tool {
    public String name() { return "submit_plan"; }
    public String description() { return "保存并展示完整计划，等待用户明确批准或拒绝。"; }
    public String prompt() { return "仅在计划模式提交完整 Markdown 计划（最多 20000 个 UTF-16 字符）。专用确认批准后才继续执行；普通提问和既有权限不能代替批准。"; }
    public boolean isAllowedInPlan() { return true; }
    public boolean isPlanOnly() { return true; }
    public boolean requiresConfirmationChannel() { return false; }
    public PermissionCategory permissionCategory() { return PermissionCategory.SESSION_STATE; }
    public JsonNode schema() { return JsonSchema.object().string("plan", "完整 Markdown 计划", 1, 20000).required("plan").build(); }
    public String summary(JsonNode args) { return "提交计划并等待明确批准"; }
    public ToolResult execute(JsonNode args, ToolContext ctx) throws Exception {
        var workflow = ctx.workflow();
        if (workflow == null) throw new ToolException("计划仅供主会话使用");
        TurnEventSink sink = TurnEventSink.current();
        if (sink != null && sink.taskId() != null) throw new ToolException("子任务不能提交计划");
        String markdown = args.path("plan").asText();
        long revision = workflow.saveDraft(markdown);
        if (sink == null) {
            workflow.stopTurn();
            return new ToolResult(ToolExecution.Status.FAILED, "无可用的计划确认渠道；已保存草稿并保留计划模式，未批准。待用户交互确认。");
        }
        var answer = new CompletableFuture<Boolean>();
        BooleanSupplier current = () -> !sink.cancelled() && !ctx.cancelled().getAsBoolean() && workflow.currentDraft(revision);
        boolean approved = false;
        try {
            sink.emit(new TurnEvent.PlanApprovalRequest(workflow.sessionId(), sink.turnId(), revision, markdown, choice -> {
                if (!current.getAsBoolean() || answer.isDone()) return;
                if (choice == null) throw new ToolException("必须明确同意或拒绝计划");
                answer.complete(choice);
            }, () -> current.getAsBoolean() && !answer.isDone()));
            while (true) {
                if (Thread.currentThread().isInterrupted() || sink.cancelled() || ctx.cancelled().getAsBoolean())
                    throw new InterruptedException("计划确认已取消，保留草稿");
                if (!current.getAsBoolean()) throw new ToolException("计划确认已过期，未批准");
                try {
                    boolean choice = answer.get(100, TimeUnit.MILLISECONDS);
                    if (!current.getAsBoolean()) throw new ToolException("计划确认已过期，未批准");
                    String result = workflow.decidePlan(revision, choice);
                    approved = choice;
                    return new ToolResult(choice ? ToolExecution.Status.SUCCEEDED : ToolExecution.Status.DENIED, result);
                } catch (TimeoutException ignored) { /* 不将等待或空输入视作批准。 */ }
            }
        } finally {
            answer.cancel(false);
            if (!approved) workflow.stopTurn();
        }
    }
}
