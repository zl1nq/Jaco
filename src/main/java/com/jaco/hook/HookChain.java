package com.jaco.hook;

import com.jaco.llm.ChatRequest;
import com.jaco.llm.ChatResponse;
import com.jaco.llm.ToolCall;

import java.util.List;

/** 按注册顺序执行一组 hook。 */
public final class HookChain {

    private final List<AgentHook> hooks;

    public HookChain(List<AgentHook> hooks) {
        this.hooks = List.copyOf(hooks);
    }

    public void onSessionChanged() {
        for (AgentHook hook : hooks) {
            hook.onSessionChanged();
        }
    }

    /** 依次改写 prompt；任一 hook 返回 null 即拦截。 */
    public String onUserPromptSubmit(String prompt) {
        for (AgentHook hook : hooks) {
            prompt = hook.onUserPromptSubmit(prompt);
            if (prompt == null) {
                return null;
            }
        }
        return prompt;
    }

    public void onBeforeRequest(ChatRequest request) {
        for (AgentHook hook : hooks) {
            hook.onBeforeRequest(request);
        }
    }

    public void onAfterResponse(ChatResponse response) {
        for (AgentHook hook : hooks) {
            hook.onAfterResponse(response);
        }
    }

    /** M1：任一 hook 否决即拒绝，取第一个否决原因。 */
    public HookVerdict onBeforeToolCall(ToolCall call) {
        for (AgentHook hook : hooks) {
            HookVerdict verdict = hook.onBeforeToolCall(call);
            if (!verdict.proceed()) {
                return verdict;
            }
        }
        return HookVerdict.allow();
    }

    public void onAfterToolCall(ToolCall call, String result) {
        for (AgentHook hook : hooks) {
            hook.onAfterToolCall(call, result);
        }
    }

    public boolean onStop(ChatResponse response) {
        for (AgentHook hook : hooks) {
            if (hook.onStop(response)) {
                return true;
            }
        }
        return false;
    }
}
