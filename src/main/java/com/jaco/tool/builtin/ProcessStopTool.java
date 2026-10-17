package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaco.tool.JsonSchema;
import com.jaco.tool.PreparedToolCall;
import com.jaco.tool.Tool;
import com.jaco.tool.ToolContext;

public final class ProcessStopTool implements Tool {
    private final BackgroundProcessManager manager;

    public ProcessStopTool(BackgroundProcessManager manager) {
        this.manager = manager;
    }

    @Override
    public String name() {
        return "process_stop";
    }

    @Override
    public String description() {
        return "停止当前会话的后台任务及已发现的子进程，等待清理并返回状态；重复停止已结束任务不会改变其退出结果。"
                + "若仍在清理会返回 stopping，可用 process_poll 查询；进程树清理尽力执行，不能保证终止自行脱离管理的进程。";
    }

    @Override
    public JsonNode schema() {
        ObjectNode schema = (ObjectNode) JsonSchema.object()
                .string("task_id", "要停止的后台任务 ID")
                .required("task_id").build();
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public String summary(JsonNode args) {
        return args.path("task_id").asText("?");
    }

    @Override
    public PreparedToolCall prepare(JsonNode args, ToolContext ctx) {
        return manager.prepareStop(args, ctx);
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws Exception {
        return prepare(args, ctx).execute();
    }
}
