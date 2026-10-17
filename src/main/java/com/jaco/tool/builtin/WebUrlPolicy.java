package com.jaco.tool.builtin;

import com.jaco.tool.ToolException;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/** URL 与地址校验；DNS 结果随后必须固定交给连接器，不能再次解析。 */
public final class WebUrlPolicy {
    private WebUrlPolicy() {
    }

    static URI parse(String value) {
        if (value == null || value.length() > 2048) throw new ToolException("url 必须提供且不能超过 2048 字符");
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if ((!scheme.equals("http") && !scheme.equals("https")) || host == null || uri.getRawUserInfo() != null) {
                throw new ToolException("只接受有明确主机名且不含用户名密码的 HTTP/HTTPS URL；国际化域名请使用 punycode");
            }
            if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
            host = host.toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            if (host.contains("%") || host.equals("localhost") || host.endsWith(".localhost")
                    || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".home.arpa")
                    || (!host.contains(".") && !host.contains(":"))) {
                throw new ToolException("不允许本机或内部主机名");
            }
            int port = uri.getPort();
            if (port == 0 || port > 65535) throw new ToolException("URL 端口必须是 1-65535");
            String authority = host.contains(":") ? "[" + host + "]" : host;
            String path = uri.getRawPath();
            return URI.create(scheme + "://" + authority + (port < 0 ? "" : ":" + port)
                    + (path == null || path.isEmpty() ? "/" : path)
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())).normalize();
        } catch (java.net.URISyntaxException | IllegalArgumentException e) {
            throw new ToolException("url 格式无效");
        }
    }

    public static String origin(String url) {
        URI uri = parse(url);
        return uri.getScheme() + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":"
                + (uri.getPort() < 0 ? uri.getScheme().equals("https") ? 443 : 80 : uri.getPort());
    }

    static String host(URI uri) {
        String host = uri.getHost();
        return host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
    }

    static InetAddress[] publicAddresses(InetAddress[] addresses) {
        if (addresses == null || addresses.length == 0 || addresses.length > 16) {
            throw new ToolException("DNS 未返回可用地址或地址数量超限");
        }
        for (InetAddress address : addresses) {
            if (address == null || !isPublic(address)) {
                throw new ToolException("目标解析到非公共地址，已拒绝连接");
            }
        }
        return addresses.clone();
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int a = bytes[0] & 255;
        int b = bytes[1] & 255;
        if (bytes.length == 4) {
            int c = bytes[2] & 255;
            return !(a == 0 || a == 10 || a == 127 || a >= 224
                    || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                    // Azure 平台虚拟地址并非普通公网服务，见 Microsoft Learn 的 168.63.129.16 说明。
                    || (a == 168 && b == 63 && c == 129 && (bytes[3] & 255) == 16)
                    || (a == 192 && (b == 168 || (b == 0 && (c == 0 || c == 2)) || (b == 88 && c == 99)))
                    || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                    || (a == 203 && b == 0 && c == 113));
        }
        // 仅允许 IPv6 全球单播，并排除文档、协议分配及含 IPv4 隧道的特殊段。
        return bytes.length == 16 && (a & 0xe0) == 0x20
                && !(a == 0x20 && b == 0x01 && (bytes[2] & 0xfe) == 0)
                && !(a == 0x20 && b == 0x01 && (bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8)
                && !(a == 0x20 && b == 0x02)
                && !(a == 0x3f && b == 0xff && (bytes[2] & 0xf0) == 0);
    }
}
