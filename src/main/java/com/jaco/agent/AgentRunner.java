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
    private Session session;

    public AgentRunner(OpenAiCompatClient client,
                       ProviderConfig provider,
                       String configuredSystemPrompt,
                       SessionStore sessions,
                       HookChain hooks,
                       ToolRegistry registry,
                       ToolSandbox sandbox,
                       Path workspaceRoot,
                       String shellChoice,
                       int maxIterations) {
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

            Usage totalUsage = null;
            String finishReason = null;
            Throwable error = null;
            boolean interrupted = false;
            int iterations = 0;

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
                    }
                    if (result.error() != null) {
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
                handle.processRef(), handle::isCancelled);
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
            session.messages().add(Message.toolResult(call.id(), result));
            hooks.onAfterToolCall(call, result);
            handle.emit(new TurnEvent.ToolCallEnd(name, ok, summarizeResult(result)));
        }
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

    private Usage mergeUsage(Usage a, Usage b) {
        return new Usage(
                a.promptTokens() + b.promptTokens(),
                a.completionTokens() + b.completionTokens(),
                a.totalTokens() + b.totalTokens());
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
