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

class UserProfileBatchTest {
    @TempDir Path tmp;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private UserProfileStore store() throws IOException { return new UserProfileStore(tmp.resolve("profile.json")); }
    private String project() { return tmp.toString(); }
    private static Map<String, String> operation(String id, String value, String evidence) {
        return Map.of("message_id", id, "action", "set", "category", "preference",
                "key", "response.language", "value", value, "scope", "global", "evidence", evidence);
    }
    private static String output(List<Map<String, String>> operations) throws IOException {
        return MAPPER.writeValueAsString(Map.of("operations", operations));
    }

    @Test void queuePersistsRawMessageContextSessionAndIdsAndEmptyResultAcknowledgesIt() throws Exception {
        UserProfileStore store = store();
        store.enqueue("解释一下递归", "上一条回答", "s1", project());
        var batch = store.pendingBatch(project());
        assertEquals(batch, store().pendingBatch(project()));
        assertEquals("解释一下递归", batch.get(0).message());
        assertEquals("上一条回答", batch.get(0).previousAssistant());
        assertEquals("s1", batch.get(0).session());
        assertTrue(store.applyBatch("{\"operations\":[]}", batch, project()).isEmpty());
        assertEquals(0, store().pendingCount(project()));
        assertThrows(IOException.class, () -> store.applyBatch("{\"operations\":[]}", batch, project()));
    }

    @Test void evidenceMustBelongToReferencedMessageAndWholeBatchIsRetainedOnFailure() throws Exception {
        UserProfileStore store = store();
        store.enqueue("以后默认中文", "", "s1", project());
        store.enqueue("以后默认英文", "", "s2", project());
        var batch = store.pendingBatch(project());
        byte[] before = Files.readAllBytes(tmp.resolve("profile.json"));
        assertThrows(IOException.class, () -> store.applyBatch(output(List.of(
                operation(batch.get(0).id(), "中文", "中文"), operation(batch.get(1).id(), "中文", "中文"))), batch, project()));
        assertTrue(store.entries().isEmpty());
        assertEquals(batch, store().pendingBatch(project()));
        assertArrayEquals(before, Files.readAllBytes(tmp.resolve("profile.json")));
        assertThrows(IOException.class, () -> store.applyBatch(output(List.of(operation("unknown-id", "中文", "中文"))), batch, project()));
    }

    @Test void newestUserExpressionWinsEvenIfModelReturnsOperationsInReverseOrder() throws Exception {
        UserProfileStore store = store();
        store.enqueue("以后默认中文", "", "s1", project());
        store.enqueue("以后默认英文", "", "s2", project());
        var batch = store.pendingBatch(project());
        store.applyBatch(output(List.of(operation(batch.get(1).id(), "英文", "英文"),
                operation(batch.get(0).id(), "中文", "中文"))), batch, project());
        assertEquals(1, store().entries().size());
        assertEquals("英文", store().entries().get(0).value());
        assertEquals("s2", store().entries().get(0).sourceSession());
        assertEquals("英文", store().entries().get(0).evidence());
        assertEquals(0, store().pendingCount(project()));
    }

    @Test void projectsNeverMixAndAcknowledgementRemovesOnlySelectedProjectMessages() throws Exception {
        UserProfileStore store = store();
        String other = tmp.resolve("other").toString();
        store.enqueue("解释当前项目", "", "s1", project());
        store.enqueue("解释另一个项目", "", "s2", other);
        var batch = store.pendingBatch(project());
        assertEquals(1, batch.size());
        store.applyBatch("{\"operations\":[]}", batch, project());
        assertEquals(0, store().pendingCount(project()));
        assertEquals(1, store().pendingCount(other));
    }

    @Test void queueAndBatchAreBoundedWithoutDiscardingUnprocessedMessages() throws Exception {
        UserProfileStore store = store();
        for (int i = 0; i < 12; i++) store.enqueue("解释第" + i + "题", "", "s", project());
        var batch = store.pendingBatch(project());
        assertEquals(10, batch.size());
        store.applyBatch("{\"operations\":[]}", batch, project());
        assertEquals(2, store.pendingCount(project()));
        store.clear();
        store.enqueue("长".repeat(16_000), "上下文", "s", project());
        store.enqueue("长".repeat(16_000), "上下文", "s", project());
        assertEquals(1, store.pendingBatch(project()).size());
        store.applyBatch("{\"operations\":[]}", store.pendingBatch(project()), project());
        assertEquals(1, store().pendingCount(project()));
        assertThrows(IOException.class, () -> store.enqueue("长".repeat(16_001), "", "s", project()));
        assertEquals(1, store().pendingCount(project()));
    }

    @Test void pureConfirmationsDoNotEnterQueueAndImmediateExpressionsAreLocallyDetected() throws Exception {
        UserProfileStore store = store();
        for (String prompt : List.of("继续", "好的", "谢谢", "OK!", "")) store.enqueue(prompt, "", "s", project());
        assertEquals(0, store.pendingCount(project()));
        for (String prompt : List.of("以后默认中文", "我是Java开发者", "我喜欢简短回答", "忘记我的职业", "I prefer English")) {
            assertTrue(ProfileExtractionPolicy.immediate(prompt), prompt);
        }
        assertFalse(ProfileExtractionPolicy.immediate("运行单元测试"));
        assertTrue(ProfileExtractionPolicy.shouldQueue("好的，以后默认中文"));
    }

    @Test void cooldownPersistsAcrossReloadAndSuccessfulBatchClearsIt() throws Exception {
        UserProfileStore store = store();
        store.enqueue("解释递归", "", "s", project());
        store.markExtractionFailed();
        assertFalse(store().retryReady());
        store.applyBatch("{\"operations\":[]}", store.pendingBatch(project()), project());
        assertTrue(store().retryReady());
        assertEquals(0, store().pendingCount(project()));
    }

    @Test void manualForgettingDiscardsOlderCandidatesSoTheyCannotRecreateRemovedEntry() throws Exception {
        UserProfileStore store = store();
        store.apply("{\"operations\":[{\"action\":\"set\",\"category\":\"preference\",\"key\":\"response.language\","
                + "\"value\":\"中文\",\"scope\":\"global\",\"evidence\":\"中文\"}]}", "中文", "s", project());
        store.enqueue("用中文", "", "s", project());
        store.forget(store.entries().get(0).id());
        assertEquals(0, store().pendingCount(project()));
        assertTrue(store().entries().isEmpty());
    }

    @Test void legacyFileWithoutPendingFieldsStillLoadsAndUpgradesWhenQueued() throws Exception {
        Files.writeString(tmp.resolve("profile.json"), "{\"enabled\":true,\"entries\":[]}");
        UserProfileStore store = store();
        assertEquals(0, store.pendingCount(project()));
        store.enqueue("解释递归", "", "s", project());
        assertEquals(1, store().pendingCount(project()));
    }

    @Test void failedAtomicWritePreservesInMemoryBatchAndProfileTogether() throws Exception {
        UserProfileStore store = store();
        store.enqueue("默认中文", "", "s", project());
        var batch = store.pendingBatch(project());
        Files.delete(tmp.resolve("profile.json"));
        Files.createDirectory(tmp.resolve("profile.json"));
        Files.writeString(tmp.resolve("profile.json/sentinel"), "keep");
        assertThrows(IOException.class, () -> store.applyBatch(
                output(List.of(operation(batch.get(0).id(), "中文", "中文"))), batch, project()));
        assertTrue(store.entries().isEmpty());
        assertEquals(batch, store.pendingBatch(project()));
        assertEquals("keep", Files.readString(tmp.resolve("profile.json/sentinel")));
    }

    @Test void olderCandidatesFromAnotherProjectCannotOverwriteNewerGlobalPreferenceAfterReload() throws Exception {
        UserProfileStore store = store();
        String other = tmp.resolve("other").toString();
        store.enqueue("默认中文", "", "old", other);
        store.enqueue("默认英文", "", "new", project());
        var newer = store.pendingBatch(project());
        store.applyBatch(output(List.of(operation(newer.get(0).id(), "英文", "英文"))), newer, project());
        UserProfileStore loaded = store();
        var older = loaded.pendingBatch(other);
        loaded.applyBatch(output(List.of(operation(older.get(0).id(), "中文", "中文"))), older, other);
        assertEquals("英文", store().entries().get(0).value());
        assertEquals("new", store().entries().get(0).sourceSession());
        assertTrue(MAPPER.readTree(Files.readString(tmp.resolve("profile.json"))).path("processedAt").isEmpty());
    }

    @Test void automaticDeletionCannotBeUndoneByOlderCandidatesFromAnotherProject() throws Exception {
        UserProfileStore store = store();
        String other = tmp.resolve("other").toString();
        store.enqueue("默认中文", "", "old", other);
        store.enqueue("忘记语言偏好", "", "new", project());
        var deletion = store.pendingBatch(project());
        store.applyBatch(output(List.of(Map.of("message_id", deletion.get(0).id(), "action", "delete",
                "category", "preference", "key", "response.language", "scope", "global", "evidence", "忘记语言偏好"))),
                deletion, project());
        UserProfileStore loaded = store();
        var older = loaded.pendingBatch(other);
        loaded.applyBatch(output(List.of(operation(older.get(0).id(), "中文", "中文"))), older, other);
        assertTrue(store().entries().isEmpty());
        assertEquals(0, store().pendingCount(other));
    }
}
