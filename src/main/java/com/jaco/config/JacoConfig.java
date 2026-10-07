package com.jaco.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * ~/.jaco/config.yaml 的绑定模型。未知字段忽略（向前兼容：老版本读新配置不崩）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JacoConfig(
        String active,
        @JsonProperty("system_prompt") String systemPrompt,
        @JsonProperty("log_level") String logLevel,
        String shell,
        @JsonProperty("max_iterations") Integer maxIterations,
        @JsonProperty("context_limit") Long contextLimit,
        WorkspaceConfig workspace,
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

    public int effectiveMaxIterations() {
        return maxIterations == null || maxIterations < 1 ? 25 : maxIterations;
    }

    /** 模型上下文窗口大小（tokens），决定压缩触发点（70%）与目标（40%）。 */
    public long effectiveContextLimit() {
        return contextLimit == null || contextLimit < 1024 ? 65536 : contextLimit;
    }

    /** shell 取值：auto / bash / cmd / powershell，默认 auto。 */
    public String effectiveShell() {
        return shell == null || shell.isBlank() ? "auto" : shell;
    }
}
