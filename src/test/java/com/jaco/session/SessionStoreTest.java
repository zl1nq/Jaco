package com.jaco.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStoreTest {

    @TempDir
    Path tmp;

    @Test
    void listSessionsSortedNewestFirst() throws Exception {
        SessionStore store = new SessionStore(tmp);
        Session old = Session.create("s20260101-000000", 1000);
        old.messages().add(com.jaco.llm.Message.user("旧会话内容"));
        Session fresh = Session.create("s20260102-000000", 2000);
        store.save(old);
        store.save(fresh);

        List<Session> sessions = store.listSessions();
        assertEquals(2, sessions.size());
        assertEquals("s20260102-000000", sessions.get(0).id());
        assertEquals("s20260101-000000", sessions.get(1).id());
        assertEquals("旧会话内容", sessions.get(1).messages().get(0).content());
    }

    @Test
    void loadRejectsPathTraversalAndMissingId() throws Exception {
        SessionStore store = new SessionStore(tmp);
        assertTrue(store.load("../escape").isEmpty());
        assertTrue(store.load("snope").isEmpty());
    }

    @Test
    void loadRoundTripsSavedSession() throws Exception {
        SessionStore store = new SessionStore(tmp);
        Session session = Session.create("s20260103-000000", 3000);
        session.messages().add(com.jaco.llm.Message.user("内容"));
        session.compactedToolCallIds().add("call-1");
        store.save(session);

        Session loaded = store.load("s20260103-000000").orElseThrow();
        assertEquals(1, loaded.messages().size());
        assertEquals(java.util.Set.of("call-1"), loaded.compactedToolCallIds());
    }

    @Test
    void loadsLegacySessionWithoutCompactionMetadata() throws Exception {
        SessionStore store = new SessionStore(tmp);
        Files.writeString(tmp.resolve("legacy.json"),
                "{\"id\":\"legacy\",\"createdAt\":1,\"messages\":[]}");
        Session loaded = store.load("legacy").orElseThrow();
        assertTrue(loaded.compactedToolCallIds().isEmpty());
        loaded.compactedToolCallIds().add("call-1");
        assertEquals(java.util.Set.of("call-1"), loaded.compactedToolCallIds());
    }
}
