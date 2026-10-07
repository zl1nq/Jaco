package com.jaco.agent;

import com.jaco.config.ProviderConfig;
import com.jaco.hook.HookChain;
import com.jaco.session.Session;
import com.jaco.session.SessionStore;
import com.jaco.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunnerSwitchSessionTest {

    @TempDir
    Path tmp;

    private AgentRunner runner(SessionStore store) {
        return new AgentRunner(null,
                new ProviderConfig("http://localhost", "k", "m", null),
                null, store, new HookChain(List.of()), new ToolRegistry(),
                null, tmp, "auto", 25, 100_000, 8000);
    }

    @Test
    void switchByExactIdAndUniquePrefix() throws Exception {
        SessionStore store = new SessionStore(tmp);
        Session a = Session.create("s20260101-000000", 1000);
        a.messages().add(com.jaco.llm.Message.user("A 的内容"));
        Session b = Session.create("s20260102-000000", 2000);
        b.messages().add(com.jaco.llm.Message.user("B 的内容"));
        store.save(a);
        store.save(b);

        AgentRunner agent = runner(store);
        agent.start(); // 载入最近会话（b）
        assertEquals("s20260102-000000", agent.session().id());

        assertNull(agent.switchSession("s20260101-000000"));
        assertEquals("s20260101-000000", agent.session().id());
        assertEquals("A 的内容", agent.session().messages().get(0).content());

        // 唯一前缀：两个 id 共享 "s2026"，只有 01 开头唯一
        assertNull(agent.switchSession("s20260102"));
        assertEquals("s20260102-000000", agent.session().id());
    }

    @Test
    void switchRejectsAmbiguousMissingAndCurrent() throws Exception {
        SessionStore store = new SessionStore(tmp);
        store.save(Session.create("s20260101-000000", 1000));
        store.save(Session.create("s20260102-000000", 2000));

        AgentRunner agent = runner(store);
        agent.start();
        String current = agent.session().id();

        assertTrue(agent.switchSession("s2026").startsWith("前缀不唯一"));
        assertTrue(agent.switchSession("s9999").startsWith("没有匹配"));
        assertEquals("已是当前会话", agent.switchSession(current));
        assertEquals(current, agent.session().id());
    }
}
