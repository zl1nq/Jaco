package com.jaco.agent;

import com.jaco.config.ProviderConfig;
import com.jaco.hook.HookChain;
import com.jaco.llm.ChatRequest;
import com.jaco.llm.ChatResponse;
import com.jaco.llm.ChatStream;
import com.jaco.llm.Message;
import com.jaco.llm.OpenAiCompatClient;
import com.jaco.llm.Usage;
import com.jaco.session.Session;
import com.jaco.session.SessionStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent loop 的宿主。v1 是纯聊天（一轮 = 一次 LLM 调用），
 * 但结构上与 M1 的工具循环同构：beginTurn 发起、消费方拉取流、endTurn 归档——
 * M1 接入工具调用时在这两者之间插入“解析 tool_calls → 执行 → 结果回填 → 继续拉流”，
 * begin/end 的边界不变。
 */
public final class AgentRunner {

    public static final String DEFAULT_SYSTEM_PROMPT = """
            你是 jaco，一个运行在终端里的助手。回答简洁、直接，适合在命令行中阅读。
            """;

    private final OpenAiCompatClient client;
    private final ProviderConfig provider;
    private final HookChain hooks;
    private final SessionStore sessions;
    private final String systemPrompt;
    private Session session;

    public AgentRunner(OpenAiCompatClient client,
                       ProviderConfig provider,
                       String configuredSystemPrompt,
                       SessionStore sessions,
                       HookChain hooks) {
        this.client = client;
        this.provider = provider;
        this.hooks = hooks;
        this.sessions = sessions;
        this.systemPrompt = configuredSystemPrompt != null && !configuredSystemPrompt.isBlank()
                ? configuredSystemPrompt
                : DEFAULT_SYSTEM_PROMPT;
    }

    public void start() {
        session = sessions.loadLatestOrNew();
    }

    public Session session() {
        return session;
    }

    public void newSession() {
        session = sessions.createNew();
    }

    /**
     * 开始一轮：过 hook → 追加 user 消息 → 发起流式请求。
     * 返回的流由调用方（TUI）消费。
     */
    public ChatStream beginTurn(String prompt) {
        String effective = hooks.onUserPromptSubmit(prompt);
        if (effective == null) {
            throw new TurnAbortedException();
        }
        session.messages().add(Message.user(effective));
        ChatRequest request = new ChatRequest(
                provider.model(),
                buildMessages(),
                provider.temperature(),
                true,
                new ChatRequest.StreamOptions(true));
        hooks.onBeforeRequest(request);
        try {
            return client.chatStream(request);
        } catch (java.io.IOException e) {
            // 请求没成功，刚追加的 user 消息回滚，避免历史里留下没有回应的孤消息
            List<Message> messages = session.messages();
            messages.remove(messages.size() - 1);
            throw new IllegalStateException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("请求被中断", e);
        }
    }

    /**
     * 结束一轮：归档 assistant 回复（中断时保留已收到的部分）、触发 onAfterResponse、落盘。
     */
    public void endTurn(String content, String finishReason, Usage usage, boolean interrupted, Throwable error) {
        if (!content.isEmpty()) {
            session.messages().add(Message.assistant(content));
        }
        if (!interrupted && error == null) {
            hooks.onAfterResponse(new ChatResponse(Message.assistant(content), usage, finishReason));
        }
        sessions.save(session);
    }

    private List<Message> buildMessages() {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(systemPrompt));
        messages.addAll(session.messages());
        return messages;
    }
}
