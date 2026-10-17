package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaco.tool.ToolException;

import java.util.Set;

/** 前台及后台命令工具共享严格参数校验。 */
final class CommandArguments {
    private CommandArguments() {
    }

    static void validate(JsonNode args, String tool, String... fields) {
        if (args == null || !args.isObject()) {
            throw new ToolException(tool + " 参数必须是 JSON 对象");
        }
        Set<String> allowed = Set.of(fields);
        args.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new ToolException("未知的 " + tool + " 参数: " + field);
            }
        });
    }

    static String string(JsonNode args, String field, String fallback) {
        JsonNode value = args.get(field);
        if (value == null) {
            return fallback;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new ToolException(field + " 必须是非空白字符串");
        }
        return value.textValue();
    }

    static long number(JsonNode args, String field, long fallback, long min, long max) {
        JsonNode value = args.get(field);
        if (value == null) {
            return fallback;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < min || value.longValue() > max) {
            throw new ToolException(field + " 必须是 " + min + " 到 " + max + " 的整数");
        }
        return value.longValue();
    }
}
