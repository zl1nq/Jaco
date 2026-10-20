package com.jaco.agent.subagent;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/** 父归属及预算在接收任务前固定；模型、沙箱和工具配置由不可变 runner 持有。 */
public record SubagentSpec(String parentSessionId, String parentTurnId, String prompt,
                           Duration timeout, int maxIterations, SubagentProfile profile, Set<String> tools,
                           boolean runInBackground) {
    public SubagentSpec(String parentSessionId, String parentTurnId, String prompt,
                        Duration timeout, int maxIterations, SubagentProfile profile, Set<String> tools) {
        this(parentSessionId, parentTurnId, prompt, timeout, maxIterations, profile, tools, false);
    }
    /** 兼容已有调用，默认使用只读调查预设。 */
    public SubagentSpec(String parentSessionId, String parentTurnId, String prompt,
                        Duration timeout, int maxIterations) {
        this(parentSessionId, parentTurnId, prompt, timeout, maxIterations, SubagentProfile.EXPLORE, null);
    }

    public SubagentSpec(String parentSessionId, String parentTurnId, String prompt,
                        Duration timeout, int maxIterations, SubagentProfile profile) {
        this(parentSessionId, parentTurnId, prompt, timeout, maxIterations, profile, null);
    }

    public SubagentSpec {
        Objects.requireNonNull(parentSessionId);
        Objects.requireNonNull(parentTurnId);
        Objects.requireNonNull(prompt);
        Objects.requireNonNull(timeout);
        Objects.requireNonNull(profile);
        tools = profile.selectTools(tools);
        if (runInBackground && profile != SubagentProfile.EXPLORE) {
            throw new IllegalArgumentException("后台子任务仅支持 explore 预设");
        }
        if (prompt.isBlank() || timeout.isNegative() || timeout.isZero() || maxIterations < 1) {
            throw new IllegalArgumentException("子任务说明及预算无效");
        }
        timeout.toNanos(); // 拒绝无法用单调时钟表示的预算。
    }
}
