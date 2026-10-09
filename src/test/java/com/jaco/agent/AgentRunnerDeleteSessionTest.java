package com.jaco.agent;

import com.jaco.config.ProviderConfig;
import com.jaco.hook.AgentHook;
import com.jaco.hook.HookChain;
import com.jaco.llm.Message;
import com.jaco.session.Session;
import com.jaco.session.SessionStore;
import com.jaco.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class AgentRunnerDeleteSessionTest {

    @TempDir
    Path tmp;

    private static final String OLDER = "s20260101-000000";
    private static final String CURRENT = "s20260102-000000";
    private SessionStore store;
    private final AtomicInteger sessionChanges = new AtomicInteger();

    private AgentRunner runner() throws Exception {
        store = new SessionStore(tmp);
        Session older = Session.create(OLDER, 1000).withFirstPromptTitle("排查配置加载");
        older.messages().add(Message.user("排查配置加载"));
        store.save(older);
        store.appendArchivedOutput(older, "原始归档内容");
        store.save(Session.create(CURRENT, 2000).withFirstPromptTitle("当前任务"));
        AgentHook hook = new AgentHook() {
            @Override
            public void onSessionChanged() {
                sessionChanges.incrementAndGet();
            }
        };
        AgentRunner agent = new AgentRunner(null,
                new ProviderConfig("http://localhost", "k", "m", null),
                null, store, new HookChain(List.of(hook)), new ToolRegistry(),
                null, tmp, "auto", 25, 100_000, 8000);
        agent.start();
        return agent;
    }

    @Test
    void confirmedExactIdDeletesSessionAndArchiveWithoutChangingCurrentSession() throws Exception {
        AgentRunner agent = runner();
        Session current = agent.session();
        AtomicInteger confirmations = new AtomicInteger();
        String result = agent.deleteSession(OLDER, target -> {
            confirmations.incrementAndGet();
            assertEquals(OLDER, target.id());
            assertEquals("排查配置加载", target.displayTitle());
            return true;
        });

        assertTrue(result.startsWith("已删除会话 " + OLDER));
        assertEquals(1, confirmations.get());
        assertFalse(Files.exists(tmp.resolve(OLDER + ".json")));
        assertFalse(Files.exists(tmp.resolve(OLDER + ".archive.jsonl")));
        assertSame(current, agent.session());
        assertEquals(0, sessionChanges.get());
        assertEquals(List.of(CURRENT), agent.listSessions().stream().map(Session::id).toList());
        assertTrue(agent.switchSession(OLDER).startsWith("没有匹配"));
        assertEquals(CURRENT, store.loadLatestOrNew().id());
    }

    @Test
    void uniquePrefixDeletesOnlySelectedSession() throws Exception {
        AgentRunner agent = runner();
        store.save(Session.create("s20260101-000001", 1500));
        String result = agent.deleteSession("s20260101-000000", target -> true);
        assertTrue(result.startsWith("已删除会话"));
        assertTrue(store.load("s20260101-000001").isPresent());

        assertTrue(agent.deleteSession("s20260101", target -> true).startsWith("已删除会话"));
        assertTrue(store.load("s20260101-000001").isEmpty());
        assertTrue(store.load(CURRENT).isPresent());
    }

    @Test
    void cancellationPreservesBothFiles() throws Exception {
        AgentRunner agent = runner();
        String before = Files.readString(tmp.resolve(OLDER + ".archive.jsonl"));
        assertEquals("已取消删除", agent.deleteSession("s20260101", target -> false));
        assertTrue(store.load(OLDER).isPresent());
        assertEquals(before, Files.readString(tmp.resolve(OLDER + ".archive.jsonl")));
    }

    @Test
    void invalidAmbiguousAndCurrentTargetsNeverAskForConfirmation() throws Exception {
        AgentRunner agent = runner();
        for (String target : List.of("", "../outside", "s2026", "s9999", CURRENT, "s20260102")) {
            String result = agent.deleteSession(target, s -> {
                fail("不应请求确认: " + target);
                return true;
            });
            assertFalse(result.startsWith("已删除"));
            assertEquals(2, agent.listSessions().size());
        }
        assertTrue(agent.deleteSession(CURRENT, s -> true).contains("请先 /switch"));
    }

    @Test
    void archiveDeletionFailureIsReportedAndPreservesSession() throws Exception {
        AgentRunner agent = runner();
        Path archive = tmp.resolve(OLDER + ".archive.jsonl");
        Files.delete(archive);
        Files.createDirectory(archive);
        String result = agent.deleteSession(OLDER, target -> true);
        assertTrue(result.startsWith("删除失败:"));
        assertTrue(store.load(OLDER).isPresent());
        assertTrue(Files.isDirectory(archive));
    }

    @Test
    void targetBecomingCurrentDuringConfirmationCannotBeDeleted() throws Exception {
        AgentRunner agent = runner();
        String result = agent.deleteSession(OLDER, target -> {
            agent.switchSession(OLDER);
            return true;
        });
        assertTrue(result.startsWith("不能删除当前会话"));
        assertTrue(store.load(OLDER).isPresent());
        assertTrue(Files.exists(tmp.resolve(OLDER + ".archive.jsonl")));
    }
}
