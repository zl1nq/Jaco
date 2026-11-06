package com.jaco.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaco.llm.ToolCall;
import com.jaco.llm.ToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工具注册表：Schema + Handler 的 dispatch map（教程模式），新增工具零侵入。 */
public final class ToolRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(Tool... initialTools) {
        for (Tool tool : initialTools) register(tool);
    }

    public Tool get(String name) { return tools.get(name); }

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public List<ToolDefinition> definitions() {
        return definitions(null);
    }

    /** 工具子集（子 agent 用）；include 为 null 时返回全部。 */
    public List<ToolDefinition> definitions(java.util.Set<String> include) {
        return definitions(include, false);
    }
    public List<ToolDefinition> definitions(java.util.Set<String> include, boolean planning) {
        List<ToolDefinition> defs = new ArrayList<>();
        for (Tool tool : tools.values()) {
            if (planning ? !tool.isAllowedInPlan() : tool.isPlanOnly()) continue;
            if (include == null || (include.contains(tool.name()) && tool.isSubagentAllowed())) {
                defs.add(ToolDefinition.of(tool.name(), tool.description(), tool.schema()));
            }
        }
        return defs;
    }

    /** 使用注册顺序装配，子任务范围与工具定义采用相同过滤规则。 */
    public String prompts(java.util.Set<String> include) {
        return prompts(include, false);
    }
    public String prompts(java.util.Set<String> include, boolean planning) {
        StringBuilder text = new StringBuilder();
        for (Tool tool : tools.values()) {
            if (planning ? !tool.isAllowedInPlan() : tool.isPlanOnly()) continue;
            if (include != null && (!include.contains(tool.name()) || !tool.isSubagentAllowed())) continue;
            String prompt = tool.prompt();
            if (!prompt.isBlank()) text.append("\n\n## 工具 ").append(tool.name()).append("\n").append(prompt);
        }
        return text.toString();
    }

    /** 参数摘要委托给工具自身，用于终端展示。args 非法时降级为原始字符串。 */
    public String summaryOf(ToolCall call) {
        Tool tool = tools.get(call.function().name());
        if (tool == null) {
            return String.valueOf(call.function().arguments());
        }
        try {
            return tool.summary(MAPPER.readTree(call.function().arguments()));
        } catch (Exception e) {
            return String.valueOf(call.function().arguments());
        }
    }

    /** 执行工具；未知工具/参数非法抛 ToolException。 */
    public ToolResult execute(ToolCall call, ToolContext ctx) throws Exception {
        return prepare(call, ctx).execute();
    }

    public PreparedToolCall prepare(ToolCall call, ToolContext ctx) throws Exception {
        Tool tool = tools.get(call.function().name());
        if (tool == null) {
            throw new ToolException("未知工具: " + call.function().name());
        }
        if (ctx.workflow() != null && ctx.workflow().planning() && !tool.isAllowedInPlan())
            throw new ToolException("计划模式禁止工具: " + tool.name());
        if (tool.isPlanOnly() && (ctx.workflow() == null || !ctx.workflow().planning()))
            throw new ToolException("工具仅在计划模式可用: " + tool.name());
        JsonNode args;
        try {
            args = MAPPER.readTree(call.function().arguments());
        } catch (Exception e) {
            throw new ToolException("参数不是合法 JSON: " + e.getMessage());
        }
        ToolArguments.validate(args, tool.schema());
        return tool.prepare(args, ctx);
    }
}
