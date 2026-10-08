package com.jaco.agent;

import com.jaco.config.ProviderConfig;
import com.jaco.hook.HookChain;
import com.jaco.hook.HookVerdict;
import com.jaco.llm.ToolCall;
import com.jaco.session.Session;
import com.jaco.session.SessionStore;
import com.jaco.tool.PermissionHook;
import com.jaco.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerSessionPermissionsTest {

    @TempDir
    Path tmp;

    private static final String OLDER = "s20260101-000000";
    private static final String NEWER = "s20260102-000000";
    private static final List<ToolCall> CALLS = List.of(
            call("write_file", "{\"path\":\"example.txt\",\"content\":\"hello\"}"),
            call("run_command", "{\"command\":\"echo hello\"}"),
            call("task", "{\"prompt\":\"调查项目结构\"}"));

    private static ToolCall call(String name, String arguments) {
        return new ToolCall(name, "function", new ToolCall.FunctionCall(name, arguments));
    }

    private AgentRunner runner(PermissionHook permission) throws Exception {
        SessionStore store = new SessionStore(tmp);
        store.save(Session.create(OLDER, 1000));
        store.save(Session.create(NEWER, 2000));
        AgentRunner agent = new AgentRunner(null,
                new ProviderConfig("http://localhost", "k", "m", null),
                null, store, new HookChain(List.of(permission)), new ToolRegistry(),
                null, tmp, "auto", 25, 100_000, 8000);
        agent.start();
        return agent;
    }

    /** 通过真实确认事件作答，不执行工具，也不发送模型请求。 */
    private HookVerdict answer(PermissionHook permission, ToolCall call, String response) throws Exception {
        TurnHandle handle = new TurnHandle();
        CompletableFuture<HookVerdict> verdict = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            TurnEventSink.install(handle);
            try {
                verdict.complete(permission.onBeforeToolCall(call));
            } catch (Throwable e) {
                verdict.completeExceptionally(e);
            } finally {
                TurnEventSink.clear();
            }
        });
        handle.setLoopThread(worker);
        worker.start();
        try {
            TurnEvent.ApprovalRequest request = assertInstanceOf(
                    TurnEvent.ApprovalRequest.class, handle.poll(2000));
            assertEquals(call.function().name(), request.tool());
            request.resolver().accept(response);
            return verdict.get(2, TimeUnit.SECONDS);
        } finally {
            handle.cancel();
            worker.join(2000);
        }
    }

    private void allowForSession(PermissionHook permission) throws Exception {
        for (ToolCall call : CALLS) {
            assertTrue(answer(permission, call, "a").proceed());
            // 没有确认渠道仍放行，说明同类工具已获得会话授权。
            assertTrue(permission.onBeforeToolCall(call).proceed());
        }
    }

    private void assertApprovalRequired(PermissionHook permission) throws Exception {
        for (ToolCall call : CALLS) {
            assertFalse(answer(permission, call, "n").proceed());
        }
        assertTrue(permission.onBeforeToolCall(call("read_file", "{\"path\":\"example.txt\"}")).proceed());
    }

    @Test
    void newSessionRequiresApprovalAgain() throws Exception {
        PermissionHook permission = new PermissionHook();
        AgentRunner agent = runner(permission);
        allowForSession(permission);

        agent.newSession();

        assertNotEquals(NEWER, agent.session().id());
        assertApprovalRequired(permission);
    }

    @Test
    void switchingAndReturningRequireFreshApproval() throws Exception {
        PermissionHook permission = new PermissionHook();
        AgentRunner agent = runner(permission);
        allowForSession(permission);

        assertNull(agent.switchSession(OLDER));
        assertApprovalRequired(permission);
        allowForSession(permission);

        assertNull(agent.switchSession("s20260102"));
        assertEquals(NEWER, agent.session().id());
        assertApprovalRequired(permission);
    }

    @Test
    void unsuccessfulSwitchesPreserveApproval() throws Exception {
        PermissionHook permission = new PermissionHook();
        AgentRunner agent = runner(permission);
        allowForSession(permission);

        for (String target : List.of("s9999", "s2026", NEWER, "s20260102")) {
            assertNotNull(agent.switchSession(target));
            assertEquals(NEWER, agent.session().id());
            for (ToolCall call : CALLS) {
                assertTrue(permission.onBeforeToolCall(call).proceed());
            }
        }
    }
}
