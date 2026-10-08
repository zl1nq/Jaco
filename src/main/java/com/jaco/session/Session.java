package com.jaco.session;

import com.jaco.llm.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** 会话消息及已压缩工具结果的调用 ID；元数据随会话保存，不进入模型请求。 */
public record Session(String id, long createdAt, List<Message> messages,
                      Set<String> compactedToolCallIds) {

    public Session(String id, long createdAt, List<Message> messages) {
        this(id, createdAt, messages, Set.of());
    }

    public static Session create(String id, long createdAt) {
        return new Session(id, createdAt, new ArrayList<>());
    }

    public Session {
        messages = messages instanceof ArrayList<Message> ? messages : new ArrayList<>(messages);
        compactedToolCallIds = compactedToolCallIds == null
                ? new HashSet<>() : new HashSet<>(compactedToolCallIds);
    }
}
