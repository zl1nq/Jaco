package com.jaco.llm;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 本地 HTTP 服务验证真实等待和取消行为，不调用外部模型。 */
class OpenAiCompatClientDeadlineTest {
    private HttpServer server;
    private ExecutorService handlers;
    private HttpHandler handler;
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch received = new CountDownLatch(1);
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(handlers);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            received.countDown();
            try { handler.handle(exchange); } finally { exchange.close(); }
        });
        server.start();
    }

    @AfterEach void stop() {
        release.countDown();
        server.stop(0);
        handlers.shutdownNow();
    }

    private OpenAiCompatClient client() {
        return new OpenAiCompatClient("http://127.0.0.1:" + server.getAddress().getPort() + "/", "test");
    }

    private static ChatRequest request() {
        return new ChatRequest("model", List.of(Message.user("test")), 0.0, true,
                new ChatRequest.StreamOptions(true), null);
    }

    private void waitForRelease() {
        try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Test void responseHeaderWaitHasDeadline() {
        handler = exchange -> waitForRelease();
        OpenAiCompatClient client = client();
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            assertThrows(HttpTimeoutException.class,
                    () -> client.chatStream(request(), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(800)));
            assertTrue(received.await(100, TimeUnit.MILLISECONDS));
            assertEquals(1, requests.get());
        });
    }

    @Test void headerWaitAndStreamReadShareDeadlineInsteadOfRestartingTimer() {
        handler = exchange -> {
            try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            waitForRelease();
        };
        OpenAiCompatClient client = client();
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1200);
            try (ChatStream stream = client.chatStream(request(), deadline)) {
                assertInstanceOf(StreamChunk.Delta.class, stream.poll(500));
                long remainingMillis = Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                StreamChunk.Error error = assertInstanceOf(StreamChunk.Error.class, stream.poll(remainingMillis + 200));
                assertInstanceOf(HttpTimeoutException.class, error.cause());
                assertTrue(stream.isCancelled());
            }
        });
    }

    @Test void errorBodyReadCannotWaitBeyondDeadline() {
        handler = exchange -> {
            exchange.sendResponseHeaders(400, 0);
            exchange.getResponseBody().flush();
            waitForRelease();
        };
        OpenAiCompatClient client = client();
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            assertThrows(HttpTimeoutException.class,
                    () -> client.chatStream(request(), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(800)));
            assertEquals(1, requests.get());
        });
    }

    @Test void retryBackoffConsumesSameBudgetAndDoesNotStartAnotherAttemptAfterExpiry() {
        handler = exchange -> exchange.sendResponseHeaders(503, -1);
        OpenAiCompatClient client = client();
        assertTimeoutPreemptively(Duration.ofMillis(3000), () -> {
            assertThrows(HttpTimeoutException.class,
                    () -> client.chatStream(request(), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2200)));
            assertEquals(2, requests.get());
        });
    }

    @Test void interruptDuringHeaderWaitEndsRequestPromptly() throws Exception {
        handler = exchange -> waitForRelease();
        OpenAiCompatClient client = client();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try { client.chatStream(request(), System.nanoTime() + TimeUnit.SECONDS.toNanos(10)); }
            catch (Throwable e) { failure.set(e); }
        });
        try {
            assertTrue(received.await(2, TimeUnit.SECONDS));
            worker.interrupt();
            worker.join(1000);
            assertFalse(worker.isAlive());
            assertInstanceOf(InterruptedException.class, failure.get());
        } finally { worker.interrupt(); }
    }

    @Test void successfulStreamKeepsDoneAndCancelsDeadlineTask() throws Exception {
        handler = exchange -> {
            byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        };
        OpenAiCompatClient client = client();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        try (ChatStream stream = client.chatStream(request(), deadline)) {
            assertEquals("ok", assertInstanceOf(StreamChunk.Delta.class, stream.poll(500)).text());
            assertEquals("stop", assertInstanceOf(StreamChunk.Done.class, stream.poll(500)).finishReason());
            TimeUnit.NANOSECONDS.sleep(Math.max(0, deadline - System.nanoTime()) + TimeUnit.MILLISECONDS.toNanos(100));
            assertFalse(stream.isCancelled());
            assertNull(stream.poll(0));
        }
    }

    @Test void cancelRunsResourceCleanupEvenAfterHttpFutureHasCompleted() {
        AtomicInteger closed = new AtomicInteger();
        ChatStream stream = new ChatStream(java.util.concurrent.CompletableFuture.completedFuture(null), closed::incrementAndGet);
        stream.cancel();
        stream.cancel();
        assertEquals(1, closed.get());
        assertTrue(stream.isCancelled());
    }

    @Test void expiredDeadlineDoesNotStartHttpRequest() {
        OpenAiCompatClient client = client();
        assertThrows(HttpTimeoutException.class, () -> client.chatStream(request(), System.nanoTime() - 1));
        assertEquals(0, requests.get());
    }
}
