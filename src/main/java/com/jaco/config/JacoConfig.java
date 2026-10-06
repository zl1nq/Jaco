package com.jaco.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * ~/.jaco/config.yaml 的绑定模型。
 */
public record JacoConfig(
        String active,
        @JsonProperty("system_prompt") String systemPrompt,
        @JsonProperty("log_level") String logLevel,
        Map<String, ProviderConfig> providers) {

    /** 解析出当前生效的 provider；active 未指定且只有一个 profile 时自动选择。 */
    public ProviderConfig activeProvider() {
        if (providers == null || providers.isEmpty()) {
            return null;
        }
        if (active == null || active.isBlank()) {
            return providers.size() == 1 ? providers.values().iterator().next() : null;
        }
        return providers.get(active);
    }
}
