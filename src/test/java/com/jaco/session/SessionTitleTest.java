package com.jaco.session;

import com.jaco.llm.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SessionTitleTest {

    @TempDir
    Path tmp;

    @Test
    void titleSurvivesHistoryReplacementAndReload() throws Exception {
        Session session = Session.create("s1", 1).withFirstPromptTitle("修复 recall 分页读取");
        session.messages().add(Message.user("修复 recall 分页读取"));
        session.messages().clear();
        session.messages().add(Message.user("[历史摘要] 已完成修改"));
        session.messages().add(Message.user("另一个问题"));
        session = session.withFirstPromptTitle("另一个问题");
        SessionStore store = new SessionStore(tmp);
        store.save(session);

        assertEquals("修复 recall 分页读取", store.load("s1").orElseThrow().title());
        assertEquals("修复 recall 分页读取", store.listSessions().get(0).displayTitle());
        assertEquals("修复 recall 分页读取", store.loadLatestOrNew().displayTitle());
    }

    @Test
    void legacySessionUsesFirstRemainingUserMessageAndSkipsInjectedMarkers() throws Exception {
        Session legacy = new Session("legacy", 1, List.of(
                Message.assistant("回复"), Message.user("  "),
                Message.user("[早前 3 轮对话已压缩归档]"), Message.user("[历史摘要] 摘要"),
                Message.user("[onStop hook] 请继续"), Message.user("  排查\n配置\t加载  "),
                Message.user("后续输入")));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var json = mapper.valueToTree(legacy);
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("title");
        Files.writeString(tmp.resolve("legacy.json"), mapper.writeValueAsString(json));

        assertEquals("排查 配置 加载", new SessionStore(tmp).load("legacy").orElseThrow().displayTitle());
    }

    @Test
    void longTitleUsesFortyCodePointsAndCleansControlCharacters() {
        String input = "中文😀".repeat(20);
        Session session = Session.create("s1", 1).withFirstPromptTitle(input);
        assertEquals(input.substring(0, input.offsetByCodePoints(0, 40)) + "…", session.title());
        assertEquals(41, session.title().codePointCount(0, session.title().length()));
        assertFalse(Character.isHighSurrogate(session.title().charAt(session.title().length() - 2)));
        assertEquals("检查 文件", Session.create("s2", 2)
                .withFirstPromptTitle("\n检查\t\u001b 文件\r\n").title());
    }

    @Test
    void emptySessionCanReceiveTitleLater() {
        Session session = Session.create("s1", 1).withFirstPromptTitle(" \n\t ");
        assertEquals("未命名会话", session.displayTitle());
        assertEquals("检查代码", session.withFirstPromptTitle("检查代码").displayTitle());
    }
}
