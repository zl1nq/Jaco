package com.jaco.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 手写 JSON Schema 的小助手，让工具参数定义保持一行一个字段的可读性。
 */
public final class JsonSchema {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectNode root = MAPPER.createObjectNode();
    private final ObjectNode properties = MAPPER.createObjectNode();
    private final ArrayNode required = MAPPER.createArrayNode();

    private JsonSchema() {
        root.put("type", "object");
        root.set("properties", properties);
    }

    public static JsonSchema object() {
        return new JsonSchema();
    }

    public JsonSchema string(String name, String description) {
        return typed(name, "string", description);
    }

    public JsonSchema integer(String name, String description) {
        return typed(name, "integer", description);
    }

    public JsonSchema string(String name, String description, String... enumValues) {
        ObjectNode prop = properties.putObject(name);
        prop.put("type", "string").put("description", description);
        ArrayNode enums = prop.putArray("enum");
        for (String v : enumValues) {
            enums.add(v);
        }
        return this;
    }

    public JsonSchema required(String... names) {
        for (String name : names) {
            required.add(name);
        }
        return this;
    }

    public JsonNode build() {
        root.set("required", required);
        return root;
    }

    private JsonSchema typed(String name, String type, String description) {
        properties.putObject(name)
                .put("type", type)
                .put("description", description);
        return this;
    }
}
