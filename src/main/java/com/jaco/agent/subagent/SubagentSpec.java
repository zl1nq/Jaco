package com.jaco.agent.subagent;

import java.time.Duration;
import java.util.Objects;

/** 父归属及预算在接收任务前固定；模型、沙箱和工具配置由不可变 runner 持有。 */
public record SubagentSpec(String parentSessionId, String parentTurnId, String prompt,
                           Duration timeout, int maxIterations) {
    public SubagentSpec {
        Objects.requireNonNull(parentSessionId);
        Objects.requireNonNull(parentTurnId);
        Objects.requireNonNull(prompt);
        Objects.requireNonNull(timeout);
        if (prompt.isBlank() || timeout.isNegative() || timeout.isZero() || maxIterations < 1) {
            throw new IllegalArgumentException("子任务说明及预算无效");
        }
        timeout.toNanos(); // 拒绝无法用单调时钟表示的预算。
    }
}
