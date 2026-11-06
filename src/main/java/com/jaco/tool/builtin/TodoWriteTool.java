package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaco.agent.TurnEvent;
import com.jaco.agent.TurnEventSink;
import com.jaco.session.TodoItem;
import com.jaco.tool.*;
import java.util.ArrayList;

public final class TodoWriteTool implements Tool {
    @Override public boolean isAllowedInPlan() { return true; }

    public String name() { return "todo_write"; }
    public String description() { return "整体更新主会话任务清单并保存进度。"; }
    public String prompt() { return "多步骤任务维护清单，保留既有项目的稳定 ID。只在工作实际完成时标记 completed。todos 整体替换清单，[] 清空；最多 50 项，每项内容最多 500 个字符。清单更新不授予文件或命令权限。"; }
    public boolean requiresConfirmationChannel() { return false; }
    public PermissionCategory permissionCategory() { return PermissionCategory.SESSION_STATE; }
    public JsonNode schema() {
        ObjectNode root = (ObjectNode) JsonSchema.object().required("todos").build();
        ObjectNode todos = root.withObject("/properties").putObject("todos");
        todos.put("type", "array").put("maxItems", 50);
        todos.set("items", JsonSchema.object().string("id", "稳定 ID，字母数字下划线或连字符", 1, 64)
                .string("content", "任务内容", 1, 500)
                .string("status", "当前状态", "pending", "in_progress", "completed")
                .required("id", "content", "status").build());
        return root;
    }
    public String summary(JsonNode args) { return "更新 " + args.path("todos").size() + " 项任务"; }
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        if (ctx.workflow() == null) throw new ToolException("清单仅供主会话使用");
        if (!args.path("todos").isArray()) throw new ToolException("todos 必须是数组");
        var todos = new ArrayList<TodoItem>();
        for (JsonNode item : args.get("todos")) todos.add(new TodoItem(item.path("id").asText(null),
                item.path("content").asText(null), TodoItem.Status.valueOf(item.path("status").asText())));
        String text = ctx.workflow().writeTodos(todos);
        TurnEventSink sink = TurnEventSink.current();
        if (sink != null) sink.emit(new TurnEvent.Notice(text));
        return ToolResult.success(text);
    }
}
