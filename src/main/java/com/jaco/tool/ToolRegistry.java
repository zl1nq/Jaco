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

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public List<ToolDefinition> definitions() {
        return definitions(null);
    }

    /** 工具子集（子 agent 用）；include 为 null 时返回全部。 */
    public List<ToolDefinition> definitions(java.util.Set<String> include) {
        List<ToolDefinition> defs = new ArrayList<>();
        for (Tool tool : tools.values()) {
            if (include == null || include.contains(tool.name())) {
                defs.add(ToolDefinition.of(tool.name(), tool.description(), tool.schema()));
            }
        }
        return defs;
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
    public String execute(ToolCall call, ToolContext ctx) throws Exception {
        Tool tool = tools.get(call.function().name());
        if (tool == null) {
            throw new ToolException("未知工具: " + call.function().name());
        }
        JsonNode args;
        try {
            args = MAPPER.readTree(call.function().arguments());
        } catch (Exception e) {
            throw new ToolException("参数不是合法 JSON: " + e.getMessage());
        }
        return tool.execute(args, ctx);
    }
}
