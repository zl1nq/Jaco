package com.jaco.skill;

import com.jaco.session.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class SkillServiceTest {
    @TempDir Path dir;
    private SkillRegistry registry(String fields, String body) throws Exception {
        Path file = dir.resolve("skills/review/SKILL.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\nname: review\ndescription: Java review\n" + fields + "---\n" + body);
        return new SkillRegistry(null, dir.resolve("skills"));
    }
    @Test void loadsOnceAndRecordsOriginWithoutLeakingIntoNextTurn() throws Exception {
        var registry = registry("", "COMPLETE_RULE");
        var store = new SessionStore(dir.resolve("sessions"));
        var session = store.createNew();
        var service = new SkillService(registry, session, store, "turn", () -> true, 100000);
        assertFalse(service.context().contains("COMPLETE_RULE"));
        service.load("review", false);
        assertTrue(service.context().contains("COMPLETE_RULE"));
        assertTrue(service.load("project:review", false).contains("已在本轮"));
        assertEquals(1, Files.readAllLines(store.archivePath(session)).size());
        String record = Files.readString(store.archivePath(session));
        assertTrue(record.contains("skill_invocation"));
        assertTrue(record.contains(session.id()));
        assertTrue(record.contains("model"));
        var next = new SkillService(registry, session, store, "next", () -> true, 100000);
        assertFalse(next.context().contains("COMPLETE_RULE"));
    }
    @Test void invocationFlagsAndArchivedFailuresCannotActivate() throws Exception {
        var store = new SessionStore(dir.resolve("sessions"));
        var session = store.createNew();
        var manual = new SkillService(registry("disable-model-invocation: true\n", "MANUAL"), session, store, "t", () -> true, 100000);
        assertFalse(manual.list("", 0, 20, 8000).contains("review"));
        assertThrows(RuntimeException.class, () -> manual.load("review", false));
        manual.load("review", true);
        var model = new SkillService(registry("user-invocable: false\n", "MODEL"), session, store, "t", () -> true, 100000);
        assertThrows(RuntimeException.class, () -> model.load("review", true));
        model.load("review", false);
        var other = store.createNew();
        Files.createDirectory(store.archivePath(other));
        var failed = new SkillService(registry("", "FAILED"), other, store, "t", () -> true, 100000);
        assertThrows(java.io.UncheckedIOException.class, () -> failed.load("review", false));
        assertFalse(failed.hasLoaded());
    }
    @Test void enforcesBodyBudgetVersionAndCancellation() throws Exception {
        var registry = registry("", "x".repeat(20000));
        var store = new SessionStore(dir.resolve("s"));
        var session = store.createNew();
        var active = new AtomicBoolean(true);
        var service = new SkillService(registry, session, store, "t", active::get, 10000);
        assertThrows(RuntimeException.class, () -> service.load("review", false));
        assertFalse(service.hasLoaded());
        assertFalse(Files.exists(store.archivePath(session)));
        active.set(false);
        assertThrows(RuntimeException.class, () -> service.list("", 0, 10, 8000));
        assertThrows(RuntimeException.class, service::context);
        active.set(true);
        Files.writeString(dir.resolve("skills/review/SKILL.md"), "changed");
        assertThrows(java.io.IOException.class, () -> service.load("review", false));
    }
    @Test void catalogAndPagesRespectBudgetAndExposeContinuation() throws Exception {
        var registry = registry("", "body");
        for (int i = 0; i < 30; i++) {
            Path file = dir.resolve("skills/s" + i + "/SKILL.md");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "---\nname: s" + i + "\ndescription: " + "中".repeat(900) + "\n---\nBody");
        }
        registry.reload();
        var store = new SessionStore(dir.resolve("s"));
        var service = new SkillService(registry, store.createNew(), store, "t", () -> true, 20000);
        assertTrue(service.context().length() <= 4000);
        assertTrue(SkillService.tokens(service.context()) <= 1000);
        assertTrue(service.context().contains("skill_list"));
        String page = service.list("", 0, 2, 8000);
        assertTrue(page.contains("next_offset: 2"));
        assertTrue(page.contains("has_more: true"));
        assertTrue(service.list("nomatch", 0, 20, 8000).contains("total: 0"));
        assertThrows(RuntimeException.class, () -> service.list("", 0, 2, 10));
    }
}
