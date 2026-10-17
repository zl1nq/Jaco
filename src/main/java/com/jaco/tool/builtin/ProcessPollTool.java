package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaco.tool.JsonSchema;
import com.jaco.tool.PreparedToolCall;
import com.jaco.tool.Tool;
import com.jaco.tool.ToolContext;

public final class ProcessPollTool implements Tool {
    private final BackgroundProcessManager manager;

    public ProcessPollTool(BackgroundProcessManager manager) {
        this.manager = manager;
    }

    @Override
    public String name() {
        return "process_poll";
    }

    @Override
    public String description() {
        return "只读查询当前会话后台任务：省略全部参数列出任务，指定 task_id 查询状态、退出码和日志。"
                + "offset 是整个输出的 UTF-16 字符位置，首次可省略，之后使用 next_offset 避免重复读取。"
                + "日志缓冲保留末尾 50000 字符；log_gap=true 表示旧日志已淘汰，available_from 给出可读取起点，"
                + "更早内容可通过 archive_id 用 recall 检索（最多归档前 2000000 字符）。"
                + "has_more 表示当前还有日志未读，与任务是否结束不同；wait_ms 可等待新输出或结束，取消等待不会停止任务。";
    }

    @Override
    public JsonNode schema() {
        ObjectNode schema = (ObjectNode) JsonSchema.object()
                .string("task_id", "process_start 返回的 ID；省略全部参数可列出任务")
                .integer("offset", "整个输出的字符偏移，默认 0；后续传返回的 next_offset")
                .integer("limit", "最多读取字符数，默认 2000，范围 1-10000，也受工具回填预算限制")
                .integer("wait_ms", "无新日志时最多等待毫秒数，默认 0，范围 0-10000")
                .build();
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public String summary(JsonNode args) {
        return args.path("task_id").asText("列出后台任务");
    }

    @Override
    public PreparedToolCall prepare(JsonNode args, ToolContext ctx) {
        return manager.preparePoll(args, ctx);
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws Exception {
        return prepare(args, ctx).execute();
    }
}
