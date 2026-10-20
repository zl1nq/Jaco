package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.tool.JsonSchema;
import com.jaco.tool.Tool;
import com.jaco.tool.ToolContext;
import com.jaco.agent.subagent.SubagentProfile;
import com.jaco.agent.subagent.SubagentRequest;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * 按预设委派子 agent：独立线程跑一个嵌套 agent loop（独立上下文），
 * 最终报告作为工具结果返回父模型。中间探索过程不占用父上下文，也不进父会话归档。
 * Handler 由 Main 在 AgentRunner 构造后绑定（registry 先于 runner 存在，晚接线）。
 */
public final class TaskTool implements Tool {

    /** 由 AgentRunner 实现：同步执行子任务并返回报告（含"被中断"等终态文案）。 */
    public interface Handler {
        String run(SubagentRequest request, BooleanSupplier cancelled);
    }

    private volatile Handler handler;

    public void bind(Handler handler) {
        this.handler = handler;
    }

    @Override
    public String name() {
        return "task";
    }

    @Override
    public String description() {
        return "委派一个具有独立上下文的子 agent，返回最终报告。"
                + "profile 默认 explore（只读调查）；edit 可读写文件，修改会展示预览并遵循宿主权限确认和审计。"
                + "两种预设都禁止 shell、网络和递归委派；tools 可进一步收窄工具范围，不能扩权。"
                + "适合大范围搜索、代码调研等中间输出很多的场景；子任务的过程不占用本对话上下文。";
    }

    @Override
    public JsonNode schema() {
        return JsonSchema.object()
                .string("prompt", "给子 agent 的完整任务说明：目标、范围、报告应包含什么")
                .string("profile", "explore=只读调查（默认）；edit=文件修改，执行前展示预览并走权限确认",
                        Arrays.stream(SubagentProfile.values()).map(SubagentProfile::id).toArray(String[]::new))
                .strings("tools", "可选工具子集，必须全部属于所选预设；省略使用完整预设，[] 禁止工具，重复项去重",
                        Arrays.stream(SubagentProfile.values()).flatMap(p -> p.tools().stream())
                                .distinct().sorted().toArray(String[]::new))
                .required("prompt")
                .build();
    }

    @Override
    public String summary(JsonNode args) {
        String p = args.path("prompt").asText("?");
        String profile = args.path("profile").asText("explore");
        String tools = args.has("tools") ? args.path("tools").toString() : "预设默认";
        return "profile=" + brief(profile, 32) + ", tools=" + brief(tools, 100) + ": " + brief(p, 60);
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) {
        SubagentRequest request;
        try {
            request = parseRequest(args);
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        }
        Handler h = handler;
        if (h == null) return "ERROR: 子 agent 未接线";
        return h.run(request, ctx.cancelled());
    }

    private SubagentRequest parseRequest(JsonNode args) {
        if (args == null || !args.isObject()) throw new IllegalArgumentException("task 参数必须是对象");
        if (args.has("prompt") && !args.path("prompt").isTextual()) {
            throw new IllegalArgumentException("prompt 必须是字符串");
        }
        String prompt = args.path("prompt").asText("").strip();
        if (prompt.isEmpty()) throw new IllegalArgumentException("prompt 为空");
        SubagentProfile profile = SubagentProfile.EXPLORE;
        if (args.has("profile")) {
            if (!args.path("profile").isTextual()) throw new IllegalArgumentException("profile 必须是字符串");
            profile = SubagentProfile.fromId(args.path("profile").textValue());
        }
        Set<String> tools = null;
        if (args.has("tools")) {
            if (!args.path("tools").isArray()) throw new IllegalArgumentException("tools 必须是字符串数组");
            tools = new LinkedHashSet<>();
            for (var tool : args.path("tools")) {
                if (!tool.isTextual() || tool.textValue().isBlank()) {
                    throw new IllegalArgumentException("tools 中的工具名必须是非空字符串");
                }
                tools.add(tool.textValue());
            }
        }
        return new SubagentRequest(prompt, profile, tools);
    }

    private String brief(String text, int limit) {
        String flat = text.replace('\n', ' ');
        if (flat.length() <= limit) return flat;
        int end = limit;
        if (end > 0 && Character.isHighSurrogate(flat.charAt(end - 1))) end--;
        return flat.substring(0, end) + "…";
    }
}
