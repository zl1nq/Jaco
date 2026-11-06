package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.tool.*;

public final class TodoReadTool implements Tool {
    public String name() { return "todo_read"; }
    public String description() { return "读取当前主会话的任务清单与完成进度。"; }
    public String prompt() { return "用 todo_read 查看整体任务进度；清单随会话恢复，子任务通过报告让主任务更新。"; }
    public boolean isReadOnly() { return true; }
    public boolean requiresConfirmationChannel() { return false; }
    public PermissionCategory permissionCategory() { return PermissionCategory.SESSION_STATE; }
    public JsonNode schema() { return JsonSchema.object().build(); }
    public String summary(JsonNode args) { return "当前任务清单"; }
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        if (ctx.workflow() == null) throw new ToolException("清单仅供主会话使用");
        return ToolResult.success(ctx.workflow().readTodos());
    }
}
