package com.jaco.hook;

import com.jaco.llm.ChatRequest;
import com.jaco.llm.ChatResponse;
import com.jaco.llm.ToolCall;

/**
 * Agent 生命周期钩子。所有方法都有默认空实现，按需覆盖；
 * 实现注册进 HookChain 后按注册顺序执行。
 *
 * <p>v1 接线的事件点：{@link #onUserPromptSubmit}、{@link #onBeforeRequest}、{@link #onAfterResponse}。
 * 工具相关事件点（onBeforeToolCall/onAfterToolCall/onStop）签名已定，M1 工具接入时接线。
 * 权限确认、审计日志、自动记忆等能力都应实现为 hook，而不是改 agent loop。</p>
 */
public interface AgentHook {

    /** 新会话创建成功或成功切换到其他会话后调用，用于清理会话级状态。 */
    default void onSessionChanged() {
    }

    /** 用户输入提交后、进入本轮处理前调用。返回改写后的 prompt；返回 null 表示拦截，本轮直接终止。 */
    default String onUserPromptSubmit(String prompt) {
        return prompt;
    }

    /** 请求发送前调用（只读观察点；鉴权 header 等写操作由客户端层负责）。 */
    default void onBeforeRequest(ChatRequest request) {
    }

    /** 一轮正常完成后调用（用户中断或出错时不调用）。 */
    default void onAfterResponse(ChatResponse response) {
    }

    /** M1：工具执行前调用。返回 deny 时拒绝执行，拒绝原因将作为工具结果喂回模型，不中断循环。 */
    default HookVerdict onBeforeToolCall(ToolCall call) {
        return HookVerdict.allow();
    }

    /** M1：工具执行后调用。 */
    default void onAfterToolCall(ToolCall call, String result) {
    }

    /** M1：一轮结束时调用。返回 true 表示强制续轮（返回内容追加后继续循环）。 */
    default boolean onStop(ChatResponse response) {
        return false;
    }
}
