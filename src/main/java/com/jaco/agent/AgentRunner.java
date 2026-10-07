package com.jaco.agent;

import com.jaco.config.ProviderConfig;
import com.jaco.hook.HookChain;
import com.jaco.hook.HookVerdict;
import com.jaco.llm.ChatRequest;
import com.jaco.llm.ChatResponse;
import com.jaco.llm.ChatStream;
import com.jaco.llm.Message;
import com.jaco.llm.OpenAiCompatClient;
import com.jaco.llm.Role;
import com.jaco.llm.StreamChunk;
import com.jaco.llm.ToolCall;
import com.jaco.llm.Usage;
import com.jaco.session.Session;
import com.jaco.session.SessionStore;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolRegistry;
import com.jaco.tool.ToolSandbox;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent loop 的宿主与驱动者。核心信条：循环本体保持最小——
 * 请求 LLM → 无 tool_calls 即停 → 有则执行工具、结果回填 → 继续；
 * 一切外围能力（权限、审计、续轮）都以 hook 或事件形式挂在循环外。
 *
 * <p>loop 在独立线程运行，通过 TurnHandle 向消费方（TUI）吐 TurnEvent。
 */
public final class AgentRunner {

    public static final String DEFAULT_SYSTEM_PROMPT = """
            你是 jaco，一个运行在终端里的助手，可以调用工具读写文件、执行命令。
            回答简洁、直接，适合在命令行中阅读。
            """;

    private final OpenAiCompatClient client;
    private final ProviderConfig provider;
    private final HookChain hooks;
    private final SessionStore sessions;
    private final ToolRegistry registry;
    private final ToolSandbox sandbox;
    private final Path workspaceRoot;
    private final String configuredSystemPrompt;
    private final String shellChoice;
    private final int maxIterations;
    private final long contextLimit;
    private final int maxToolResultChars;
    private Session session;
    private long lastPromptTokens;

    public AgentRunner(OpenAiCompatClient client,
                       ProviderConfig provider,
                       String configuredSystemPrompt,
                       SessionStore sessions,
                       HookChain hooks,
                       ToolRegistry registry,
                       ToolSandbox sandbox,
                       Path workspaceRoot,
                       String shellChoice,
                       int maxIterations,
                       long contextLimit,
                       int maxToolResultChars) {
        this.client = client;
        this.provider = provider;
        this.hooks = hooks;
        this.sessions = sessions;
        this.registry = registry;
        this.sandbox = sandbox;
        this.workspaceRoot = workspaceRoot;
        this.configuredSystemPrompt = configuredSystemPrompt;
        this.shellChoice = shellChoice;
        this.maxIterations = Math.max(1, maxIterations);
        this.contextLimit = contextLimit;
        this.maxToolResultChars = maxToolResultChars;
    }

    public void start() {
        session = sessions.loadLatestOrNew();
    }

    public Session session() {
        return session;
    }

    public String modelName() {
        return provider.model();
    }

    public void newSession() {
        session = sessions.createNew();
    }

    /** 启动一轮：loop 在后台线程运行，返回的事件句柄由调用方消费。 */
    public TurnHandle runTurn(String prompt) {
        TurnHandle handle = new TurnHandle();
        Thread thread = new Thread(() -> {
            TurnEventSink.install(handle);
            try {
                loop(prompt, handle);
            } finally {
                TurnEventSink.clear();
            }
        }, "jaco-agent");
        handle.setLoopThread(thread);
        thread.start();
        return handle;
    }

    private void loop(String prompt, TurnHandle handle) {
        try {
            String effective = hooks.onUserPromptSubmit(prompt);
            if (effective == null) {
                sessions.save(session);
                handle.emit(new TurnEvent.Done(null, null, false, true, null, 0));
                return;
            }
            session.messages().add(Message.user(effective));

            // 压缩检查发生在轮开始（循环外一层，循环本体不感知）
            ContextCompactor compactor = new ContextCompactor(sessions, session, contextLimit);
            var outcome = compactor.compactIfNeeded(session.messages(), lastPromptTokens,
                    notice -> handle.emit(new TurnEvent.Notice(notice)), this::summarizeOldMessages);
            if (outcome.compacted()) {
                sessions.save(session);
                lastPromptTokens = outcome.tokensAfter();
            }

            Usage totalUsage = null;
            String finishReason = null;
            Throwable error = null;
            boolean interrupted = false;
            int iterations = 0;
            boolean overflowRetried = false;

            while (!handle.isCancelled()) {
                if (++iterations > maxIterations) {
                    finishReason = "max_iterations";
                    break;
                }
                ChatStream stream = null;
                try {
                    ChatRequest request = new ChatRequest(
                            provider.model(),
                            buildMessages(),
                            provider.temperature(),
                            true,
                            new ChatRequest.StreamOptions(true),
                            registry.definitions());
                    hooks.onBeforeRequest(request);
                    stream = client.chatStream(request);
                    handle.setCurrentStream(stream);

                    IterationResult result = consumeStream(stream, handle);
                    if (result.usage() != null) {
                        totalUsage = totalUsage == null ? result.usage() : mergeUsage(totalUsage, result.usage());
                        lastPromptTokens = result.usage().promptTokens();
                    }
                    if (result.error() != null) {
                        // 上下文超限是可恢复的：强制压缩后重试一次（估算与真实 token 的偏差兜底）
                        if (!overflowRetried && isContextOverflow(result.error())) {
                            overflowRetried = true;
                            var forced = compactor.forceCompact(session.messages(),
                                    notice -> handle.emit(new TurnEvent.Notice(notice)), this::summarizeOldMessages);
                            lastPromptTokens = forced.tokensAfter();
                            sessions.save(session);
                            continue;
                        }
                        error = result.error();
                        break;
                    }
                    if (handle.isCancelled()) {
                        interrupted = true;
                        break;
                    }

                    List<ToolCall> calls = result.toolCalls();
                    if (calls.isEmpty()) {
                        session.messages().add(Message.assistant(result.content()));
                        finishReason = result.finishReason();
                        ChatResponse response = new ChatResponse(
                                Message.assistant(result.content()), result.usage(), result.finishReason());
                        if (hooks.onStop(response)) {
                            session.messages().add(Message.user("[onStop hook] 请继续"));
                            continue;
                        }
                        break;
                    }

                    session.messages().add(new Message(
                            Role.ASSISTANT,
                            result.content().isEmpty() ? null : result.content(),
                            calls, null, null));
                    executeTools(calls, handle);
                    if (handle.isCancelled()) {
                        interrupted = true;
                        break;
                    }
                } catch (IOException e) {
                    error = e;
                    break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    interrupted = true;
                    break;
                } finally {
                    if (stream != null) {
                        handle.setCurrentStream(null);
                    }
                }
            }
            if (handle.isCancelled()) {
                interrupted = true;
            }
            sessions.save(session);
            handle.emit(new TurnEvent.Done(totalUsage, finishReason, interrupted, false, error, iterations));
        } catch (Exception fatal) {
            sessions.save(session);
            handle.emit(new TurnEvent.Done(null, null, false, false, fatal, 0));
        }
    }

    /** 消费一次 LLM 流：转发正文增量、累积 tool_calls、聚合 usage。 */
    private IterationResult consumeStream(ChatStream stream, TurnHandle handle) throws InterruptedException {
        StringBuilder content = new StringBuilder();
        ToolCallAccumulator accumulator = new ToolCallAccumulator();
        String finishReason = null;
        Usage usage = null;
        Throwable error = null;

        while (true) {
            StreamChunk chunk = stream.poll(200);
            if (handle.isCancelled()) {
                break;
            }
            if (chunk == null) {
                continue;
            }
            if (chunk instanceof StreamChunk.Delta d) {
                content.append(d.text());
                handle.emit(new TurnEvent.Delta(d.text()));
            } else if (chunk instanceof StreamChunk.ToolCallDelta t) {
                accumulator.add(t);
            } else if (chunk instanceof StreamChunk.Done d) {
                finishReason = d.finishReason();
                usage = d.usage();
                break;
            } else if (chunk instanceof StreamChunk.Error e) {
                if (!handle.isCancelled()) {
                    error = e.cause() != null ? e.cause() : new RuntimeException(e.message());
                }
                break;
            }
        }
        if (content.length() > 0 && !handle.isCancelled() && error == null && !accumulator.isEmpty()) {
            // 少数模型在 tool_calls 响应里也夹带正文：保留进事件流但不进最终 assistant 消息
            handle.emit(new TurnEvent.Delta("\n"));
        }
        return new IterationResult(content.toString(), accumulator.build(), finishReason, usage, error);
    }

    /** 串行执行工具；每个 tool_call 都必须产生 tool result（协议要求成对）。 */
    private void executeTools(List<ToolCall> calls, TurnHandle handle) {
        ToolContext ctx = new ToolContext(workspaceRoot, sandbox, shellChoice,
                handle.processRef(), handle::isCancelled, sessions.archivePath(session));
        for (ToolCall call : calls) {
            String name = call.function().name() != null ? call.function().name() : "?";
            String summary = registry.summaryOf(call);
            handle.emit(new TurnEvent.ToolCallStart(name, summary));

            String result;
            boolean ok = true;
            HookVerdict verdict = hooks.onBeforeToolCall(call);
            if (!verdict.proceed()) {
                result = "Permission denied: " + verdict.denyReason();
                ok = false;
            } else if (handle.isCancelled()) {
                result = "ERROR: interrupted by user";
                ok = false;
            } else {
                try {
                    result = registry.execute(call, ctx);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result = "ERROR: interrupted by user";
                    ok = false;
                } catch (Exception e) {
                    result = "ERROR: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                    ok = false;
                }
            }
            result = gateToolResult(result);
            session.messages().add(Message.toolResult(call.id(), result));
            hooks.onAfterToolCall(call, result);
            handle.emit(new TurnEvent.ToolCallEnd(name, ok, summarizeResult(result)));
        }
    }

    /**
     * 单条工具输出的硬闸门：超限即整条落盘归档，回填头尾预览。在结果入口处生效，
     * 不依赖轮开始的压缩周期——任何时刻消息列表里的单条 tool_result 都有上界。
     */
    private String gateToolResult(String result) {
        if (result.length() <= maxToolResultChars) {
            return result;
        }
        sessions.appendArchivedOutput(session, result);
        return clipMiddle(result, maxToolResultChars / 2);
    }

    /** 头尾各留 keepEach 字符，中段以省略标记代替，并注明完整内容可 recall。 */
    static String clipMiddle(String text, int keepEach) {
        int head = Math.min(keepEach, text.length());
        int tail = Math.min(keepEach, text.length());
        return "(工具输出过大已存档，共 " + text.length() + " 字符，保留头尾各 " + head
                + " 字符；完整内容可用 recall 工具取回)\n"
                + text.substring(0, head)
                + "\n…(中间省略)…\n"
                + text.substring(text.length() - tail);
    }

    private String summarizeResult(String result) {
        if (result == null) {
            return "";
        }
        String firstLine = result.lines().findFirst().orElse("");
        long lineCount = result.lines().count();
        String s = firstLine.length() > 100 ? firstLine.substring(0, 100) + "…" : firstLine;
        return lineCount > 1 ? s + " …(" + lineCount + " 行)" : s;
    }

    /** 识别"上下文超限"类错误：各家措辞不一，按关键词粗匹配。 */
    private static boolean isContextOverflow(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) {
            return false;
        }
        String m = msg.toLowerCase();
        return m.contains("context length") || m.contains("context_length") || m.contains("maximum context")
                || m.contains("too long") || m.contains("too many tokens") || m.contains("reduce the length");
    }

    private Usage mergeUsage(Usage a, Usage b) {
        return new Usage(
                a.promptTokens() + b.promptTokens(),
                a.completionTokens() + b.completionTokens(),
                a.totalTokens() + b.totalTokens());
    }

    /** 压缩第 ④ 步的摘要调用：同步消费一次流式请求。失败时抛出，由压缩器降级为归档标记。 */
    private String summarizeOldMessages(List<Message> oldMessages) {
        StringBuilder transcript = new StringBuilder();
        for (Message m : oldMessages) {
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
        }
        ChatRequest request = new ChatRequest(
                provider.model(),
                List.of(
                        Message.system("""
                                你是对话摘要器。把对话历史总结成结构化要点，供后续对话作为上下文使用。
                                必须包含：用户的当前目标、已做出的关键决定、已完成的文件改动或执行过的命令、未完成事项。
                                只输出要点，使用简洁中文，不要客套。"""),
                        Message.user(transcript.toString())),
                0.3,
                true,
                new ChatRequest.StreamOptions(true),
                null);
        try {
            ChatStream stream = client.chatStream(request);
            StringBuilder summary = new StringBuilder();
            while (true) {
                StreamChunk chunk = stream.poll(1000);
                if (chunk instanceof StreamChunk.Delta d) {
                    summary.append(d.text());
                } else if (chunk instanceof StreamChunk.Done || chunk instanceof StreamChunk.Error e) {
                    if (chunk instanceof StreamChunk.Error err) {
                        throw new IllegalStateException(err.message());
                    }
                    break;
                }
            }
            return summary.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("摘要被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("摘要调用失败: " + e.getMessage(), e);
        }
    }

    private List<Message> buildMessages() {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(buildSystemPrompt()));
        messages.addAll(session.messages());
        return messages;
    }

    /** 教程 PROMPT_SECTIONS 模式：每轮重新拼装，M2+ 的动态上下文走同样的入口。 */
    private String buildSystemPrompt() {
        String identity = configuredSystemPrompt != null && !configuredSystemPrompt.isBlank()
                ? configuredSystemPrompt
                : DEFAULT_SYSTEM_PROMPT;
        String shell = shellChoice == null ? "auto" : shellChoice;
        return identity + """

                # 环境
                - 工作目录: %s
                - 操作系统: %s
                - Shell: %s
                - 日期: %s

                # 工具使用守则
                - 修改文件前先 read_file 了解现状；大文件用 offset/limit 分页读取
                - run_command 会真实执行命令，命令必须与任务直接相关
                - 路径只允许在工作目录内
                """.formatted(workspaceRoot, System.getProperty("os.name"), shell, LocalDate.now());
    }

    private record IterationResult(String content,
                                   List<ToolCall> toolCalls,
                                   String finishReason,
                                   Usage usage,
                                   Throwable error) {
    }
}
