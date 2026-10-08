package com.jaco.agent;

import com.jaco.llm.Message;
import com.jaco.llm.Role;
import com.jaco.llm.ToolCall;
import com.jaco.session.Session;
import com.jaco.session.SessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContextCompactorTest {

    @TempDir
    Path tmp;

    private static ToolCall toolCall(String id) {
        return new ToolCall(id, "function",
                new ToolCall.FunctionCall("grep", "{\"x\":\"" + "a".repeat(152) + "\"}"));
    }

    /** 一个带工具调用的完整轮：user → assistant(tool_calls) → tool result。 */
    private static List<Message> toolTurn(String userId, String callId, String resultText) {
        return List.of(
                Message.user(userId),
                new Message(Role.ASSISTANT, null, List.of(toolCall(callId)), null, null),
                Message.toolResult(callId, resultText));
    }

    private static String plainTurn(String userText) {
        return userText;
    }

    private ContextCompactor compactor(Session session, long contextLimit) throws Exception {
        return new ContextCompactor(new SessionStore(tmp), session, contextLimit);
    }

    @Test
    void belowTriggerDoesNothing() throws Exception {
        Session session = Session.create("s0", 0);
        session.messages().addAll(List.of(Message.user("hello world")));
        var outcome = compactor(session, 100_000).compactIfNeeded(
                session.messages(), 0, n -> { }, null);
        assertFalse(outcome.compacted());
    }

    @Test
    void step1PlaceholderKeepsToolNameSizeAndFirstLine() throws Exception {
        Session session = Session.create("s1", 0);
        // contextLimit=1000：触发 700、目标 400。2 个旧工具轮（各约 493 tok）+ 最近 2 轮（约 157 tok）
        String resultText = "FIRST_LINE_MARKER\n" + "r".repeat(1582); // 共 1600 字符
        session.messages().addAll(toolTurn("u".repeat(160), "call-1", resultText));
        session.messages().addAll(toolTurn("u".repeat(160), "call-2", resultText));
        session.messages().add(Message.user("u".repeat(160)));
        session.messages().addAll(toolTurn("u".repeat(160), "call-3", "RECENT_KEEP"));

        var outcome = compactor(session, 1000).compactIfNeeded(
                session.messages(), 0, n -> { }, null);

        assertTrue(outcome.compacted());
        String joined = render(session.messages());
        // 占位含工具名、体量、首行
        assertTrue(joined.contains("(grep 结果已省略：原 "));
        assertTrue(joined.contains("1600 字符；首行: FIRST_LINE_MARKER"));
        // 最近轮不受①影响
        assertTrue(joined.contains("RECENT_KEEP"));
        // ① 已达标，被替换的原文仍必须归档；最近轮不归档。
        SessionStore store = new SessionStore(tmp);
        String archived = Files.readString(store.archivePath(session));
        assertEquals(2, archived.lines().count());
        assertTrue(archived.contains("r".repeat(1582)));
        assertFalse(archived.contains("RECENT_KEEP"));
        assertEquals(java.util.Set.of("call-1", "call-2"), session.compactedToolCallIds());

        // 保存并恢复后，显式状态仍生效，重复压缩不再次归档或改写占位。
        store.save(session);
        Session loaded = store.load(session.id()).orElseThrow();
        compactor(loaded, 1000).forceCompact(loaded.messages(), n -> { }, null);
        assertEquals(joined, render(loaded.messages()));
        assertEquals(archived, Files.readString(store.archivePath(loaded)));

        // 后续进入②时，不再次归档已保存的工具结果或占位。
        compactor(loaded, 400).forceCompact(loaded.messages(), n -> { }, null);
        String laterArchive = Files.readString(store.archivePath(loaded));
        assertEquals(2, laterArchive.lines().filter(line -> line.contains("FIRST_LINE_MARKER")).count());
        assertFalse(laterArchive.contains("结果已省略"));
        assertTrue(loaded.compactedToolCallIds().isEmpty());
    }

    @Test
    void step2ArchivesOriginalContentAndLeavesSkeletonMarker() throws Exception {
        Session session = Session.create("s2", 0);
        // 8 个 args 重的旧轮：① 替换 result 后仍超目标 → ② 归档原始内容 + 骨架标记
        String resultText = "ORIGINAL_RESULT_" + "r".repeat(224); // 共 240 字符
        for (int i = 0; i < 8; i++) {
            session.messages().addAll(toolTurn("u".repeat(160), "call-" + i, resultText));
        }
        session.messages().add(Message.user("u".repeat(160)));
        session.messages().add(Message.user("FINAL_USER_TEXT"));

        var outcome = compactor(session, 1000).compactIfNeeded(
                session.messages(), 0, n -> { }, null);

        assertTrue(outcome.compacted());
        String joined = render(session.messages());

        // 发送视图：原始 result 不在；走到②后连①的占位也被骨架标记整体替换
        assertFalse(joined.contains("ORIGINAL_RESULT_"));
        assertFalse(joined.contains("结果已省略"));

        // 骨架标记：轮数 + 每轮 user/assistant 首句线索
        assertTrue(joined.contains("[早前 8 轮对话已压缩归档，可用 recall 工具检索："));
        assertTrue(joined.contains("· 用户: " + "u".repeat(40)));

        // 归档保真：原始 result 与 args 都在归档文件里
        Path archive = new SessionStore(tmp).archivePath(session);
        assertTrue(Files.exists(archive));
        String archived = Files.readString(archive);
        assertTrue(archived.contains("ORIGINAL_RESULT_"));
        assertTrue(archived.contains("a".repeat(40)));
        assertEquals(24, archived.lines().count()); // 8 轮 × 3 条，无①重复归档。
        assertTrue(session.compactedToolCallIds().isEmpty());

        // 最近轮完整保留
        assertTrue(joined.contains("FINAL_USER_TEXT"));
    }

    @Test
    void archiveFailurePreservesOriginalMessagesInBothStages() throws Exception {
        SessionStore store = new SessionStore(tmp);
        for (long limit : List.of(1000L, 400L)) {
            Session session = Session.create("sfail" + limit, 0);
            session.messages().addAll(toolTurn("u".repeat(160), "call-1", "x".repeat(1600)));
            session.messages().addAll(toolTurn("u".repeat(160), "call-2", "x".repeat(1600)));
            session.messages().add(Message.user("u".repeat(160)));
            session.messages().add(Message.user("u".repeat(160)));
            List<Message> original = List.copyOf(session.messages());
            // 用目录占据归档路径，确定性模拟写入失败。
            Files.createDirectory(store.archivePath(session));

            assertThrows(UncheckedIOException.class, () ->
                    compactor(session, limit).forceCompact(session.messages(), n -> { }, null));

            assertEquals(original, session.messages());
            assertTrue(session.compactedToolCallIds().isEmpty());
        }
    }

    @Test
    void placeholderLikeToolOutputIsStillArchivedAndCallPairsStayIntact() throws Exception {
        Session session = Session.create("sliteral", 0);
        String output = "(grep 结果已省略：这是文件中的普通文本)\n" + "x".repeat(1600);
        session.messages().addAll(toolTurn("old", "literal-call", output));
        List<Message> recent = List.of(Message.user("previous"), Message.assistant("reply"), Message.user("current"));
        session.messages().addAll(recent);

        compactor(session, 1000).forceCompact(session.messages(), n -> { }, null);

        assertTrue(Files.readString(new SessionStore(tmp).archivePath(session)).contains("x".repeat(1600)));
        assertEquals("literal-call", session.messages().get(1).toolCalls().get(0).id());
        assertEquals("literal-call", session.messages().get(2).toolCallId());
        assertEquals(recent, session.messages().subList(3, session.messages().size()));
        // 会话元数据不会进入模型的 Message JSON。
        assertNull(new com.fasterxml.jackson.databind.ObjectMapper()
                .valueToTree(session.messages().get(2)).get("compactedToolCallIds"));
    }

    @Test
    void skeletonMarkerSkipsInjectedMarkersAndTruncates() throws Exception {
        Session session = Session.create("s3", 0);
        // 旧轮里夹着上一轮压缩注入的标记行，骨架里不应复读
        session.messages().addAll(List.of(
                Message.user("[早前 3 条消息已压缩归档，关键内容可用 recall 工具检索]"),
                Message.user("u".repeat(160)),
                new Message(Role.ASSISTANT, "ASSISTANT_REPLY_" + "x".repeat(200), null, null, null)));
        for (int i = 0; i < 9; i++) {
            session.messages().addAll(toolTurn("u".repeat(160), "call-" + i, "r".repeat(240)));
        }
        session.messages().add(Message.user("FINAL"));

        compactor(session, 1000).compactIfNeeded(session.messages(), 0, n -> { }, null);

        String joined = render(session.messages());
        assertTrue(joined.contains("ASSISTANT_REPLY_"));
        assertTrue(joined.contains("ASSISTANT_REPLY_" + "x".repeat(50)));
        assertFalse(joined.contains("关键内容可用 recall 工具检索用户"));
    }

    private static String render(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : new ArrayList<>(messages)) {
            sb.append(m.role()).append(':').append(m.content() == null ? "" : m.content()).append('\n');
        }
        return sb.toString();
    }
}
