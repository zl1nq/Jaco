package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.tool.*;
import java.util.function.Function;

/** 读取当前会话的子任务状态及最终报告；不会等待任务完成。 */
public final class TaskStatusTool implements Tool {
    private final Function<String, String> query;

    public TaskStatusTool(Function<String, String> query) { this.query = query; }
    public String name() { return "task_status"; }
    public String description() { return "查询当前会话子任务的状态或最终报告。省略 task_id 列出任务；不会阻塞等待。"; }
    public JsonNode schema() { return JsonSchema.object().string("task_id", "task 返回的完整任务ID；省略列出任务").build(); }
    public String summary(JsonNode args) { return args.path("task_id").asText("全部子任务"); }
    public String execute(JsonNode args, ToolContext ctx) {
        if (args == null || !args.isObject() || (args.has("task_id") &&
                (!args.path("task_id").isTextual() || args.path("task_id").asText().isBlank()))) {
            return "ERROR: task_id 必须是非空字符串";
        }
        String id = args.path("task_id").asText("").strip();
        // 查询接口不接受终端命令语法，避免状态查询触发取消。
        if (id.chars().anyMatch(Character::isWhitespace)) return "ERROR: task_id 必须是完整任务ID";
        return query.apply(id);
    }
}
