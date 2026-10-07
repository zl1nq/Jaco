package com.jaco.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一个 OpenAI 兼容服务的连接配置。base_url 支持三种写法：
 * 服务根（https://api.deepseek.com）、带版本（.../v1）、完整 endpoint（.../chat/completions）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProviderConfig(
        @JsonProperty("base_url") String baseUrl,
        @JsonProperty("api_key") String apiKey,
        String model,
        Double temperature) {

    public String chatCompletionsUrl() {
        String base = baseUrl == null ? "" : baseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }
}
