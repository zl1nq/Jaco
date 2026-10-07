package com.jaco.agent;

import com.jaco.config.ProviderConfig;
import com.jaco.hook.HookChain;
import com.jaco.llm.ChatRequest;
import com.jaco.llm.ChatStream;
import com.jaco.llm.Message;
import com.jaco.llm.OpenAiCompatClient;
import com.jaco.llm.Role;
import com.jaco.llm.StreamChunk;
import com.jaco.llm.ToolCall;
import com.jaco.llm.TestStreams;
import com.jaco.session.SessionStore;
import com.jaco.tool.ToolRegistry;
import com.jaco.tool.ToolSandbox;
import com.jaco.tool.builtin.GrepTool;
import com.jaco.tool.builtin.ListDirTool;
import com.jaco.tool.builtin.RecallTool;
import com.jaco.tool.builtin.ReadFileTool;
import com.jaco.tool.builtin.TaskTool;
import com.jaco.tool.builtin.WriteFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunnerSubagentTest {

    @TempDir
    Path tmp;

    /** 脚本化假客户端：按调用次序回放预置的流，并记录收到的请求。 */
    static class FakeClient extends OpenAiCompatClient {
        private final List<ChatStream> streams;
        final List<ChatRequest> requests = new ArrayList<>();
        int cursor = 0;

        FakeClient(List<ChatStream> streams) {
            super("http://localhost", "k");
            this.streams = streams;
        }

        @Override
        public ChatStream chatStream(ChatRequest request) {
            requests.add(request);
            return streams.get(Math.min(cursor++, streams.size() - 1));
        }
    }

    private AgentRunner runner(FakeClient client) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        registry.register(new ListDirTool());
        registry.register(new GrepTool());
        registry.register(new RecallTool());
        registry.register(new TaskTool());
        SessionStore store = new SessionStore(tmp);
        AgentRunner agent = new AgentRunner(client,
                new ProviderConfig("http://localhost", "k", "m", null),
                null, store, new HookChain(List.of()), registry,
                new ToolSandbox(tmp, List.of()), tmp, "auto", 25, 100_000, 8000);
        agent.start();
        return agent;
    }

    private static String render(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            sb.append(m.role()).append(':').append(m.content() == null ? "" : m.content()).append('\n');
        }
        return sb.toString();
    }

    @Test
    void runsToolRoundThenReturnsReport() throws Exception {
        ChatStream s1 = TestStreams.of(
                new StreamChunk.ToolCallDelta(0, "c1", "grep", "{\"pattern\":\"x\"}"),
                new StreamChunk.Done("tool_calls", null));
        ChatStream s2 = TestStreams.of(
                new StreamChunk.Delta("最终报告"),
                new StreamChunk.Done("stop", null));
        FakeClient client = new FakeClient(List.of(s1, s2));

        String report = runner(client).runSubagent("找找 x", () -> false);

        assertEquals("最终报告", report);
        assertEquals(2, client.requests.size());

        // 第一次请求只携带只读工具定义，且不含 task（禁止递归）
        Set<String> names = client.requests.get(0).tools().stream()
                .map(t -> t.function().name()).collect(Collectors.toSet());
        assertEquals(Set.of("read_file", "list_dir", "grep", "recall"), names);

        // 第二次请求带上了 assistant(tool_calls) 与子 agent 的 tool result
        String msgs = render(client.requests.get(1).messages());
        assertTrue(msgs.contains("ASSISTANT:"));
        assertTrue(msgs.contains("TOOL:"));
    }

    @Test
    void writeToolsDeniedInsideSubagent() throws Exception {
        ChatStream s1 = TestStreams.of(
                new StreamChunk.ToolCallDelta(0, "c1", "write_file", "{\"path\":\"x\",\"content\":\"y\"}"),
                new StreamChunk.Done("tool_calls", null));
        ChatStream s2 = TestStreams.of(
                new StreamChunk.Delta("收到拒绝，放弃"),
                new StreamChunk.Done("stop", null));
        FakeClient client = new FakeClient(List.of(s1, s2));

        assertEquals("收到拒绝，放弃", runner(client).runSubagent("改文件", () -> false));

        String msgs = render(client.requests.get(1).messages());
        assertTrue(msgs.contains("ERROR: 子 agent 只允许只读工具"));
    }

    @Test
    void cancelledBeforeStartNeverCallsClient() throws Exception {
        FakeClient client = new FakeClient(List.of());
        assertEquals("(子任务被中断)", runner(client).runSubagent("x", () -> true));
        assertEquals(0, client.requests.size());
    }

    @Test
    void directAnswerWithoutToolCalls() throws Exception {
        ChatStream s1 = TestStreams.of(
                new StreamChunk.Delta("直接答案"),
                new StreamChunk.Done("stop", null));
        FakeClient client = new FakeClient(List.of(s1));
        assertEquals("直接答案", runner(client).runSubagent("问一句", () -> false));
    }

    @Test
    void emitsStartAndEndNoticesViaParentSink() throws Exception {
        FakeClient client = new FakeClient(List.of(TestStreams.of(
                new StreamChunk.Delta("答案"),
                new StreamChunk.Done("stop", null))));
        AgentRunner agent = runner(client);

        TurnHandle handle = new TurnHandle();
        Thread caller = Thread.ofVirtual().start(() -> {
            TurnEventSink.install(handle);
            try {
                agent.runSubagent("帮我调研", () -> false);
            } finally {
                TurnEventSink.clear();
            }
        });
        caller.join(10_000);

        List<String> notices = new ArrayList<>();
        TurnEvent event;
        while ((event = handle.poll(50)) != null) {
            if (event instanceof TurnEvent.Notice n) {
                notices.add(n.text());
            }
        }
        assertEquals(2, notices.size());
        assertTrue(notices.get(0).startsWith("⟣ 子任务开始: 帮我调研"));
        assertTrue(notices.get(1).startsWith("⟣ 子任务结束: 耗时 0s / 1 次迭代"));
    }
}
