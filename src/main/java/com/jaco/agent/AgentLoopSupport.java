package com.jaco.agent;

import com.jaco.config.ProviderConfig;
import com.jaco.hook.HookChain;
import com.jaco.llm.*;
import com.jaco.session.Session;
import com.jaco.session.SessionStore;
import com.jaco.agent.subagent.SubagentControl;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.BooleanSupplier;

/** 父子循环共用的流消费、结果归档及上下文压缩；不持有当前会话。 */
public final class AgentLoopSupport {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentLoopSupport.class);
    private final OpenAiCompatClient client;
    private final ProviderConfig provider;
    private final HookChain hooks;
    private final SessionStore sessions;
    private final long contextLimit;
    private final int maxToolResultChars;
    private final SubagentControl control;

    public AgentLoopSupport(OpenAiCompatClient client, ProviderConfig provider, HookChain hooks,
                            SessionStore sessions, long contextLimit, int maxToolResultChars,
                            SubagentControl control) {
        this.client = client;
        this.provider = provider;
        this.hooks = hooks;
        this.sessions = sessions;
        this.contextLimit = contextLimit;
        this.maxToolResultChars = maxToolResultChars;
        this.control = control;
    }

    public IterationResult consumeStream(ChatStream stream, TurnHandle handle,
                                          BooleanSupplier cancelled) throws InterruptedException {
        StringBuilder content = new StringBuilder();
        ToolCallAccumulator accumulator = new ToolCallAccumulator();
        String finishReason = null;
        Usage usage = null;
        Throwable error = null;

        while (true) {
            StreamChunk chunk = stream.poll(200);
            if (cancelled.getAsBoolean()) {
                break;
            }
            if (chunk == null) {
                continue;
            }
            if (chunk instanceof StreamChunk.Delta d) {
                content.append(d.text());
                if (handle != null) {
                    handle.emit(new TurnEvent.Delta(d.text()));
                }
            } else if (chunk instanceof StreamChunk.ToolCallDelta t) {
                accumulator.add(t);
            } else if (chunk instanceof StreamChunk.Done d) {
                finishReason = d.finishReason();
                usage = d.usage();
                break;
            } else if (chunk instanceof StreamChunk.Error e) {
                if (!cancelled.getAsBoolean()) {
                    error = e.cause() != null ? e.cause() : new RuntimeException(e.message());
                }
                break;
            }
        }
        if (handle != null && content.length() > 0 && !cancelled.getAsBoolean()
                && error == null && !accumulator.isEmpty()) {
            // 少数模型在 tool_calls 响应里也夹带正文：保留进事件流但不进最终 assistant 消息
            handle.emit(new TurnEvent.Delta("\n"));
        }
        return new IterationResult(content.toString(), accumulator.build(), finishReason, usage, error);
    }

    public String gateToolResult(Session owner, String result) {
        if (result.length() <= maxToolResultChars) {
            return result;
        }
        String status;
        try {
            sessions.appendArchivedOutput(owner, result);
            status = "工具输出过长，完整内容已归档，可用 recall 检索";
        } catch (UncheckedIOException e) {
            log.warn("工具输出归档失败: {}", e.getMessage());
            status = "工具输出过长，归档失败；仅保留预览，无法恢复，请缩小范围重新读取";
        }
        return clipMiddle(result, maxToolResultChars, status);
    }

    /** 纯裁剪不承诺归档；提示、分隔符和 Unicode 边界都计入总预算。 */
    public static String clipMiddle(String text, int budget) {
        return clipMiddle(text, budget, "工具输出过长，仅保留头尾预览");
    }

    private static String clipMiddle(String text, int budget, String status) {
        if (text.length() <= budget) return text;
        String header = "(" + status + "，原 " + text.length() + " 字符)\n";
        String gap = "\n…(中间省略)…\n";
        if (header.length() + gap.length() > budget) return unicodePrefix("(结果已裁剪)", Math.max(0, budget));
        int available = budget - header.length() - gap.length();
        String head = unicodePrefix(text, available / 2);
        int tailStart = text.length() - (available - available / 2);
        if (tailStart < text.length() && tailStart > 0 && Character.isLowSurrogate(text.charAt(tailStart))
                && Character.isHighSurrogate(text.charAt(tailStart - 1))) tailStart++;
        return header + head + gap + text.substring(tailStart);
    }

    public static String unicodePrefix(String text, int limit) {
        int end = Math.min(text.length(), limit);
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) end--;
        return text.substring(0, end);
    }

    public ContextCompactor.Outcome compactContext(ContextCompactor compactor, List<Message> messages,
            long promptTokens, boolean forced, java.util.function.Consumer<String> notices, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            long tokens = ContextCompactor.estimateTokens(messages);
            return new ContextCompactor.Outcome(false, tokens, tokens);
        }
        try {
            return forced ? compactor.forceCompact(messages, notices, old -> summarizeOldMessages(old, cancelled))
                    : compactor.compactIfNeeded(messages, promptTokens, notices, old -> summarizeOldMessages(old, cancelled));
        } catch (UncheckedIOException e) {
            notices.accept("上下文归档失败，保留现有消息：" + e.getMessage());
            long tokens = ContextCompactor.estimateTokens(messages);
            return new ContextCompactor.Outcome(false, tokens, tokens);
        }
    }

    public static boolean isContextOverflow(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) {
            return false;
        }
        String m = msg.toLowerCase();
        return m.contains("context length") || m.contains("context_length") || m.contains("maximum context")
                || m.contains("too long") || m.contains("too many tokens") || m.contains("reduce the length");
    }

    public static Usage mergeUsage(Usage a, Usage b) {
        return new Usage(
                a.promptTokens() + b.promptTokens(),
                a.completionTokens() + b.completionTokens(),
                a.totalTokens() + b.totalTokens());
    }

    /** 有界、可取消的摘要调用；失败时由压缩器降级为已归档内容的线索。 */
    private String summarizeOldMessages(List<Message> oldMessages, BooleanSupplier cancelled) {
        StringBuilder transcript = new StringBuilder();
        int inputLimit = (int) Math.min(12000, Math.max(0, contextLimit / 3));
        if (inputLimit < 128) throw new IllegalStateException("摘要输入预算不足");
        for (Message m : oldMessages) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IllegalStateException("摘要被取消");
            String who = switch (m.role()) {
                case USER -> "用户";
                case ASSISTANT -> "assistant";
                case TOOL -> "工具结果";
                case SYSTEM -> "system";
            };
            String text = m.content() != null ? m.content() : "";
            if (m.toolCalls() != null) {
                StringBuilder calls = new StringBuilder();
                for (var tc : m.toolCalls()) {
                    if (calls.length() > 0) {
                        calls.append("; ");
                    }
                    calls.append(tc.function().name()).append("(")
                            .append(tc.function().arguments() != null && tc.function().arguments().length() > 200
                                    ? tc.function().arguments().substring(0, 200) + "…"
                                    : tc.function().arguments())
                            .append(")");
                }
                text = text + "[调用工具: " + calls + "]";
            }
            if (text.length() > 4000) {
                text = text.substring(0, 4000) + "…(截断)";
            }
            transcript.append(who).append(": ").append(text.replace('\n', ' ')).append("\n");
            if (transcript.length() >= inputLimit) break;
        }
        ChatRequest request = new ChatRequest(
                provider.model(),
                List.of(
                        Message.system("""
                                你是对话摘要器。把对话历史总结成结构化要点，供后续对话作为上下文使用。
                                必须包含：用户的当前目标、已做出的关键决定、已完成的文件改动或执行过的命令、未完成事项。
                                只输出要点，使用简洁中文，不要客套。"""),
                        Message.user(unicodePrefix(transcript.toString(), inputLimit))),
                0.3,
                true,
                new ChatRequest.StreamOptions(true),
                null);
        ChatStream stream = null;
        try {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IllegalStateException("摘要被取消");
            hooks.onBeforeRequest(request);
            stream = client.chatStream(request);
            if (control != null) control.registerStream(stream);
            StringBuilder summary = new StringBuilder();
            while (true) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IllegalStateException("摘要被取消");
                StreamChunk chunk = stream.poll(200);
                if (chunk instanceof StreamChunk.Delta d) {
                    summary.append(d.text());
                    if (summary.length() >= 4000) break;
                } else if (chunk instanceof StreamChunk.Done || chunk instanceof StreamChunk.Error e) {
                    if (control != null && chunk instanceof StreamChunk.Done done) control.addUsage(done.usage());
                    if (chunk instanceof StreamChunk.Error err) {
                        throw new IllegalStateException(err.message());
                    }
                    break;
                }
            }
            return unicodePrefix(summary.toString(), 4000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("摘要被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("摘要调用失败: " + e.getMessage(), e);
        } finally {
            if (stream != null) {
                try { stream.cancel(); } finally { if (control != null) control.clearStream(stream); }
            }
        }
    }

    public record IterationResult(String content, List<ToolCall> toolCalls,
                                  String finishReason, Usage usage, Throwable error) { }
}
