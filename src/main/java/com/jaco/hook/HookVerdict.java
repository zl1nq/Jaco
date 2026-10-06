package com.jaco.hook;

/** onBeforeToolCall 的裁决结果。 */
public record HookVerdict(boolean proceed, String denyReason) {

    public static HookVerdict allow() {
        return new HookVerdict(true, null);
    }

    public static HookVerdict deny(String reason) {
        return new HookVerdict(false, reason);
    }
}
