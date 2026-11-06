package com.jaco.agent;

import com.jaco.config.ProviderConfig;
import com.jaco.hook.HookChain;
import com.jaco.llm.*;
import com.jaco.session.SessionStore;
import com.jaco.tool.*;
import com.jaco.tool.builtin.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerPlanTest {
    @TempDir Path dir;
    @Test void approvalContinuesSameTurnWithFreshToolsAndIndependentPermission() throws Exception { run(true); }
    @Test void rejectionStopsTurnAndDeniesRemainingBatchCalls() throws Exception { run(false); }
    private void run(boolean approve) throws Exception {
        var requests = new ArrayList<ChatRequest>();
        var client = new OpenAiCompatClient("http://localhost", "k") {
            @Override public ChatStream chatStream(ChatRequest request) {
                requests.add(request);
                if (requests.size() == 1) return TestStreams.of(
                        new StreamChunk.ToolCallDelta(0, "p", "submit_plan", "{\"plan\":\"# 完整计划\\n创建测试文件\"}"),
                        new StreamChunk.ToolCallDelta(1, "w", "write_file", "{\"path\":\"new.txt\",\"content\":\"hello\"}"),
                        new StreamChunk.Done("tool_calls", null));
                return TestStreams.of(new StreamChunk.Delta("完成"), new StreamChunk.Done("stop", null));
            }
        };
        var registry = new ToolRegistry(new SubmitPlanTool(), new EnterPlanModeTool(), new TodoReadTool(), new WriteFileTool());
        var permission = new PermissionHook(registry);
        var store = new SessionStore(dir.resolve("sessions"));
        try (var agent = new AgentRunner(client, new ProviderConfig("http://localhost", "k", "m", null), null,
                store, new HookChain(List.of(permission)), registry, new ToolSandbox(dir, List.of()), dir, "auto", 10, 100000, 8000)) {
            agent.start(); agent.plan();
            var events = new ArrayList<TurnEvent>();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                try (var handle = agent.runTurn("制定方案")) {
                    while (true) {
                        TurnEvent event = handle.poll(100);
                        if (event == null) continue;
                        events.add(event);
                        if (event instanceof TurnEvent.PlanApprovalRequest p) p.resolver().accept(approve);
                        if (event instanceof TurnEvent.ApprovalRequest p) p.resolver().accept("n");
                        if (event instanceof TurnEvent.Done d) { assertNull(d.error()); assertEquals(approve ? "stop" : "plan_waiting", d.finishReason()); break; }
                    }
                }
            });
            assertEquals(!approve, agent.planning());
            assertEquals(approve ? 2 : 1, requests.size());
            assertFalse(requests.getFirst().tools().stream().anyMatch(t -> t.function().name().equals("write_file")));
            if (approve) {
                assertTrue(requests.getLast().tools().stream().anyMatch(t -> t.function().name().equals("write_file")));
                assertFalse(requests.getLast().tools().stream().anyMatch(t -> t.function().name().equals("submit_plan")));
                assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.ApprovalRequest));
            } else assertFalse(events.stream().anyMatch(e -> e instanceof TurnEvent.ApprovalRequest));
            assertFalse(Files.exists(dir.resolve("new.txt")));
            assertEquals("制定方案", agent.session().title());
            assertEquals(!approve, store.load(agent.session().id()).orElseThrow().workflow().planning());
            assertEquals(2, agent.session().messages().stream().filter(m -> m.role() == Role.TOOL).count());
        }
    }
}
