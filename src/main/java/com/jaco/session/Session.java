package com.jaco.session;

import com.jaco.llm.Message;
import com.jaco.llm.Role;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** 会话消息及已压缩工具结果的调用 ID；元数据随会话保存，不进入模型请求。 */
public record Session(String id, long createdAt, List<Message> messages,
                      Set<String> compactedToolCallIds, String title) {

    public Session(String id, long createdAt, List<Message> messages, Set<String> compactedToolCallIds) {
        this(id, createdAt, messages, compactedToolCallIds, null);
    }

    public Session(String id, long createdAt, List<Message> messages) {
        this(id, createdAt, messages, Set.of(), null);
    }

    public static Session create(String id, long createdAt) {
        return new Session(id, createdAt, new ArrayList<>());
    }

    public Session {
        messages = messages instanceof ArrayList<Message> ? messages : new ArrayList<>(messages);
        compactedToolCallIds = compactedToolCallIds == null
                ? new HashSet<>() : new HashSet<>(compactedToolCallIds);
        // 旧会话缺少标题时，从尚存的用户消息提取，跳过程序注入的压缩和续轮标记。
        if (title == null || title.isBlank()) {
            title = messages.stream()
                    .filter(m -> m.role() == Role.USER)
                    .map(m -> titleFrom(m.content()))
                    .filter(t -> !t.isEmpty() && !t.startsWith("[早前 ")
                            && !t.startsWith("[历史摘要]") && !t.startsWith("[onStop hook]"))
                    .findFirst().orElse(null);
        }
    }

    /** 首次有效输入确定标题；后续输入和压缩不修改它。 */
    public Session withFirstPromptTitle(String prompt) {
        if (title != null && !title.isBlank()) {
            return this;
        }
        String candidate = titleFrom(prompt);
        return candidate.isEmpty() ? this
                : new Session(id, createdAt, messages, compactedToolCallIds, candidate);
    }

    public String displayTitle() {
        return title == null || title.isBlank() ? "未命名会话" : title;
    }

    private static String titleFrom(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder();
        text.codePoints().forEach(c -> cleaned.appendCodePoint(
                Character.isWhitespace(c) || Character.isISOControl(c) ? ' ' : c));
        String title = cleaned.toString().replaceAll(" +", " ").strip();
        return title.codePointCount(0, title.length()) > 40
                ? title.substring(0, title.offsetByCodePoints(0, 40)) + "…" : title;
    }
}
