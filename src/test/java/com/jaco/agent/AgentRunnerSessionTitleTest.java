package com.jaco.agent;

import com.jaco.config.ProviderConfig;
import com.jaco.hook.AgentHook;
import com.jaco.hook.HookChain;
import com.jaco.llm.ChatRequest;
import com.jaco.llm.ChatStream;
import com.jaco.llm.OpenAiCompatClient;
import com.jaco.llm.StreamChunk;
import com.jaco.llm.TestStreams;
import com.jaco.session.SessionStore;
import com.jaco.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class AgentRunnerSessionTitleTest {

    @TempDir
    Path tmp;

    @Test
    void realTurnsPersistFirstRawUserPromptAsTitle() throws Exception {
        OpenAiCompatClient client = new OpenAiCompatClient("http://localhost", "k") {
            @Override
            public ChatStream chatStream(ChatRequest request) {
                return TestStreams.of(new StreamChunk.Delta("完成"), new StreamChunk.Done("stop", null));
            }
        };
        AgentHook hook = new AgentHook() {
            @Override
            public String onUserPromptSubmit(String prompt) {
                return "hook 改写后的输入";
            }
        };
        SessionStore store = new SessionStore(tmp);
        AgentRunner agent = new AgentRunner(client,
                new ProviderConfig("http://localhost", "k", "m", null),
                null, store, new HookChain(List.of(hook)), new ToolRegistry(),
                null, tmp, "auto", 25, 100_000, 8000);
        agent.start();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (String prompt : List.of("修复归档检索", "运行相关测试")) {
                TurnHandle handle = agent.runTurn(prompt);
                try {
                    while (true) {
                        if (handle.poll(100) instanceof TurnEvent.Done done) {
                            assertNull(done.error());
                            break;
                        }
                    }
                } finally {
                    handle.cancel();
                }
            }
        });
        assertEquals("修复归档检索", agent.session().title());
        assertEquals("hook 改写后的输入", agent.session().messages().get(0).content());
        assertEquals("修复归档检索", store.load(agent.session().id()).orElseThrow().title());
    }
}
