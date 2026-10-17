package com.jaco.tool.builtin;

import com.jaco.tool.ToolException;
import org.apache.hc.core5.http.ContentType;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** 只解析已获取的字节，不加载脚本、图片、样式或链接。 */
final class WebContent {
    static final int MAX_CHARS = 500_000;
    private static final Set<String> BLOCKS = Set.of("p", "div", "section", "article", "main", "h1", "h2", "h3",
            "h4", "h5", "h6", "ul", "ol", "li", "blockquote", "tr", "pre", "table");

    record Parsed(String title, String text, boolean truncated) {
    }

    static ContentType media(String header) {
        try {
            ContentType type = ContentType.parse(header);
            if (type == null) throw new ToolException("响应缺少 Content-Type");
            String mime = type.getMimeType().toLowerCase(Locale.ROOT);
            if (!Set.of("text/html", "application/xhtml+xml", "text/plain", "text/markdown", "text/csv", "application/json")
                    .contains(mime) && !mime.endsWith("+json")) {
                throw new ToolException("不支持的网页内容类型: " + mime);
            }
            return type;
        } catch (IllegalArgumentException e) {
            throw new ToolException("响应 Content-Type 或字符编码无效");
        }
    }

    Parsed extract(WebHttpTransport.Response response, URI base) throws Exception {
        ContentType type = media(response.contentType());
        String mime = type.getMimeType();
        if (!mime.equals("text/html") && !mime.equals("application/xhtml+xml")) {
            String text = new String(response.body(), type.getCharset() == null ? StandardCharsets.UTF_8 : type.getCharset());
            if (text.startsWith("\ufeff")) text = text.substring(1);
            if (text.indexOf('\0') >= 0) throw new ToolException("响应包含二进制零字符，无法作为正文读取");
            int end = Math.min(MAX_CHARS, text.length());
            if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            return new Parsed("", text.substring(0, end), end < text.length());
        }
        var document = Jsoup.parse(new ByteArrayInputStream(response.body()),
                type.getCharset() == null ? null : type.getCharset().name(), base.toString());
        String title = document.title().strip();
        if (title.length() > 300) title = title.substring(0, 300);
        if (!title.isEmpty() && Character.isHighSurrogate(title.charAt(title.length() - 1))) title = title.substring(0, title.length() - 1);
        document.select("script,style,noscript,template,iframe,object,embed,svg,canvas,nav,footer,aside").remove();
        Element body = document.selectFirst("main");
        if (body == null) body = document.selectFirst("article");
        if (body == null) body = document.body();
        Text text = new Text();
        NodeTraversor.traverse(new NodeVisitor() {
            int pre;
            int code;
            int links;

            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode value) {
                    text.add(pre > 0 || code > 0 ? value.getWholeText()
                            : value.getWholeText().replaceAll("[\\s\\u00a0]+", " "));
                } else if (node instanceof Element element) {
                    String tag = element.normalName();
                    if (BLOCKS.contains(tag) || tag.equals("br")) text.newline();
                    if (tag.equals("li")) text.add("- ");
                    if (tag.equals("pre")) {
                        text.add("```\n");
                        pre++;
                    }
                    if (tag.equals("code")) {
                        if (pre == 0) text.add("`");
                        code++;
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (!(node instanceof Element element)) return;
                String tag = element.normalName();
                if (tag.equals("a") && links++ < 1000) {
                    String link = element.absUrl("href");
                    try {
                        if (!link.isEmpty()) text.add(" (" + WebUrlPolicy.parse(link) + ")");
                    } catch (ToolException ignored) {
                        // 不把 javascript、凭据链接等导出为可用网址。
                    }
                }
                if (tag.equals("code")) {
                    code--;
                    if (pre == 0) text.add("`");
                }
                if (tag.equals("pre")) {
                    pre--;
                    text.newline();
                    text.add("```\n");
                }
                if (BLOCKS.contains(tag)) text.newline();
            }
        }, body);
        return new Parsed(title, text.value.toString().strip(), text.truncated);
    }

    private static final class Text {
        final StringBuilder value = new StringBuilder();
        boolean truncated;

        void add(String text) {
            if (Thread.currentThread().isInterrupted()) throw new ToolException("正文解析已取消");
            int count = Math.min(text.length(), MAX_CHARS - value.length());
            if (count > 0 && Character.isHighSurrogate(text.charAt(count - 1))) count--;
            value.append(text, 0, count);
            truncated |= count < text.length();
        }

        void newline() {
            if (!value.isEmpty() && value.charAt(value.length() - 1) != '\n') add("\n");
        }
    }
}
