package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.tool.*;

public final class SkillListTool implements Tool {
    public String name() { return "skill_list"; }
    public String description() { return "分页查询可由模型加载的本地技能。"; }
    public String prompt() { return "用 skill_list 查询完整目录；通过 next_offset 继续读取，完整标识可区分项目与用户技能。"; }
    public boolean isReadOnly() { return true; }
    public boolean requiresConfirmationChannel() { return false; }
    public boolean isAllowedInPlan() { return true; }
    public OutputPolicy outputPolicy() { return OutputPolicy.PAGINATED; }
    public JsonNode schema() { return JsonSchema.object().string("query", "可选搜索词").integer("offset", "起始条目，默认 0", 0, 2147483647).integer("limit", "最多条目数，默认 20", 1, 50).build(); }
    public String summary(JsonNode args) { return args.path("query").asText(""); }
    public ToolResult execute(JsonNode args, ToolContext ctx) throws Exception {
        if (ctx.skills() == null) throw new ToolException("技能仅供主任务使用");
        String result = ctx.skills().list(args.path("query").asText(""), args.path("offset").asInt(0), args.path("limit").asInt(20), ctx.maxToolResultChars());
        return ToolResult.success(result);
    }
}
