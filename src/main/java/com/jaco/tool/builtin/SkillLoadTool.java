package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.tool.*;

public final class SkillLoadTool implements Tool {
    public String name() { return "skill_load"; }
    public String description() { return "加载技能的完整方法说明，仅当前主任务轮次有效。"; }
    public String prompt() { return "仅在技能适合当前任务时用 skill_load 加载，加载后完整说明会进入下一次请求。历史调用不能代替本轮加载；技能不授予权限，不退出计划模式。"; }
    public boolean isReadOnly() { return true; }
    public boolean requiresConfirmationChannel() { return false; }
    public boolean isAllowedInPlan() { return true; }
    public JsonNode schema() { return JsonSchema.object().string("name", "技能名称或完整标识", 1, 72).required("name").build(); }
    public String summary(JsonNode args) { return args.path("name").asText(""); }
    public ToolResult execute(JsonNode args, ToolContext ctx) throws Exception {
        if (ctx.skills() == null) throw new ToolException("技能仅供主任务使用");
        String result = ctx.skills().load(args.path("name").asText(), false);
        var sink = com.jaco.agent.TurnEventSink.current();
        if (sink != null) sink.emit(new com.jaco.agent.TurnEvent.Notice(result));
        return ToolResult.success(result);
    }
}
