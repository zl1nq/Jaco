package com.jaco.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatRequest(
        String model,
        List<Message> messages,
        Double temperature,
        boolean stream,
        @JsonProperty("stream_options") StreamOptions streamOptions) {

    public record StreamOptions(@JsonProperty("include_usage") boolean includeUsage) {
    }
}
