package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.tool.*;
import java.util.function.Function;

/** 显式取消子任务，不取消主轮次。 */
public final class TaskCancelTool implements Tool {
    private final Function<String, String> cancel;

    public TaskCancelTool(Function<String, String> cancel) { this.cancel = cancel; }
    public String name() { return "task_cancel"; }
    public String description() { return "请求取消当前会话指定的子任务。返回取消请求回执；用 task_status 确认实际退出。"; }
    public JsonNode schema() { return JsonSchema.object().string("task_id", "task 返回的完整任务ID").required("task_id").build(); }
    public String summary(JsonNode args) { return args.path("task_id").asText("?"); }
    public String execute(JsonNode args, ToolContext ctx) {
        if (args == null || !args.isObject() || !args.path("task_id").isTextual()
                || args.path("task_id").asText().isBlank()) return "ERROR: task_id 必须是非空字符串";
        return cancel.apply(args.path("task_id").asText().strip());
    }
}
