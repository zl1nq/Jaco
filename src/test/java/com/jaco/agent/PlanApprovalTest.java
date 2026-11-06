package com.jaco.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaco.session.*;
import com.jaco.tool.*;
import com.jaco.tool.builtin.SubmitPlanTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class PlanApprovalTest {
    @TempDir Path dir;
    private final ObjectMapper json = new ObjectMapper();
    private final class Pending implements AutoCloseable {
        final SessionStore store = new SessionStore(dir);
        final Session owner = store.createNew();
        final AtomicBoolean active = new AtomicBoolean(true);
        final WorkflowService service = new WorkflowService(owner, store, active::get);
        final TurnHandle handle = new TurnHandle();
        final CompletableFuture<ToolResult> result = new CompletableFuture<>();
        final Thread worker;
        Pending() throws Exception {
            service.enterPlan();
            worker = Thread.ofVirtual().unstarted(() -> {
                TurnEventSink.install(handle);
                try { result.complete(new SubmitPlanTool().execute(json.createObjectNode().put("plan", "# 完整计划\n研究并实现"), context())); }
                catch (Exception e) { result.completeExceptionally(e); }
                finally { TurnEventSink.clear(); }
            });
            handle.setLoopThread(worker);
            worker.start();
        }
        ToolContext context() { return new ToolContext(dir, null, "auto", null, handle::isCancelled, null, 8000, service); }
        TurnEvent.PlanApprovalRequest request() {
            return assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                TurnEvent event;
                while ((event = handle.poll(100)) == null) assertFalse(result.isDone());
                return assertInstanceOf(TurnEvent.PlanApprovalRequest.class, event);
            });
        }
        public void close() throws Exception { handle.cancel(); worker.join(1000); assertFalse(worker.isAlive()); }
    }
    @Test void onlyExplicitDecisionChangesModeAndPersistsDraft() throws Exception {
        for (boolean approve : new boolean[]{true, false}) try (Pending p = new Pending()) {
            var request = p.request();
            assertEquals(p.owner.id(), request.sessionId());
            assertEquals(p.handle.id(), request.turnId());
            assertEquals(request.markdown(), p.store.load(p.owner.id()).orElseThrow().workflow().state().draft());
            assertThrows(ToolException.class, () -> request.resolver().accept(null));
            assertThrows(TimeoutException.class, () -> p.result.get(100, TimeUnit.MILLISECONDS));
            request.resolver().accept(approve);
            assertEquals(approve ? ToolExecution.Status.SUCCEEDED : ToolExecution.Status.DENIED, p.result.get(2, TimeUnit.SECONDS).status());
            assertEquals(!approve, p.service.planning());
            assertEquals(!approve, p.service.shouldStopTurn());
            request.resolver().accept(!approve);
            assertFalse(request.active().getAsBoolean());
            assertEquals(approve ? "approved" : "rejected", p.store.load(p.owner.id()).orElseThrow().workflow().state().decision());
        }
    }
    @Test void staleSessionRevisionAndCancellationNeverApprove() throws Exception {
        for (int cause = 0; cause < 3; cause++) try (Pending p = new Pending()) {
            var request = p.request();
            if (cause == 0) p.active.set(false);
            if (cause == 1) p.service.saveDraft("更新后的计划");
            if (cause == 2) p.handle.cancel();
            request.resolver().accept(true);
            assertThrows(ExecutionException.class, () -> p.result.get(2, TimeUnit.SECONDS));
            assertTrue(p.service.planning());
            assertFalse(request.active().getAsBoolean());
            assertNotEquals("approved", p.owner.workflow().state().decision());
        }
    }
    @Test void missingChannelPreservesDraftAndCancelIsNotApproval() throws Exception {
        var store = new SessionStore(dir);
        var owner = store.createNew();
        var service = new WorkflowService(owner, store, () -> true);
        service.enterPlan();
        var ctx = new ToolContext(dir, null, "auto", null, () -> false, null, 8000, service);
        assertEquals(ToolExecution.Status.FAILED, new SubmitPlanTool().execute(json.createObjectNode().put("plan", "方案"), ctx).status());
        assertTrue(service.planning());
        assertTrue(service.shouldStopTurn());
        assertThrows(IllegalArgumentException.class, () -> service.saveDraft(" "));
        assertThrows(IllegalArgumentException.class, () -> service.saveDraft("x".repeat(20001)));
        service.cancelPlan();
        assertFalse(service.planning());
        var saved = store.load(owner.id()).orElseThrow().workflow().state();
        assertEquals("方案", saved.draft());
        assertEquals("cancelled", saved.decision());
    }
}
