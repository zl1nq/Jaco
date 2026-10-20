package com.jaco.agent.subagent;

import com.jaco.agent.AgentLoopSupport;
import com.jaco.agent.ContextCompactor;
import com.jaco.config.ProviderConfig;
import com.jaco.hook.HookChain;
import com.jaco.llm.*;
import com.jaco.session.*;
import com.jaco.tool.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import static com.jaco.agent.AgentLoopSupport.isContextOverflow;
import static com.jaco.agent.AgentLoopSupport.unicodePrefix;

/** 只读子循环；所有可变运行状态均属于本次 control 和临时 Session。 */
public final class SubagentRunner {
    private final OpenAiCompatClient client;
    private final ProviderConfig provider;
    private final HookChain hooks;
    private final SessionStore sessions;
    private final ToolRegistry registry;
    private final ToolSandbox sandbox;
    private final Path workspaceRoot;
    private final String shellChoice;
    private final long contextLimit;
    private final int maxToolResultChars;

    public SubagentRunner(OpenAiCompatClient client, ProviderConfig provider, HookChain hooks,
                          SessionStore sessions, ToolRegistry registry, ToolSandbox sandbox,
                          Path workspaceRoot, String shellChoice, long contextLimit, int maxToolResultChars) {
        this.client = client;
        this.provider = provider;
        this.hooks = hooks;
        this.sessions = sessions;
        this.registry = registry;
        this.sandbox = sandbox;
        this.workspaceRoot = workspaceRoot;
        this.shellChoice = shellChoice;
        this.contextLimit = contextLimit;
        this.maxToolResultChars = maxToolResultChars;
    }

    public SubagentManager.Completion run(SubagentControl control) {
        var spec = control.spec();
        var profile = spec.profile();
        Set<String> tools = spec.tools();
        BooleanSupplier cancelled = control::isStopped;
        var support = new AgentLoopSupport(client, provider, hooks, sessions, contextLimit, maxToolResultChars, control);
        Session child = Session.create(spec.parentSessionId() + "-sub-" + control.id(), System.currentTimeMillis());
        List<Message> messages = child.messages();
        String systemPrompt = profile.systemPrompt();
        if (!tools.equals(profile.tools())) {
            systemPrompt += "\n本次运行可用工具: " + (tools.isEmpty() ? "无，只根据任务说明回答，不调用工具"
                    : tools.stream().sorted().toList()) + "。不得调用其他工具。";
        }
        messages.add(Message.system(systemPrompt));
        messages.add(Message.user(spec.prompt()));
        List<ToolDefinition> defs = registry.definitions(tools);
        ContextCompactor compactor = new ContextCompactor(sessions, child, contextLimit);
        long promptTokens = 0;
        boolean overflowRetried = false;
        try {
            for (int i = 0; i < spec.maxIterations() && !cancelled.getAsBoolean(); i++) {
                control.iteration(i + 1);
                ChatStream stream = null;
                try {
                    control.phase(SubagentPhase.COMPACTING_CONTEXT);
                    var outcome = support.compactContext(compactor, messages, promptTokens, false, n -> {}, cancelled);
                    if (outcome.compacted()) promptTokens = outcome.tokensAfter();
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) return SubagentManager.Completion.failed("子任务被中断");
                    ChatRequest request = new ChatRequest(provider.model(), List.copyOf(messages), provider.temperature(),
                            true, new ChatRequest.StreamOptions(true), defs.isEmpty() ? null : defs);
                    control.phase(SubagentPhase.REQUESTING_MODEL);
                    hooks.onBeforeRequest(request);
                    stream = client.chatStream(request);
                    control.registerStream(stream);
                    AgentLoopSupport.IterationResult result = support.consumeStream(stream, null, cancelled);
                    if (result.usage() != null) promptTokens = result.usage().promptTokens();
                    control.addUsage(result.usage());
                    if (result.error() != null) {
                        if (!overflowRetried && isContextOverflow(result.error())) {
                            overflowRetried = true;
                            control.phase(SubagentPhase.COMPACTING_CONTEXT);
                            var forced = support.compactContext(compactor, messages, 0, true, n -> {}, cancelled);
                            if (forced.compacted()) { promptTokens = forced.tokensAfter(); continue; }
                            return failure("上下文超限，压缩未能缩小请求", messages, control);
                        }
                        return failure(result.error().getMessage() != null
                                ? result.error().getMessage() : result.error().getClass().getSimpleName(), messages, control);
                    }
                    if (cancelled.getAsBoolean()) {
                        return SubagentManager.Completion.failed("子任务被中断");
                    }
                    List<ToolCall> calls = result.toolCalls();
                    if (calls.isEmpty()) {
                        return SubagentManager.Completion.succeeded(result.content().isBlank() ? "(子任务无输出)" : result.content());
                    }
                    messages.add(new Message(Role.ASSISTANT,
                            result.content().isEmpty() ? null : result.content(), calls, null, null));
                    control.phase(SubagentPhase.EXECUTING_TOOL);
                    executeSubagentTools(calls, child, control, support, tools);
                    control.progress(partialFindings(messages));
                } catch (IOException e) {
                    if (!overflowRetried && isContextOverflow(e)) {
                        overflowRetried = true;
                        control.phase(SubagentPhase.COMPACTING_CONTEXT);
                        var forced = support.compactContext(compactor, messages, 0, true, n -> {}, cancelled);
                        if (forced.compacted()) { promptTokens = forced.tokensAfter(); continue; }
                    }
                    return failure(e.getMessage(), messages, control);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return SubagentManager.Completion.failed("子任务被中断");
                } finally {
                    if (stream != null) {
                        try { stream.cancel(); } finally { control.clearStream(stream); }
                    }
                }
            }
        } catch (RuntimeException e) {
            return failure(e.getMessage(), messages, control);
        }
        control.progress(partialFindings(messages));
        return new SubagentManager.Completion(ExitReason.BUDGET_EXCEEDED, "",
                new SubagentResult.ErrorInfo("IterationLimit", "达到迭代上限 " + spec.maxIterations()));
    }

    private SubagentManager.Completion failure(String reason, List<Message> messages, SubagentControl control) {
        control.progress(partialFindings(messages));
        return SubagentManager.Completion.failed(String.valueOf(reason));
    }

    private String partialFindings(List<Message> messages) {
        StringBuilder progress = new StringBuilder();
        int start = Math.max(0, messages.size() - 6);
        for (int i = start; i < messages.size(); i++) {
            Message m = messages.get(i);
            if ((m.role() == Role.ASSISTANT || m.role() == Role.TOOL) && m.content() != null && !m.content().isBlank()) {
                progress.append("部分进展（资料）: ").append(unicodePrefix(m.content(), 500)).append("\n");
            }
        }
        return progress.toString();
    }

    /** 子 agent 的工具执行：归档及 recall 严格使用本次子任务的运行状态。 */
    private void executeSubagentTools(List<ToolCall> calls, Session child, SubagentControl control,
                                      AgentLoopSupport support, Set<String> tools) {
        BooleanSupplier cancelled = control::isStopped;
        ToolContext ctx = new ToolContext(workspaceRoot, sandbox, shellChoice,
                control.processRef(), cancelled, sessions.archivePath(child), maxToolResultChars);
        for (ToolCall call : calls) {
            String name = call.function().name() != null ? call.function().name() : "?";
            String result;
            if (!tools.contains(name)) {
                result = "ERROR: 子 agent 只允许只读工具 " + tools;
            } else {
                try {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    result = registry.execute(call, ctx);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result = "ERROR: interrupted by user";
                } catch (Exception e) {
                    result = "ERROR: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                }
            }
            result = support.gateToolResult(child, result);
            child.messages().add(Message.toolResult(call.id(), result));
            control.progress(partialFindings(child.messages()));
        }
    }

}
