package com.jaco.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * OpenAI 协议的 tools 参数条目（function calling）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolDefinition(
        @JsonProperty("type") String type,
        Function function) {

    public static final String TYPE_FUNCTION = "function";

    public static ToolDefinition of(String name, String description, JsonNode parametersSchema) {
        return new ToolDefinition(TYPE_FUNCTION, new Function(name, description, parametersSchema));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Function(
            String name,
            String description,
            JsonNode parameters) {
    }
}
