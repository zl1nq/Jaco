package com.jaco.llm;

import java.util.concurrent.CompletableFuture;

/** 测试用：直接构造已填充事件的 ChatStream，绕过 HTTP。 */
public final class TestStreams {

    private TestStreams() {
    }

    public static ChatStream of(StreamChunk... chunks) {
        ChatStream stream = new ChatStream(CompletableFuture.completedFuture(null));
        for (StreamChunk c : chunks) {
            stream.queue().offer(c);
        }
        return stream;
    }
}
