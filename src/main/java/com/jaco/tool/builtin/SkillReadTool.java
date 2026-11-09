package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaco.tool.*;

public final class SkillReadTool implements Tool {
    public String name() { return "skill_read"; }
    public String description() { return "分页读取本轮已加载技能包中的 UTF-8 参考资料、模板或脚本内容，不执行脚本。"; }
    public String prompt() { return "skill_read 使用技能完整标识和包内相对路径；先 skill_load。沿用 read_file 的行号、next_line/next_column_offset 和 expected_version 分页。首次读取固定资源版本，变化后不能在本轮切换到新版本；加载或读取不授予执行权限。"; }
    public boolean isReadOnly() { return true; }
    public boolean requiresConfirmationChannel() { return false; }
    public boolean isAllowedInPlan() { return true; }
    public OutputPolicy outputPolicy() { return OutputPolicy.PAGINATED; }
    public JsonNode schema() {
        ObjectNode schema = (ObjectNode) new ReadFileTool().schema().deepCopy();
        schema.withObject("/properties").putObject("name").put("type", "string").put("minLength", 1).put("maxLength", 72);
        schema.withArray("required").add("name");
        return schema;
    }
    public String summary(JsonNode args) { return args.path("name").asText() + ": " + args.path("path").asText(); }
    public ToolResult execute(JsonNode args, ToolContext ctx) throws Exception {
        if (ctx.skills() == null) throw new ToolException("技能仅供主任务使用");
        return ctx.skills().read(args.path("name").asText(), (ObjectNode) args, ctx);
    }
}
