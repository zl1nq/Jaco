package com.jaco.agent.subagent;

import java.time.Duration;
import java.util.Objects;

/** 父归属及预算在接收任务前固定；模型、沙箱和工具配置由不可变 runner 持有。 */
public record SubagentSpec(String parentSessionId, String parentTurnId, String prompt,
                           Duration timeout, int maxIterations, SubagentProfile profile) {
    /** 兼容已有调用，默认使用只读调查预设。 */
    public SubagentSpec(String parentSessionId, String parentTurnId, String prompt,
                        Duration timeout, int maxIterations) {
        this(parentSessionId, parentTurnId, prompt, timeout, maxIterations, SubagentProfile.EXPLORE);
    }

    public SubagentSpec {
        Objects.requireNonNull(parentSessionId);
        Objects.requireNonNull(parentTurnId);
        Objects.requireNonNull(prompt);
        Objects.requireNonNull(timeout);
        Objects.requireNonNull(profile);
        if (prompt.isBlank() || timeout.isNegative() || timeout.isZero() || maxIterations < 1) {
            throw new IllegalArgumentException("子任务说明及预算无效");
        }
        timeout.toNanos(); // 拒绝无法用单调时钟表示的预算。
    }
}
