package com.jaco.tool;

import com.fasterxml.jackson.databind.JsonNode;

/** 校验工具 Schema 使用的基础约束；互斥关系及文件等业务条件由工具校验。 */
final class ToolArguments {
    private ToolArguments() { }
    static void validate(JsonNode value, JsonNode schema) { validate(value, schema, "参数"); }
    private static void validate(JsonNode value, JsonNode schema, String path) {
        if (value == null || value.isNull()) fail(path, "不能为 null");
        String type = schema.path("type").asText("");
        boolean valid = switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "" -> true;
            default -> throw new IllegalArgumentException("不支持的工具参数类型: " + type);
        };
        if (!valid) fail(path, "必须是 " + type);
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode candidate : schema.get("enum")) if (candidate.equals(value)) found = true;
            if (!found) fail(path, "必须属于 " + schema.get("enum"));
        }
        if (value.isObject()) {
            for (JsonNode required : schema.path("required"))
                if (!value.has(required.asText())) fail(path + "." + required.asText(), "必须提供");
            JsonNode properties = schema.path("properties");
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String child = path + "." + field.getKey();
                if (properties.has(field.getKey())) validate(field.getValue(), properties.get(field.getKey()), child);
                else if (!schema.path("additionalProperties").asBoolean(true)) fail(child, "未知参数");
            }
        }
        if (value.isArray()) {
            bounds(value.size(), schema, "minItems", "maxItems", path);
            if (schema.has("items")) for (int i = 0; i < value.size(); i++)
                validate(value.get(i), schema.get("items"), path + "[" + i + "]");
        }
        if (value.isTextual()) bounds(value.textValue().length(), schema, "minLength", "maxLength", path);
        if (value.isNumber()) {
            if (schema.has("minimum") && value.decimalValue().compareTo(schema.get("minimum").decimalValue()) < 0)
                fail(path, "不能小于 " + schema.get("minimum"));
            if (schema.has("maximum") && value.decimalValue().compareTo(schema.get("maximum").decimalValue()) > 0)
                fail(path, "不能大于 " + schema.get("maximum"));
        }
    }
    private static void bounds(int size, JsonNode schema, String min, String max, String path) {
        if (schema.has(min) && size < schema.get(min).intValue()) fail(path, min + "=" + schema.get(min));
        if (schema.has(max) && size > schema.get(max).intValue()) fail(path, max + "=" + schema.get(max));
    }
    private static void fail(String path, String reason) { throw new ToolException(path + ": " + reason); }
}
