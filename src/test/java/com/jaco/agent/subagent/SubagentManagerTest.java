package com.jaco.agent.subagent;

import com.jaco.llm.TestStreams;
import com.jaco.llm.Usage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SubagentManagerTest {
    private static SubagentSpec spec() {
        return new SubagentSpec("session", "turn", "调查任务", Duration.ofSeconds(5), 3);
    }

    private static void reached(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(3, TimeUnit.SECONDS), "执行未到达预期边界");
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean done = false;
        while (!done) {
            try { latch.await(); done = true; }
            catch (InterruptedException ignored) { }
        }
    }

    @Test void resultIsPublishedOnlyAfterWorkerExitsAndStatsAreRetained() throws Exception {
        try (var manager = new SubagentManager()) {
            var thread = new AtomicReference<Thread>();
            var handle = manager.start(spec(), () -> false, control -> {
                thread.set(Thread.currentThread());
                control.iteration(2);
                control.addUsage(new Usage(10, 4, 14));
                control.progress("src/A.java:12");
                return SubagentManager.Completion.succeeded("调查报告");
            });
            var result = handle.await(Duration.ofSeconds(3));
            assertEquals(ExitReason.SUCCEEDED, result.exitReason());
            assertFalse(thread.get().isAlive());
            assertEquals(SubagentState.EXITED, handle.snapshot().state());
            assertEquals("session", handle.snapshot().parentSessionId());
            assertEquals("turn", handle.snapshot().parentTurnId());
            assertNotNull(handle.snapshot().startedAt());
            assertNotNull(handle.snapshot().exitedAt());
            assertEquals(14, result.stats().totalTokens());
            assertTrue(result.stats().usageAvailable());
            assertEquals(2, result.stats().iterations());
            handle.cancel();
            assertSame(result, handle.await());
        }
    }

    @Test void cancelledParentBeforeStartNeverExecutesWork() throws Exception {
        try (var manager = new SubagentManager()) {
            AtomicBoolean executed = new AtomicBoolean();
            var handle = manager.start(spec(), () -> true, control -> {
                executed.set(true);
                return SubagentManager.Completion.succeeded("不应执行");
            });
            var result = handle.await(Duration.ofSeconds(3));
            assertFalse(executed.get());
            assertEquals(ExitReason.CANCELLED, result.exitReason());
            assertNull(result.error());
            assertNull(handle.snapshot().startedAt());
        }
    }

    @Test void cancelBeforeStreamRegistrationClosesLateResourceAndWinsOverSuccess() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        try (var manager = new SubagentManager()) {
            var handle = manager.start(spec(), () -> false, control -> {
                control.progress("已发现 src/A.java:42");
                entered.countDown();
                awaitIgnoringInterrupts(release);
                control.registerStream(TestStreams.withCleanup(() -> closed.set(true)));
                return SubagentManager.Completion.succeeded("迟到的成功");
            });
            try {
                reached(entered);
                handle.cancel();
                handle.cancel();
                release.countDown();
                var result = handle.await(Duration.ofSeconds(3));
                assertTrue(closed.get());
                assertEquals(ExitReason.CANCELLED, result.exitReason());
                assertTrue(result.partialFindings().contains("src/A.java:42"));
                assertEquals(StopReason.USER_CANCELLED, handle.snapshot().stopReason());
            } finally { release.countDown(); }
        }
    }

    @Test void clearingOldStreamDoesNotLoseNewResource() throws Exception {
        var first = TestStreams.of();
        AtomicBoolean closed = new AtomicBoolean();
        CountDownLatch ready = new CountDownLatch(1);
        try (var manager = new SubagentManager()) {
            var handle = manager.start(spec(), () -> false, control -> {
                control.registerStream(first);
                control.registerStream(TestStreams.withCleanup(() -> closed.set(true)));
                control.clearStream(first);
                ready.countDown();
                new CountDownLatch(1).await();
                return SubagentManager.Completion.succeeded("不会完成");
            });
            reached(ready);
            handle.cancel();
            assertEquals(ExitReason.CANCELLED, handle.await(Duration.ofSeconds(3)).exitReason());
            assertTrue(closed.get());
        }
    }

    @Test void acceptedCompletionWinsOverLaterCancelButStillWaitsForCleanup() throws Exception {
        CountDownLatch cleaning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var manager = new SubagentManager()) {
            var handle = manager.start(spec(), () -> false, control -> {
                control.registerStream(TestStreams.withCleanup(() -> {
                    cleaning.countDown();
                    awaitIgnoringInterrupts(release);
                }));
                return SubagentManager.Completion.succeeded("已完成");
            });
            try {
                reached(cleaning);
                handle.cancel();
                assertNull(handle.result());
                assertNotEquals(SubagentState.EXITED, handle.snapshot().state());
                release.countDown();
                assertEquals(ExitReason.SUCCEEDED, handle.await(Duration.ofSeconds(3)).exitReason());
            } finally { release.countDown(); }
        }
    }

    @Test void stuckCancellationKeepsSlotAndCloseIsBounded() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var manager = new SubagentManager(Duration.ofMillis(30), 2);
        var handle = manager.start(spec(), () -> false, control -> {
            entered.countDown();
            awaitIgnoringInterrupts(release);
            return SubagentManager.Completion.succeeded("迟到的结果");
        });
        try {
            reached(entered);
            handle.cancel();
            assertThrows(TimeoutException.class, () -> handle.await(Duration.ofMillis(100)));
            assertEquals(SubagentState.CANCELLING, handle.snapshot().state());
            assertTrue(handle.snapshot().cleanupOverdue());
            assertThrows(IllegalStateException.class, () -> manager.start(spec(), () -> false,
                    c -> SubagentManager.Completion.succeeded("不能占用第二个槽位")));
            assertTimeoutPreemptively(Duration.ofSeconds(1), manager::close);
            assertNull(handle.result());
            release.countDown();
            assertEquals(ExitReason.CANCELLED, handle.await(Duration.ofSeconds(3)).exitReason());
            assertThrows(IllegalStateException.class, () -> manager.start(spec(), () -> false,
                    c -> SubagentManager.Completion.succeeded("关闭后拒绝接收")));
            manager.close();
        } finally { release.countDown(); manager.close(); }
    }

    @Test void deadlineInterruptsBlockedWorkAndKeepsPartialFindings() throws Exception {
        try (var manager = new SubagentManager()) {
            var spec = new SubagentSpec("session", "turn", "task", Duration.ofMillis(150), 3);
            var handle = manager.start(spec, () -> false, control -> {
                control.progress("已经找到入口");
                new CountDownLatch(1).await();
                return SubagentManager.Completion.succeeded("不应成功");
            });
            var result = handle.await(Duration.ofSeconds(3));
            assertEquals(ExitReason.TIMED_OUT, result.exitReason());
            assertEquals(StopReason.TIMEOUT, handle.snapshot().stopReason());
            assertTrue(result.toToolResult().contains("已经找到入口"));
        }
    }

    @Test void cleanupFailurePublishesFailureInsteadOfLosingResult() throws Exception {
        try (var manager = new SubagentManager()) {
            var handle = manager.start(spec(), () -> false, control -> {
                control.registerStream(TestStreams.withCleanup(() -> { throw new IllegalStateException("close failed"); }));
                return SubagentManager.Completion.succeeded("报告");
            });
            var result = handle.await(Duration.ofSeconds(3));
            assertEquals(ExitReason.FAILED, result.exitReason());
            assertEquals("CleanupFailure", result.error().type());
            assertEquals(SubagentState.EXITED, handle.snapshot().state());
        }
    }

    @Test void blockingResourceCloseDoesNotBlockCancelOrManagerShutdown() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var manager = new SubagentManager(Duration.ofMillis(30), 2);
        var handle = manager.start(spec(), () -> false, control -> {
            control.registerStream(TestStreams.withCleanup(() -> {
                closing.countDown();
                awaitIgnoringInterrupts(release);
            }));
            entered.countDown();
            new CountDownLatch(1).await();
            return SubagentManager.Completion.succeeded("不应成功");
        });
        try {
            reached(entered);
            assertTimeoutPreemptively(Duration.ofSeconds(1), handle::cancel);
            reached(closing);
            assertTimeoutPreemptively(Duration.ofSeconds(1), manager::close);
            assertNull(handle.result());
            assertEquals(SubagentState.CANCELLING, handle.snapshot().state());
            release.countDown();
            assertEquals(ExitReason.CANCELLED, handle.await(Duration.ofSeconds(3)).exitReason());
        } finally { release.countDown(); manager.close(); }
    }

    @Test void completedRecordsAreBoundedAndDoNotDisappearWhenRead() throws Exception {
        try (var manager = new SubagentManager(Duration.ofMillis(30), 2)) {
            String first = null;
            for (int i = 0; i < 3; i++) {
                var handle = manager.start(spec(), () -> false, c -> SubagentManager.Completion.succeeded("报告"));
                handle.await(Duration.ofSeconds(3));
                if (first == null) first = handle.id();
                assertSame(handle, manager.find(handle.id()));
                assertNotNull(manager.find(handle.id()).result());
            }
            assertEquals(2, manager.snapshots().size());
            assertNull(manager.find(first));
        }
    }

    @Test void scopeCancellationIsBoundToCorrectTurnAndSession() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        try (var manager = new SubagentManager()) {
            var handle = manager.start(spec(), () -> false, c -> {
                entered.countDown();
                new CountDownLatch(1).await();
                return SubagentManager.Completion.succeeded("不应成功");
            });
            reached(entered);
            manager.cancelTurn("other-turn", StopReason.PARENT_CANCELLED);
            manager.cancelSession("other-session");
            assertEquals(SubagentState.RUNNING, handle.snapshot().state());
            manager.cancelSession("session");
            assertEquals(ExitReason.CANCELLED, handle.await(Duration.ofSeconds(3)).exitReason());
            assertEquals(StopReason.SESSION_CHANGED, handle.snapshot().stopReason());
        }
    }
}
