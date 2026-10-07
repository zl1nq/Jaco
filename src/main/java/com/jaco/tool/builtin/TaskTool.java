package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.tool.JsonSchema;
import com.jaco.tool.Tool;
import com.jaco.tool.ToolContext;

import java.util.function.BooleanSupplier;

/**
 * 委派子 agent 执行只读调查任务：独立线程跑一个嵌套 agent loop（独立上下文），
 * 最终报告作为工具结果返回父模型。中间探索过程不占用父上下文，也不进父会话归档。
 * Handler 由 Main 在 AgentRunner 构造后绑定（registry 先于 runner 存在，晚接线）。
 */
public final class TaskTool implements Tool {

    /** 由 AgentRunner 实现：同步执行子任务并返回报告（含"被中断"等终态文案）。 */
    public interface Handler {
        String run(String prompt, BooleanSupplier cancelled);
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
        return "委派一个只读子 agent 独立调查任务（只能读文件、grep、列目录、查归档），它返回最终结论报告。"
                + "适合大范围搜索、代码调研等中间输出很多的场景；子任务的过程不占用本对话上下文。";
    }

    @Override
    public JsonNode schema() {
        return JsonSchema.object()
                .string("prompt", "给子 agent 的完整任务说明：目标、范围、报告应包含什么")
                .required("prompt")
                .build();
    }

    @Override
    public String summary(JsonNode args) {
        String p = args.path("prompt").asText("?");
        return p.replace('\n', ' ').length() > 60 ? p.substring(0, 60) + "…" : p;
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) {
        String prompt = args.path("prompt").asText("").strip();
        if (prompt.isEmpty()) {
            return "ERROR: prompt 为空";
        }
        Handler h = handler;
        if (h == null) {
            return "ERROR: 子 agent 未接线";
        }
        return h.run(prompt, ctx.cancelled());
    }
}
