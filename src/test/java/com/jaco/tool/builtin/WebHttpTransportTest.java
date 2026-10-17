package com.jaco.tool.builtin;

import com.jaco.tool.ToolException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class WebHttpTransportTest {
    private HttpServer server;
    private ExecutorService executor;
    private final WebHttpTransport transport = new WebHttpTransport();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    private URI uri(String path) {
        return URI.create("http://never-resolve.invalid:" + server.getAddress().getPort() + path);
    }

    private WebHttpTransport.Response get(String path) throws Exception {
        // 仅直接测试底层固定 DNS 连接；生产 WebFetchTool 必须先经公共地址策略，不能使用此测试地址。
        return transport.get(uri(path), new InetAddress[]{InetAddress.getByName("127.0.0.1")}, new WebHttpTransport.Abort());
    }

    private void send(HttpExchange exchange, int status, String type, byte[] bytes, boolean chunked) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(status, chunked ? 0 : bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException ignored) {
            // 读取上限和取消测试会主动关闭客户端连接。
        }
    }

    @Test
    void pinsValidatedDnsWhileKeepingOriginalHostAndNoCredentials() throws Exception {
        server.createContext("/page", exchange -> {
            assertEquals("never-resolve.invalid:" + server.getAddress().getPort(), exchange.getRequestHeaders().getFirst("Host"));
            assertNull(exchange.getRequestHeaders().getFirst("Cookie"));
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            send(exchange, 200, "text/plain", "pinned".getBytes(StandardCharsets.UTF_8), false);
        });
        assertEquals("pinned", new String(get("/page").body(), StandardCharsets.UTF_8));
    }

    @Test
    void doesNotAutomaticallyFollowRedirectsOrUseCookies() throws Exception {
        AtomicInteger target = new AtomicInteger();
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.getResponseHeaders().add("Set-Cookie", "secret=value");
            send(exchange, 302, "text/plain", "redirect".getBytes(StandardCharsets.UTF_8), false);
        });
        server.createContext("/target", exchange -> {
            target.incrementAndGet();
            send(exchange, 200, "text/plain", new byte[]{65}, false);
        });
        assertEquals(302, get("/redirect").status());
        assertEquals(0, target.get());
    }

    @Test
    void decompressesGzipAndHonorsDeclaredTextCharset() throws Exception {
        byte[] original = "中文正文".getBytes(Charset.forName("GBK"));
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) { gzip.write(original); }
        server.createContext("/gzip", exchange -> {
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            send(exchange, 200, "text/plain; charset=GBK", compressed.toByteArray(), false);
        });
        WebHttpTransport.Response response = get("/gzip");
        assertArrayEquals(original, response.body());
        assertEquals("中文正文", new WebContent().extract(response, uri("/gzip")).text());
    }

    @Test
    void rejectsOversizedChunkedAndCompressedResponses() throws Exception {
        byte[] big = "x".repeat(WebHttpTransport.MAX_BYTES + 1).getBytes(StandardCharsets.UTF_8);
        server.createContext("/chunked", exchange -> send(exchange, 200, "text/plain", big, true));
        assertThrows(ToolException.class, () -> get("/chunked"));
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) { gzip.write(big); }
        server.createContext("/bomb", exchange -> {
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            send(exchange, 200, "text/plain", compressed.toByteArray(), false);
        });
        ToolException error = assertThrows(ToolException.class, () -> get("/bomb"));
        assertTrue(error.getMessage().contains("解压"));
    }

    @Test
    void rejectsBinaryTypesAndHttpErrors() throws Exception {
        server.createContext("/pdf", exchange -> send(exchange, 200, "application/pdf", new byte[]{65}, false));
        assertThrows(ToolException.class, () -> get("/pdf"));
        server.createContext("/missing", exchange -> send(exchange, 404, "text/plain", new byte[]{65}, false));
        ToolException error = assertThrows(ToolException.class, () -> get("/missing"));
        assertTrue(error.getMessage().contains("404"));
    }

    @Test
    void cancellationClosesARealBlockedResponseRead() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, 0);
                started.countDown();
                try { TimeUnit.SECONDS.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        WebHttpTransport.Abort abort = new WebHttpTransport.Abort();
        CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> {
            try { transport.get(uri("/slow"), new InetAddress[]{InetAddress.getLoopbackAddress()}, abort); return "finished"; }
            catch (Exception e) { return "cancelled"; }
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        abort.cancel();
        assertEquals("cancelled", result.get(2, TimeUnit.SECONDS));
    }
}
