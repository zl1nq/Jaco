package com.jaco.tool.builtin;

import com.jaco.tool.ToolException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.util.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

/** 每跳独立连接，固定已验证地址；保留原始主机用于 Host、SNI 和默认证书验证。 */
final class WebHttpTransport {
    static final int MAX_BYTES = 2 * 1024 * 1024;

    record Response(int status, String contentType, String location, byte[] body) {
    }

    @FunctionalInterface
    interface Transport {
        Response get(URI url, InetAddress[] addresses, Abort abort) throws Exception;
    }

    static final class Abort {
        private final AtomicBoolean stopped = new AtomicBoolean();
        final AtomicReference<HttpGet> request = new AtomicReference<>();

        void cancel() {
            stopped.set(true);
            HttpGet current = request.get();
            if (current != null) current.cancel();
        }

        void check() throws InterruptedException {
            if (stopped.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException("网页抓取已取消");
        }
    }

    Response get(URI url, InetAddress[] addresses, Abort abort) throws Exception {
        abort.check();
        String expectedHost = WebUrlPolicy.host(url);
        InetAddress[] pinned = addresses.clone();
        DnsResolver resolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                if (!expectedHost.equalsIgnoreCase(host)) throw new UnknownHostException("未校验的目标主机");
                return pinned.clone();
            }

            @Override
            public String resolveCanonicalHostname(String host) throws UnknownHostException {
                resolve(host);
                return expectedHost;
            }
        };
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver)
                .setMaxConnTotal(1).setMaxConnPerRoute(1)
                .setConnectionFactory(ManagedHttpClientConnectionFactory.builder().http1Config(Http1Config.custom()
                        .setMaxHeaderCount(100).setMaxLineLength(8192).build()).build())
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(5))
                        .setSocketTimeout(Timeout.ofSeconds(10)).build()).build();
        HttpGet request = new HttpGet(url);
        request.setHeader("Accept", "text/html,application/xhtml+xml,text/plain,application/json,text/markdown");
        request.setHeader("Accept-Encoding", "gzip");
        abort.request.set(request);
        try (var client = HttpClients.custom().setConnectionManager(manager).setUserAgent("Jaco/0.1 web_fetch")
                .disableRedirectHandling().disableAutomaticRetries().disableContentCompression()
                .disableCookieManagement().disableAuthCaching().build()) {
            abort.check();
            ClassicHttpResponse response = client.executeOpen(null, request, null);
            try {
                int code = response.getCode();
                String type = header(response, "Content-Type");
                String location = header(response, "Location");
                if (redirect(code)) return new Response(code, type, location, new byte[0]);
                if (code < 200 || code >= 300) throw new ToolException("网页返回 HTTP " + code);
                if (response.getEntity() == null) return new Response(code, "text/plain", "", new byte[0]);
                WebContent.media(type);
                if (response.getEntity().getContentLength() > MAX_BYTES) throw new ToolException("响应超过 2 MiB 上限");
                InputStream wire = new FilterInputStream(response.getEntity().getContent()) {
                    int count;

                    private void add(int n) {
                        if (n > 0 && (count += n) > MAX_BYTES) throw new ToolException("传输响应超过 2 MiB 上限");
                    }

                    @Override
                    public int read() throws IOException {
                        int value = in.read();
                        if (value >= 0) add(1);
                        return value;
                    }

                    @Override
                    public int read(byte[] bytes, int offset, int length) throws IOException {
                        int n = in.read(bytes, offset, length);
                        add(n);
                        return n;
                    }
                };
                String encoding = header(response, "Content-Encoding").strip().toLowerCase(Locale.ROOT);
                InputStream body = switch (encoding) {
                    case "", "identity" -> wire;
                    case "gzip" -> new GZIPInputStream(wire);
                    default -> throw new ToolException("不支持的响应压缩编码: " + encoding);
                };
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = body.read(buffer)) != -1) {
                    abort.check();
                    if (bytes.size() + n > MAX_BYTES) throw new ToolException("解压后正文超过 2 MiB 上限");
                    bytes.write(buffer, 0, n);
                }
                return new Response(code, type, "", bytes.toByteArray());
            } finally {
                // 先断开连接，避免关闭未读取的重定向/错误实体时继续排空无限响应。
                request.cancel();
                try {
                    response.close();
                } catch (IOException ignored) {
                    // 已主动断开连接，清理时的 Socket closed 不能覆盖正文上限、HTTP 状态等原始错误。
                }
            }
        } finally {
            abort.request.compareAndSet(request, null);
        }
    }

    static boolean redirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    private String header(ClassicHttpResponse response, String name) {
        var header = response.getFirstHeader(name);
        return header == null ? "" : header.getValue();
    }
}
