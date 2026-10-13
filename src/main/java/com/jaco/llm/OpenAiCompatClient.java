package com.jaco.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 手写的 OpenAI 兼容流式客户端。
 * 重试策略：连接失败/超时/429/5xx 指数退避重试（最多 3 次）；4xx 不重试；
 * 流中途断开不重试（内容已部分产出），以 Error 事件终结。
 */
public class OpenAiCompatClient {

    private static final int MAX_ATTEMPTS = 3;
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(1, r -> {
        Thread thread = new Thread(r, "jaco-request-deadline");
        thread.setDaemon(true);
        return thread;
    });
    static {
        DEADLINES.setRemoveOnCancelPolicy(true);
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String endpoint;
    private final String apiKey;

    public OpenAiCompatClient(String chatCompletionsUrl, String apiKey) {
        this.endpoint = chatCompletionsUrl;
        this.apiKey = apiKey;
    }

    public ChatStream chatStream(ChatRequest request) throws IOException, InterruptedException {
        return openStream(request, null);
    }

    /** 使用调用方提供的 nanoTime 截止时间，响应头、重试、错误正文与 SSE 共用预算。 */
    public ChatStream chatStream(ChatRequest request, long deadlineNanos) throws IOException, InterruptedException {
        return openStream(request, deadlineNanos);
    }

    private ChatStream openStream(ChatRequest request, Long deadline) throws IOException, InterruptedException {
        byte[] payload = mapper.writeValueAsBytes(request);
        RequestResources resources = new RequestResources(deadline);
        boolean handedOff = false;
        try {
            for (int attempt = 1; ; attempt++) {
                var builder = HttpRequest.newBuilder(URI.create(endpoint))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
                if (deadline != null) builder.timeout(Duration.ofNanos(remaining(deadline)));
                HttpRequest httpRequest = builder.build();
                CompletableFuture<HttpResponse<InputStream>> future =
                        http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                resources.future = future;
                future.thenAccept(response -> {
                    if (resources.closed.get()) closeQuietly(response.body());
                });
                if (resources.closed.get()) future.cancel(true);
                HttpResponse<InputStream> response;
                try {
                    response = deadline == null ? future.get() : future.get(remaining(deadline), TimeUnit.NANOSECONDS);
                } catch (TimeoutException | CancellationException e) {
                    throw new HttpTimeoutException("模型请求总预算已耗尽");
                } catch (ExecutionException e) {
                    if (deadline != null) remaining(deadline);
                    if (attempt < MAX_ATTEMPTS && isRetryable(e.getCause())) {
                        sleepBackoff(attempt, deadline);
                        continue;
                    }
                    throw new IOException("请求失败: " + rootMessage(e), e);
                }
                resources.body = response.body();
                if (resources.closed.get()) closeQuietly(response.body());
                if (deadline != null) remaining(deadline);

                int status = response.statusCode();
                if ((status == 429 || status >= 500) && attempt < MAX_ATTEMPTS) {
                    response.body().close();
                    sleepBackoff(attempt, deadline);
                    continue;
                }
                if (status >= 400) {
                    String body;
                    try {
                        body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        if (deadline != null) remaining(deadline);
                        throw e;
                    }
                    if (deadline != null) remaining(deadline);
                    throw new JacoApiException(status, summarizeError(status, body));
                }
                ChatStream stream = startStream(response.body(), future, resources);
                handedOff = true;
                return stream;
            }
        } finally {
            if (!handedOff) resources.close();
        }
    }

    private ChatStream startStream(InputStream body, CompletableFuture<HttpResponse<InputStream>> future,
                                   RequestResources resources) {
        ChatStream stream = new ChatStream(future, resources::close);
        resources.stream = stream;
        Thread reader = new Thread(() -> {
            try {
                consume(body, stream);
            } finally {
                resources.close();
            }
        }, "jaco-sse");
        resources.reader = reader;
        reader.setDaemon(true);
        if (resources.closed.get()) stream.timeout();
        reader.start();
        return stream;
    }

    private void consume(InputStream body, ChatStream stream) {
        String finishReason = null;
        Usage usage = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).strip();
                if (data.isEmpty() || data.equals("[DONE]")) {
                    if (data.equals("[DONE]")) {
                        break;
                    }
                    continue;
                }
                JsonNode node = mapper.readTree(data);
                JsonNode choices = node.path("choices");
                if (choices.isArray() && !choices.isEmpty()) {
                    JsonNode first = choices.get(0);
                    JsonNode content = first.path("delta").get("content");
                    // 注意不能用 JsonNode.isEmpty()：对标量节点它等价于 size()==0，恒为 true
                    if (content != null && content.isTextual() && !content.textValue().isEmpty()) {
                        stream.queue().put(new StreamChunk.Delta(content.textValue()));
                    }
                    JsonNode finish = first.get("finish_reason");
                    if (finish != null && finish.isTextual()) {
                        finishReason = finish.textValue();
                    }
                    JsonNode toolCalls = first.path("delta").get("tool_calls");
                    if (toolCalls != null && toolCalls.isArray()) {
                        for (JsonNode tc : toolCalls) {
                            JsonNode fn = tc.path("function");
                            JsonNode idNode = tc.get("id");
                            JsonNode nameNode = fn.get("name");
                            JsonNode argsNode = fn.get("arguments");
                            stream.queue().put(new StreamChunk.ToolCallDelta(
                                    tc.path("index").asInt(0),
                                    idNode != null && idNode.isTextual() ? idNode.textValue() : null,
                                    nameNode != null && nameNode.isTextual() ? nameNode.textValue() : null,
                                    argsNode != null && argsNode.isTextual() ? argsNode.textValue() : null));
                        }
                    }
                }
                JsonNode usageNode = node.get("usage");
                if (usageNode != null && usageNode.isObject()) {
                    usage = new Usage(
                            usageNode.path("prompt_tokens").asInt(0),
                            usageNode.path("completion_tokens").asInt(0),
                            usageNode.path("total_tokens").asInt(0));
                }
            }
            stream.queue().put(new StreamChunk.Done(finishReason, usage));
        } catch (IOException e) {
            // 用户主动取消导致的连接关闭不算错误
            if (!stream.isCancelled()) {
                offerQuietly(stream, new StreamChunk.Error(e, "连接中断: " + e.getMessage()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void offerQuietly(ChatStream stream, StreamChunk chunk) {
        try {
            stream.queue().put(chunk);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isRetryable(Throwable cause) {
        return cause instanceof ConnectException
                || cause instanceof HttpTimeoutException
                || cause instanceof SocketTimeoutException;
    }

    private static void sleepBackoff(int attempt, Long deadline) throws InterruptedException, HttpTimeoutException {
        long delay = Duration.ofSeconds(1L << (attempt - 1)).toNanos();
        TimeUnit.NANOSECONDS.sleep(deadline == null ? delay : Math.min(delay, remaining(deadline)));
        if (deadline != null) remaining(deadline);
    }

    private static long remaining(long deadline) throws HttpTimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new HttpTimeoutException("模型请求总预算已耗尽");
        return remaining;
    }

    private static void closeQuietly(InputStream body) {
        if (body != null) {
            try { body.close(); } catch (IOException ignored) { }
        }
    }

    /** 截止时主动关闭阻塞读取的响应体；消费者结束或取消时撤销定时任务并释放资源。 */
    private static final class RequestResources implements AutoCloseable {
        final AtomicBoolean closed = new AtomicBoolean();
        volatile CompletableFuture<?> future;
        volatile InputStream body;
        volatile ChatStream stream;
        volatile Thread reader;
        private final ScheduledFuture<?> timeout;

        RequestResources(Long deadline) throws HttpTimeoutException {
            timeout = deadline == null ? null : DEADLINES.schedule(() -> {
                ChatStream active = stream;
                if (active != null) active.timeout();
                close();
            }, remaining(deadline), TimeUnit.NANOSECONDS);
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            if (timeout != null) timeout.cancel(false);
            CompletableFuture<?> active = future;
            if (active != null) active.cancel(true);
            closeQuietly(body);
            Thread worker = reader;
            if (worker != null && worker != Thread.currentThread()) worker.interrupt();
        }
    }

    private static String rootMessage(ExecutionException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    private static String summarizeError(int status, String body) {
        try {
            ObjectMapper m = new ObjectMapper();
            JsonNode message = m.readTree(body).path("error").path("message");
            if (message.isTextual()) {
                return "HTTP " + status + ": " + message.textValue();
            }
        } catch (IOException ignored) {
            // 非 JSON 错误体，退回原始文本
        }
        String raw = body.strip();
        return "HTTP " + status + (raw.isEmpty() ? "" : ": " + raw.substring(0, Math.min(300, raw.length())));
    }
}
