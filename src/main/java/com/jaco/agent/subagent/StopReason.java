package com.jaco.agent.subagent;

public enum StopReason {
    USER_CANCELLED, PARENT_CANCELLED, SCOPE_CLOSED, SESSION_CHANGED, TIMEOUT, SHUTDOWN;

    ExitReason exitReason() {
        return switch (this) {
            case TIMEOUT -> ExitReason.TIMED_OUT;
            case SHUTDOWN -> ExitReason.SHUTDOWN;
            default -> ExitReason.CANCELLED;
        };
    }
}
