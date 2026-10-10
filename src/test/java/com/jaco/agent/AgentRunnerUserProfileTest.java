package com.jaco.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaco.config.ProviderConfig;
import com.jaco.hook.AgentHook;
import com.jaco.hook.HookChain;
import com.jaco.llm.ChatRequest;
import com.jaco.llm.ChatStream;
import com.jaco.llm.OpenAiCompatClient;
import com.jaco.llm.Message;
import com.jaco.llm.StreamChunk;
import com.jaco.llm.TestStreams;
import com.jaco.llm.Usage;
import com.jaco.memory.ProfileExtractor;
import com.jaco.memory.UserProfileStore;
import com.jaco.session.Session;
import com.jaco.session.SessionStore;
import com.jaco.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerUserProfileTest {
    @TempDir Path tmp;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SET_CHINESE = """
            {"operations":[{"action":"set","category":"preference","key":"response.language",
            "value":"中文","scope":"global","evidence":"以后默认用中文回答"}]}
            """;

    static class FakeClient extends OpenAiCompatClient {
        final List<ChatRequest> requests = new ArrayList<>();
        String extraction = SET_CHINESE;
        boolean failExtraction;
        boolean failChat;
        FakeClient() { super("http://localhost", "k"); }
        @Override public ChatStream chatStream(ChatRequest request) throws IOException {
            requests.add(request);
            if (isExtraction(request)) {
                if (failExtraction) throw new IOException("提取失败");
                var result = MAPPER.readTree(extraction);
                var messages = MAPPER.readTree(request.messages().get(1).content()).path("messages");
                for (var operation : result.path("operations")) {
                    if (operation.has("message_id")) continue;
                    String id = messages.get(0).path("message_id").asText();
                    for (var message : messages) {
                        if (message.path("user_message").asText().contains(operation.path("evidence").asText())) {
                            id = message.path("message_id").asText();
                        }
                    }
                    ((com.fasterxml.jackson.databind.node.ObjectNode) operation).put("message_id", id);
                }
                return TestStreams.of(new StreamChunk.Delta(result.toString()),
                        new StreamChunk.Done("stop", new Usage(5, 2, 7)));
            }
            if (failChat) throw new IOException("聊天失败");
            return TestStreams.of(new StreamChunk.Delta("已完成"),
                    new StreamChunk.Done("stop", new Usage(10, 3, 13)));
        }
    }

    private static boolean isExtraction(ChatRequest request) {
        return request.messages().get(0).content().equals(ProfileExtractor.SYSTEM_PROMPT);
    }

    private UserProfileStore profile() throws IOException {
        return new UserProfileStore(tmp.resolve("user-profile.json"));
    }

    private AgentRunner runner(OpenAiCompatClient client, UserProfileStore profile, AgentHook... hooks) throws Exception {
        AgentRunner agent = new AgentRunner(client,
                new ProviderConfig("http://localhost", "k", "model", null), null,
                new SessionStore(tmp.resolve("sessions")), new HookChain(List.of(hooks)),
                new ToolRegistry(), null, tmp, "auto", 25, 100_000, 8000);
        agent.bindUserProfile(profile);
        agent.start();
        return agent;
    }

    private static List<TurnEvent> turn(AgentRunner agent, String prompt) {
        return await(agent.runTurn(prompt));
    }

    private static List<TurnEvent> await(TurnHandle handle) {
        return assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            List<TurnEvent> events = new ArrayList<>();
            while (true) {
                TurnEvent event = handle.poll(100);
                if (event != null) events.add(event);
                if (event instanceof TurnEvent.Done) return events;
            }
        });
    }

    private static TurnEvent.Done done(List<TurnEvent> events) {
        return (TurnEvent.Done) events.get(events.size() - 1);
    }

    private void addPreference(UserProfileStore store, String key, String value) throws Exception {
        String json = MAPPER.writeValueAsString(Map.of("operations", List.of(Map.of(
                "action", "set", "category", "preference", "key", key,
                "value", value, "scope", "global", "evidence", "明确表达"))));
        store.apply(json, "明确表达", "source", UserProfileStore.projectScope(tmp));
    }

    private static String latestChatSystem(FakeClient client) {
        return client.requests.stream().filter(r -> !isExtraction(r)).toList().getLast().messages().get(0).content();
    }

    @Test void routesRawPromptAndContinuationWithoutAddingRequestsAndResetsForNewTask() throws Exception {
        UserProfileStore profile = profile();
        addPreference(profile, "response.language", "中文");
        addPreference(profile, "git.commit_style", "中文提交习惯");
        addPreference(profile, "code.java.style", "Java专属风格");
        addPreference(profile, "document.format", "文档专属格式");
        FakeClient client = new FakeClient();
        client.extraction = "{\"operations\":[]}";
        AgentRunner agent = runner(client, profile, new AgentHook() {
            @Override public String onUserPromptSubmit(String prompt) { return "hook 改写为修改 README"; }
        });
        turn(agent, "提交代码");
        assertTrue(latestChatSystem(client).contains("中文提交习惯"));
        assertFalse(latestChatSystem(client).contains("文档专属格式"));
        turn(agent, "继续");
        assertTrue(latestChatSystem(client).contains("中文提交习惯"));
        turn(agent, "修改Java代码");
        assertTrue(latestChatSystem(client).contains("Java专属风格"));
        assertFalse(latestChatSystem(client).contains("中文提交习惯"));
        turn(agent, "你好");
        assertFalse(latestChatSystem(client).contains("Java专属风格"));
        assertTrue(latestChatSystem(client).contains("中文"));
        assertEquals(4, client.requests.size()); // 普通任务和纯承接语都没有额外提取调用
    }

    @Test void switchingAndRestartRestoreOnlyTargetSessionTaskAndNewSessionClearsIt() throws Exception {
        UserProfileStore profile = profile();
        addPreference(profile, "git.commit_style", "提交格式");
        addPreference(profile, "code.java.style", "Java专属风格");
        SessionStore sessions = new SessionStore(tmp.resolve("sessions"));
        sessions.save(new Session("s20990101-000000", 10,
                List.of(Message.user("修改Java代码"), Message.assistant("完成"), Message.user("继续"))));
        sessions.save(new Session("s20000101-000000", 1,
                List.of(Message.user("提交代码"), Message.assistant("完成"))));
        FakeClient client = new FakeClient();
        client.extraction = "{\"operations\":[]}";
        AgentRunner agent = runner(client, profile);
        turn(agent, "继续");
        assertTrue(latestChatSystem(client).contains("Java专属风格"));
        assertFalse(latestChatSystem(client).contains("提交格式"));
        assertNull(agent.switchSession("s20000101-000000"));
        turn(agent, "继续");
        assertTrue(latestChatSystem(client).contains("提交格式"));
        assertFalse(latestChatSystem(client).contains("Java专属风格"));
        agent.newSession();
        turn(agent, "继续");
        assertFalse(latestChatSystem(client).contains("提交格式"));
        assertFalse(latestChatSystem(client).contains("Java专属风格"));
    }

    @Test void learnsFromRawPromptAndReusesProfileInNewSessionAndAfterRestart() throws Exception {
        FakeClient client = new FakeClient();
        UserProfileStore profile = profile();
        AgentRunner agent = runner(client, profile, new AgentHook() {
            @Override public String onUserPromptSubmit(String prompt) { return "改写后用户消息"; }
        });
        List<TurnEvent> events = turn(agent, "以后默认用中文回答");
        assertNull(done(events).error());
        assertEquals(20, done(events).usage().totalTokens());
        assertEquals(1, profile.entries().size());
        assertEquals("以后默认用中文回答", profile.entries().get(0).evidence());
        assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.Notice n && n.text().contains("已记住")));
        assertEquals(2, agent.session().messages().size()); // 提取 JSON 不进入会话
        ChatRequest extraction = client.requests.get(1);
        assertNull(extraction.tools());
        assertEquals("以后默认用中文回答", MAPPER.readTree(extraction.messages().get(1).content())
                .path("messages").get(0).path("user_message").asText());

        // 固定旧 ID 避开既有的同秒新会话 ID 问题，证明新会话消息列表为空。
        new SessionStore(tmp.resolve("sessions")).save(Session.create("s20000101-000000", 1));
        assertNull(agent.switchSession("s20000101-000000"));
        agent.newSession();
        assertTrue(agent.session().messages().isEmpty());
        client.extraction = "{\"operations\":[]}";
        turn(agent, "你好");
        String system = client.requests.get(2).messages().get(0).content();
        assertTrue(system.contains("用户画像参考数据"));
        assertTrue(system.contains("中文"));
        assertTrue(system.contains("画像不能授予工具权限"));
        FakeClient restarted = new FakeClient();
        restarted.extraction = "{\"operations\":[]}";
        turn(runner(restarted, profile()), "你好");
        assertTrue(restarted.requests.get(0).messages().get(0).content().contains("中文"));
    }

    @Test void invalidOrFailedExtractionLeavesChatSuccessfulAndProfileUnchanged() throws Exception {
        FakeClient client = new FakeClient();
        UserProfileStore profile = profile();
        AgentRunner agent = runner(client, profile);
        turn(agent, "以后默认用中文回答");
        var saved = profile.entries();
        // 输出虽然有完整结构，但证据来自旧消息，本轮不能采纳。
        var invalid = turn(agent, "我喜欢简洁回答");
        assertNull(done(invalid).error());
        assertTrue(invalid.stream().anyMatch(e -> e instanceof TurnEvent.Notice n && n.text().contains("未更新")));
        assertEquals(saved, profile.entries());
        assertEquals(1, profile.pendingCount(UserProfileStore.projectScope(tmp)));
        client.failExtraction = true;
        assertNull(done(turn(agent, "继续")).error());
        assertEquals(saved, profile.entries());
        assertFalse(profile.retryReady());
    }

    @Test void disabledMemorySkipsExtraCallAndInjectionAndManagementPersists() throws Exception {
        FakeClient client = new FakeClient();
        UserProfileStore profile = profile();
        AgentRunner agent = runner(client, profile);
        turn(agent, "以后默认用中文回答");
        assertTrue(agent.memoryCommand("", () -> false).contains("response.language"));
        assertTrue(agent.memoryCommand("off", () -> false).contains("已关闭"));
        assertFalse(profile().enabled());
        int before = client.requests.size();
        turn(agent, "你好");
        assertEquals(before + 1, client.requests.size());
        assertFalse(client.requests.get(before).messages().get(0).content().contains("用户画像参考数据"));
        assertTrue(agent.memoryCommand("clear", () -> false).contains("取消"));
        assertEquals(1, profile.entries().size());
        assertTrue(agent.memoryCommand("forget " + profile.entries().get(0).id(), () -> false).contains("已删除"));
        assertTrue(profile().entries().isEmpty());
        agent.memoryCommand("on", () -> false);
        assertTrue(profile().enabled());
        turn(agent, "以后默认用中文回答");
        assertTrue(agent.memoryCommand("clear", () -> true).contains("已清空"));
        assertTrue(profile().entries().isEmpty());
    }

    @Test void blockedOrFailedTurnDoesNotExtract() throws Exception {
        FakeClient blocked = new FakeClient();
        AgentRunner agent = runner(blocked, profile(), new AgentHook() {
            @Override public String onUserPromptSubmit(String prompt) { return null; }
        });
        assertTrue(done(turn(agent, "以后默认用中文回答")).abortedByHook());
        assertTrue(blocked.requests.isEmpty());
        FakeClient failed = new FakeClient();
        failed.failChat = true;
        assertNotNull(done(turn(runner(failed, profile()), "以后默认用中文回答")).error());
        assertEquals(1, failed.requests.size());
        assertTrue(profile().entries().isEmpty());
    }

    @Test void extractionUsesCurrentRawMessageAndPreviousAssistantButNotOlderConversation() throws Exception {
        FakeClient client = new FakeClient();
        AgentRunner agent = runner(client, profile());
        client.extraction = "{\"operations\":[]}";
        turn(agent, "我在引用别人说：我是医生");
        turn(agent, "以后默认用中文回答");
        var input = MAPPER.readTree(client.requests.get(3).messages().get(1).content());
        assertEquals("以后默认用中文回答", input.path("messages").get(0).path("user_message").asText());
        assertEquals("已完成", input.path("messages").get(0).path("previous_assistant").asText());
        assertFalse(input.toString().contains("我是医生"));
        assertTrue(profile().entries().isEmpty());
    }

    @Test void cancellingExtractionClosesStreamAndNeverWritesPartialOutput() throws Exception {
        CountDownLatch extracting = new CountDownLatch(1);
        ChatStream stalled = TestStreams.of(new StreamChunk.Delta(SET_CHINESE));
        OpenAiCompatClient client = new OpenAiCompatClient("http://localhost", "k") {
            @Override public ChatStream chatStream(ChatRequest request) {
                if (isExtraction(request)) {
                    extracting.countDown();
                    return stalled;
                }
                return TestStreams.of(new StreamChunk.Delta("完成"), new StreamChunk.Done("stop", null));
            }
        };
        UserProfileStore profile = profile();
        AgentRunner agent = runner(client, profile);
        TurnHandle handle = agent.runTurn("以后默认用中文回答");
        assertTrue(extracting.await(5, TimeUnit.SECONDS));
        handle.cancel();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (!stalled.isCancelled()) Thread.sleep(10);
        });
        assertTrue(profile.entries().isEmpty());
        assertTrue(profile().entries().isEmpty());
        assertEquals(1, profile().pendingCount(UserProfileStore.projectScope(tmp)));
    }

    @Test void truncatedExtractionIsNotApplied() throws Exception {
        OpenAiCompatClient client = new OpenAiCompatClient("http://localhost", "k") {
            @Override public ChatStream chatStream(ChatRequest request) {
                return isExtraction(request)
                        ? TestStreams.of(new StreamChunk.Delta(SET_CHINESE), new StreamChunk.Done("length", null))
                        : TestStreams.of(new StreamChunk.Delta("完成"), new StreamChunk.Done("stop", null));
            }
        };
        UserProfileStore profile = profile();
        assertNull(done(turn(runner(client, profile), "以后默认用中文回答")).error());
        assertTrue(profile.entries().isEmpty());
    }

    @Test void twentyOrdinaryTurnsNeedOnlyTwoExtraCallsAndConfirmationsDoNotCount() throws Exception {
        FakeClient client = new FakeClient();
        client.extraction = "{\"operations\":[]}";
        UserProfileStore profile = profile();
        AgentRunner agent = runner(client, profile);
        for (int i = 0; i < 9; i++) turn(agent, "解释第 " + i + " 个问题");
        assertEquals(9, client.requests.size());
        for (String confirmation : List.of("继续", "好的", "ok", "谢谢")) turn(agent, confirmation);
        assertEquals(9, profile.pendingCount(UserProfileStore.projectScope(tmp)));
        assertEquals(13, client.requests.size());
        turn(agent, "解释第 9 个问题");
        assertEquals(15, client.requests.size());
        assertEquals(0, profile.pendingCount(UserProfileStore.projectScope(tmp)));
        for (int i = 10; i < 20; i++) turn(agent, "解释第 " + i + " 个问题");
        assertEquals(2, client.requests.stream().filter(AgentRunnerUserProfileTest::isExtraction).count());
        assertEquals(0, profile().pendingCount(UserProfileStore.projectScope(tmp)));
        var extraction = client.requests.stream().filter(AgentRunnerUserProfileTest::isExtraction).toList().get(0);
        assertEquals(10, MAPPER.readTree(extraction.messages().get(1).content()).path("messages").size());
    }

    @Test void explicitPreferenceImmediatelyProcessesEarlierMessagesWithCorrectEvidenceSource() throws Exception {
        FakeClient client = new FakeClient();
        UserProfileStore profile = profile();
        AgentRunner agent = runner(client, profile);
        for (int i = 0; i < 4; i++) turn(agent, "解释第 " + i + " 个问题");
        String session = agent.session().id();
        assertNull(done(turn(agent, "以后默认用中文回答")).error());
        assertEquals(6, client.requests.size());
        assertEquals(1, profile.entries().size());
        assertEquals("以后默认用中文回答", profile.entries().get(0).evidence());
        assertEquals(session, profile.entries().get(0).sourceSession());
        assertEquals(0, profile.pendingCount(UserProfileStore.projectScope(tmp)));
    }

    @Test void recoveredQueueCanFlushAtBoundaryAndSuccessfulBatchIsNotReplayed() throws Exception {
        FakeClient initial = new FakeClient();
        initial.extraction = "{\"operations\":[]}";
        UserProfileStore profile = profile();
        AgentRunner agent = runner(initial, profile);
        turn(agent, "解释一下迭代器");
        turn(agent, "再解释一下生成器");
        assertEquals(2, initial.requests.size());
        FakeClient restarted = new FakeClient();
        restarted.extraction = "{\"operations\":[]}";
        UserProfileStore loaded = profile();
        AgentRunner recovered = runner(restarted, loaded);
        assertTrue(recovered.hasPendingUserProfile());
        assertNull(done(await(recovered.flushUserProfile())).error());
        assertEquals(1, restarted.requests.size());
        assertEquals(2, MAPPER.readTree(restarted.requests.get(0).messages().get(1).content()).path("messages").size());
        assertFalse(recovered.hasPendingUserProfile());
        await(recovered.flushUserProfile());
        assertEquals(1, restarted.requests.size());
        assertEquals(0, profile().pendingCount(UserProfileStore.projectScope(tmp)));
    }

    @Test void failedBatchIsRetainedAndNotRetriedEveryTurnOrAtExitDuringCooldown() throws Exception {
        FakeClient client = new FakeClient();
        client.failExtraction = true;
        UserProfileStore profile = profile();
        AgentRunner agent = runner(client, profile);
        for (int i = 0; i < 10; i++) assertNull(done(turn(agent, "解释第 " + i + " 个问题")).error());
        assertEquals(11, client.requests.size());
        assertEquals(10, profile.pendingCount(UserProfileStore.projectScope(tmp)));
        assertFalse(profile().retryReady());
        for (int i = 10; i < 13; i++) turn(agent, "解释第 " + i + " 个问题");
        turn(agent, "以后默认用中文回答");
        await(agent.flushUserProfile());
        assertEquals(15, client.requests.size());
        assertEquals(14, profile().pendingCount(UserProfileStore.projectScope(tmp)));
        assertTrue(profile.entries().isEmpty());
    }

    @Test void manualOffAndClearDiscardCandidatesAndNeverReplayOldMessages() throws Exception {
        FakeClient client = new FakeClient();
        client.extraction = "{\"operations\":[]}";
        UserProfileStore profile = profile();
        AgentRunner agent = runner(client, profile);
        turn(agent, "解释一下代码");
        assertEquals(1, profile.pendingCount(UserProfileStore.projectScope(tmp)));
        agent.memoryCommand("off", () -> false);
        assertEquals(0, profile().pendingCount(UserProfileStore.projectScope(tmp)));
        agent.memoryCommand("on", () -> false);
        await(agent.flushUserProfile());
        assertEquals(1, client.requests.size());
        turn(agent, "解释一下递归");
        agent.memoryCommand("clear", () -> true);
        assertEquals(0, profile().pendingCount(UserProfileStore.projectScope(tmp)));
        await(agent.flushUserProfile());
        assertEquals(2, client.requests.size());
    }

    @Test void invalidSwitchDoesNotRequestBoundaryFlushButValidDifferentSessionDoes() throws Exception {
        FakeClient client = new FakeClient();
        AgentRunner agent = runner(client, profile());
        new SessionStore(tmp.resolve("sessions")).save(Session.create("s20000101-000000", 1));
        assertFalse(agent.profileFlushBeforeSwitch("missing"));
        assertFalse(agent.profileFlushBeforeSwitch(agent.session().id()));
        assertTrue(agent.profileFlushBeforeSwitch("s20000101"));
        assertTrue(client.requests.isEmpty());
    }
}
