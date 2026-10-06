package com.jaco.session;

import com.jaco.llm.Message;

import java.util.ArrayList;
import java.util.List;

/** 一个会话 = 消息历史。v1 无元数据，M1 需要时再扩展字段（向后兼容地加）。 */
public record Session(String id, long createdAt, List<Message> messages) {

    public static Session create(String id, long createdAt) {
        return new Session(id, createdAt, new ArrayList<>());
    }

    public Session {
        messages = messages instanceof ArrayList<Message> ? messages : new ArrayList<>(messages);
    }
}
