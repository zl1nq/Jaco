package com.jaco.agent;

/** onUserPromptSubmit 被 hook 拦截（返回 null）时抛出。 */
public class TurnAbortedException extends RuntimeException {

    public TurnAbortedException() {
        super("turn aborted by hook");
    }
}
