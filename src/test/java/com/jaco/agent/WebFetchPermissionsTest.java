package com.jaco.agent;

import com.jaco.hook.HookVerdict;
import com.jaco.llm.ToolCall;
import com.jaco.tool.PermissionHook;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WebFetchPermissionsTest {
    private ToolCall call(String args) {
        return new ToolCall("id", "function", new ToolCall.FunctionCall("web_fetch", args));
    }

    private HookVerdict answer(PermissionHook permission, ToolCall call, String answer) throws Exception {
        TurnHandle handle = new TurnHandle();
        CompletableFuture<HookVerdict> result = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            TurnEventSink.install(handle);
            try { result.complete(permission.onBeforeToolCall(call)); }
            catch (Exception e) { result.completeExceptionally(e); }
            finally { TurnEventSink.clear(); }
        });
        handle.setLoopThread(thread);
        thread.start();
        try {
            var request = assertInstanceOf(TurnEvent.ApprovalRequest.class, handle.poll(2000));
            assertTrue(request.detail().contains("网络读取"));
            request.resolver().accept(answer);
            return result.get(2, TimeUnit.SECONDS);
        } finally {
            handle.cancel();
            thread.join(2000);
        }
    }

    @Test
    void sessionApprovalIsScopedToSchemeHostAndPort() throws Exception {
        PermissionHook permission = new PermissionHook();
        assertTrue(answer(permission, call("{\"url\":\"https://example.com/a\"}"), "a").proceed());
        assertTrue(permission.onBeforeToolCall(call("{\"url\":\"https://EXAMPLE.COM:443/b\"}")).proceed());
        assertFalse(permission.onBeforeToolCall(call("{\"url\":\"https://other.example/\"}")).proceed());
        assertFalse(permission.onBeforeToolCall(call("{\"url\":\"http://example.com/\"}")).proceed());
        assertFalse(permission.onBeforeToolCall(call("{\"url\":\"https://example.com:8443/\"}")).proceed());
        permission.onSessionChanged();
        assertFalse(permission.onBeforeToolCall(call("{\"url\":\"https://example.com/a\"}")).proceed());
    }

    @Test
    void oneTimeApprovalDoesNotPersistAndCachedPagesNeedNoNetworkApproval() throws Exception {
        PermissionHook permission = new PermissionHook();
        ToolCall request = call("{\"url\":\"https://example.com/\"}");
        assertFalse(permission.onBeforeToolCall(request).proceed());
        assertTrue(answer(permission, request, "y").proceed());
        assertFalse(permission.onBeforeToolCall(request).proceed());
        assertTrue(permission.onBeforeToolCall(call("{\"fetch_id\":\"fetch-123\",\"offset\":20}")).proceed());
        assertFalse(permission.onBeforeToolCall(call("{\"url\":\"file:///tmp/secret\"}")).proceed());
    }
}
