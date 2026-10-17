package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaco.tool.JsonSchema;
import com.jaco.tool.PreparedToolCall;
import com.jaco.tool.Tool;
import com.jaco.tool.ToolContext;

public final class ProcessStartTool implements Tool {
    private final BackgroundProcessManager manager;

    public ProcessStartTool(BackgroundProcessManager manager) {
        this.manager = manager;
    }

    @Override
    public String name() {
        return "process_start";
    }

    @Override
    public String description() {
        return "后台启动命令，立即返回 task_id；用 process_poll 查询状态和增量日志，process_stop 停止。"
                + "适用于长构建、测试或开发服务；命令本身应保持前台运行，不要加 &、start 等再次脱离管理。"
                + "非交互执行，stdin 关闭；cwd 受启动目录沙箱限制，命令进程本身没有系统级沙箱。"
                + "最多同时 4 个任务，超时默认 3600 秒、上限 86400；任务仅在当前会话有效，切换会话或退出应用时停止。";
    }

    @Override
    public JsonNode schema() {
        ObjectNode schema = (ObjectNode) JsonSchema.object()
                .string("command", "非空白命令行，不要让命令再次自行后台化")
                .string("cwd", "启动目录，默认工作目录，必须位于允许范围内")
                .integer("timeout_seconds", "最大运行秒数，默认 3600，范围 1-86400")
                .string("encoding", "输出解码字符集，如 UTF-8、GBK，默认 JVM 字符集")
                .required("command").build();
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public String summary(JsonNode args) {
        String command = args.path("command").asText("?");
        return command.length() <= 80 ? command : command.substring(0, 80) + "…";
    }

    @Override
    public PreparedToolCall prepare(JsonNode args, ToolContext ctx) {
        return manager.prepareStart(args, ctx);
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws Exception {
        return prepare(args, ctx).execute();
    }
}
