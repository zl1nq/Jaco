package com.jaco.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UserProfileStoreTest {
    @TempDir Path tmp;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static String operation(String action, String category, String key, String value, String scope, String evidence)
            throws Exception {
        return MAPPER.writeValueAsString(Map.of("operations", List.of(Map.of(
                "action", action, "category", category, "key", key,
                "value", value, "scope", scope, "evidence", evidence))));
    }

    private UserProfileStore store() throws IOException {
        return new UserProfileStore(tmp.resolve("user-profile.json"));
    }

    @Test void updatesStableEntryAndPersistsSourceAcrossReload() throws Exception {
        UserProfileStore store = store();
        store.apply(operation("set", "preference", "response.language", "中文", "global", "默认中文"),
                "以后默认中文", "s1", tmp.toString());
        ProfileEntry first = store.entries().get(0);
        store.apply(operation("set", "preference", "response.language", "英文", "global", "改用英文"),
                "以后改用英文", "s2", tmp.toString());
        ProfileEntry updated = store().entries().get(0);
        assertEquals(first.id(), updated.id());
        assertEquals(first.createdAt(), updated.createdAt());
        assertEquals("英文", updated.value());
        assertEquals("s2", updated.sourceSession());
        assertEquals("改用英文", updated.evidence());
        assertTrue(updated.updatedAt() >= first.updatedAt());
        assertEquals(1, store.entries().size());
    }

    @Test void repeatedValueIsNoOpAndPreservesOriginalEvidence() throws Exception {
        UserProfileStore store = store();
        String op = operation("set", "fact", "identity.occupation", "工程师", "global", "我是工程师");
        store.apply(op, "我是工程师", "s1", tmp.toString());
        byte[] before = Files.readAllBytes(tmp.resolve("user-profile.json"));
        assertTrue(store.apply(op, "我是工程师", "s2", tmp.toString()).isEmpty());
        assertArrayEquals(before, Files.readAllBytes(tmp.resolve("user-profile.json")));
    }

    @Test void projectOverridesGlobalAndOtherProjectsAreExcluded() throws Exception {
        UserProfileStore store = store();
        String a = tmp.resolve("a").toString();
        String b = tmp.resolve("b").toString();
        store.apply(operation("set", "preference", "response.language", "中文", "global", "中文"), "中文", "s", a);
        store.apply(operation("set", "preference", "response.language", "英文", "project", "英文"), "英文", "s", a);
        store.apply(operation("set", "fact", "identity.role", "项目B管理员", "project", "管理员"), "管理员", "s", b);
        String context = store.context(a);
        assertTrue(context.contains("英文"));
        assertFalse(context.contains("中文"));
        assertFalse(context.contains("项目B管理员"));
        assertTrue(store.context(tmp.toString()).contains("中文"));
    }

    @Test void disablingPersistsAndPreventsExtractionAndInjectionWithoutDeletingEntries() throws Exception {
        UserProfileStore store = store();
        String op = operation("set", "fact", "identity.name", "小张", "global", "小张");
        store.apply(op, "小张", "s", tmp.toString());
        store.setEnabled(false);
        UserProfileStore reloaded = store();
        assertFalse(reloaded.enabled());
        assertEquals("", reloaded.context(tmp.toString()));
        assertEquals(1, reloaded.entries().size());
        assertTrue(reloaded.apply("invalid", "", "s", tmp.toString()).isEmpty());
        reloaded.setEnabled(true);
        assertTrue(store().context(tmp.toString()).contains("小张"));
    }

    @Test void automaticDeleteAndManualForgetAndClearPersist() throws Exception {
        UserProfileStore store = store();
        store.apply(operation("set", "fact", "identity.name", "小张", "global", "小张"), "小张", "s", tmp.toString());
        store.apply(operation("delete", "fact", "identity.name", "", "global", "忘记我的名字"),
                "忘记我的名字", "s2", tmp.toString());
        assertTrue(store().entries().isEmpty());
        store.apply(operation("set", "fact", "identity.name", "小张", "global", "小张"), "小张", "s", tmp.toString());
        assertFalse(store.forget("missing"));
        assertTrue(store.forget(store.entries().get(0).id()));
        assertTrue(store().entries().isEmpty());
        store.apply(operation("set", "fact", "identity.name", "小张", "global", "小张"), "小张", "s", tmp.toString());
        store.setEnabled(false);
        store.clear();
        assertTrue(store().entries().isEmpty());
        assertFalse(store().enabled());
    }

    @Test void invalidEvidenceRejectsWholeBatchAndDoesNotChangeFile() throws Exception {
        UserProfileStore store = store();
        String good = operation("set", "fact", "identity.name", "小张", "global", "小张");
        store.apply(good, "小张", "s", tmp.toString());
        byte[] before = Files.readAllBytes(tmp.resolve("user-profile.json"));
        var operations = MAPPER.createArrayNode();
        operations.add(MAPPER.readTree(operation("set", "preference", "response.length", "简短", "global", "简短"))
                .path("operations").get(0));
        operations.add(MAPPER.readTree(operation("set", "fact", "identity.job", "医生", "global", "我是医生"))
                .path("operations").get(0));
        String batch = MAPPER.writeValueAsString(Map.of("operations", operations));
        assertThrows(IOException.class, () -> store.apply(batch, "简短", "s", tmp.toString()));
        assertEquals(1, store.entries().size());
        assertArrayEquals(before, Files.readAllBytes(tmp.resolve("user-profile.json")));
    }

    @Test void rejectsMalformedOutputAndUntrustedScopeAndControlCharacters() throws Exception {
        UserProfileStore store = store();
        for (String json : List.of("null", "[]", "{}", "{\"operations\":[]} trailing",
                "{\"operations\":[],\"operations\":[]}",
                operation("set", "fact", "identity.name", "小张", "other", "小张"),
                operation("set", "fact", "invalid key", "小张", "global", "小张"),
                operation("set", "fact", "identity.name", "小张\033[31m", "global", "小张"))) {
            assertThrows(IOException.class, () -> store.apply(json, "小张", "s", tmp.toString()), json);
        }
        assertTrue(store.entries().isEmpty());
        assertFalse(Files.exists(tmp.resolve("user-profile.json")));
    }

    @Test void corruptedFileIsPreservedInsteadOfReset() throws Exception {
        Path file = tmp.resolve("user-profile.json");
        Files.writeString(file, "{broken");
        assertThrows(IOException.class, () -> new UserProfileStore(file));
        assertEquals("{broken", Files.readString(file));
        Files.writeString(file, "{}");
        assertThrows(IOException.class, () -> new UserProfileStore(file));
    }

    @Test void capacityLimitRejectsNewEntryButAllowsUpdatingExistingEntry() throws Exception {
        UserProfileStore store = store();
        for (int i = 0; i < UserProfileStore.MAX_ENTRIES; i++) {
            store.apply(operation("set", "fact", "test.k" + i, "值", "global", "值"), "值", "s", tmp.toString());
        }
        assertThrows(IOException.class, () -> store.apply(operation("set", "fact", "test.extra", "值", "global", "值"),
                "值", "s", tmp.toString()));
        store.apply(operation("set", "fact", "test.k0", "新值", "global", "新值"), "新值", "s2", tmp.toString());
        assertEquals(UserProfileStore.MAX_ENTRIES, store().entries().size());
        assertTrue(store().entries().stream().anyMatch(e -> e.key().equals("test.k0") && e.value().equals("新值")));
    }

    @Test void contextIsBoundedAndContainsOnlyCompleteJsonEntries() throws Exception {
        UserProfileStore store = store();
        String value = "长".repeat(240);
        for (int i = 0; i < 64; i++) {
            store.apply(operation("set", "fact", "test.k" + i, value, "global", "长"), "长", "s", tmp.toString());
        }
        String context = store.context(tmp.toString());
        assertTrue(context.length() <= 12_000);
        assertTrue(MAPPER.readTree(context).isArray());
        assertFalse(context.contains("evidence"));
    }

    @Test void failedDiskWriteDoesNotChangeInMemoryState() throws Exception {
        Path parent = tmp.resolve("blocked");
        UserProfileStore store = new UserProfileStore(parent.resolve("user-profile.json"));
        Files.writeString(parent, "a file blocks directory creation");
        assertThrows(IOException.class, () -> store.setEnabled(false));
        assertTrue(store.enabled());
        assertThrows(IOException.class, () -> store.apply(
                operation("set", "fact", "identity.name", "小张", "global", "小张"), "小张", "s", tmp.toString()));
        assertTrue(store.entries().isEmpty());
    }
}
