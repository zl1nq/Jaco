package com.jaco.agent.subagent;

import java.util.Objects;
import java.util.Set;

/** 工具层到运行时的不可变请求；在启动线程前完成预设及工具范围验证。 */
public record SubagentRequest(String prompt, SubagentProfile profile, Set<String> tools) {
    public SubagentRequest(String prompt) {
        this(prompt, SubagentProfile.EXPLORE, null);
    }

    public SubagentRequest {
        Objects.requireNonNull(prompt);
        Objects.requireNonNull(profile);
        prompt = prompt.strip();
        if (prompt.isEmpty()) throw new IllegalArgumentException("prompt 为空");
        tools = profile.selectTools(tools);
    }
}
