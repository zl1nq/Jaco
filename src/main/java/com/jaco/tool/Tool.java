package com.jaco.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一个可被模型调用的工具。Schema 与 Handler 分离（注册进 ToolRegistry 后新增工具零侵入）；
 * execute 返回的文本会原样作为 tool result 回喂模型。
 */
public interface Tool {

    String name();

    String description();

    /** 装配到系统提示词的详细使用规则，仅随可见工具注入。 */
    default String prompt() { return ""; }

    /** 默认按可写处理；只读不等于无需网络等专用授权。 */
    default boolean isReadOnly() { return false; }

    /** 子任务没有确认渠道时，默认拒绝执行。 */
    default boolean requiresConfirmationChannel() { return true; }

    /** 仅显式声明的工具可供子任务使用，仍须通过预设和调用允许列表。 */
    default boolean isSubagentAllowed() { return false; }

    default boolean isPlanOnly() { return false; }

    default boolean isAllowedInPlan() { return false; }

    enum PermissionCategory { STANDARD, SESSION_STATE }
    default PermissionCategory permissionCategory() { return PermissionCategory.STANDARD; }

    enum OutputPolicy { ARCHIVE_PREVIEW, PAGINATED }

    /** 分页工具必须自行保留预算内完整的续读元数据。 */
    default OutputPolicy outputPolicy() { return OutputPolicy.ARCHIVE_PREVIEW; }

    /** JSON Schema（object 类型），描述 execute 接受的参数。 */
    JsonNode schema();

    /** 单行参数摘要，用于终端展示（如 run_command 显示命令本身）。 */
    String summary(JsonNode args);

    /** 执行工具。抛出的任何异常都会被转成 "ERROR: ..." 回喂模型，不会中断 agent loop。 */
    ToolResult execute(JsonNode args, ToolContext ctx) throws Exception;

    /** 默认工具不需要预览；文件工具可只读准备修改计划。 */
    default PreparedToolCall prepare(JsonNode args, ToolContext ctx) throws Exception {
        return new PreparedToolCall(null, () -> execute(args, ctx));
    }
}
