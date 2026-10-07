package com.jaco.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一个可被模型调用的工具。Schema 与 Handler 分离（注册进 ToolRegistry 后新增工具零侵入）；
 * execute 返回的字符串会原样作为 tool result 回喂模型。
 */
public interface Tool {

    String name();

    String description();

    /** JSON Schema（object 类型），描述 execute 接受的参数。 */
    JsonNode schema();

    /** 单行参数摘要，用于终端展示（如 run_command 显示命令本身）。 */
    String summary(JsonNode args);

    /** 执行工具。抛出的任何异常都会被转成 "ERROR: ..." 回喂模型，不会中断 agent loop。 */
    String execute(JsonNode args, ToolContext ctx) throws Exception;
}
