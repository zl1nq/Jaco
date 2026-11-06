package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.tool.*;

public final class EnterPlanModeTool implements Tool {
    public String name() { return "enter_plan_mode"; }
    public String description() { return "进入只允许读取和规划的计划模式。"; }
    public String prompt() { return "需要先研究并提交方案时进入计划模式；进入前必须等待后台任务及资源清理完成。"; }
    public boolean isAllowedInPlan() { return true; }
    public boolean requiresConfirmationChannel() { return false; }
    public PermissionCategory permissionCategory() { return PermissionCategory.SESSION_STATE; }
    public JsonNode schema() { return JsonSchema.object().build(); }
    public String summary(JsonNode args) { return "进入计划模式"; }
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        if (ctx.workflow() == null) throw new ToolException("计划模式仅供主会话使用");
        return ToolResult.success(ctx.workflow().enterPlan());
    }
}
