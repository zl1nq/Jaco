package com.jaco.agent;

import com.jaco.config.ProviderConfig;
import com.jaco.hook.HookChain;
import com.jaco.llm.*;
import com.jaco.session.SessionStore;
import com.jaco.skill.SkillRegistry;
import com.jaco.tool.*;
import com.jaco.tool.builtin.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerSkillTest {
    @TempDir Path dir;
    private AgentRunner agent(OpenAiCompatClient client, String fields) throws Exception {
        return agent(client, fields, 100000);
    }
    private AgentRunner agent(OpenAiCompatClient client, String fields, long contextLimit, Tool... extra) throws Exception {
        Path file = dir.resolve("skills/review/SKILL.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\nname: review\ndescription: Java review\n" + fields + "---\nFULL_SKILL_RULE");
        var tools = new ToolRegistry(new SkillListTool(), new SkillLoadTool(), new SkillReadTool());
        for (Tool tool : extra) tools.register(tool);
        var agent = new AgentRunner(client, new ProviderConfig("http://localhost", "k", "m", null), null,
                new SessionStore(dir.resolve("sessions")), new HookChain(List.of()), tools,
                new ToolSandbox(dir, List.of()), dir, "auto", 10, contextLimit, 8000);
        agent.bindSkills(new SkillRegistry(null, dir.resolve("skills")));
        agent.start();
        return agent;
    }
    private void finish(TurnHandle handle) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (handle) {
                while (true) if (handle.poll(100) instanceof TurnEvent.Done d) { assertNull(d.error()); break; }
            }
        });
    }
    @Test void modelLoadSurvivesToolBatchesAndExpiresAtNextTurn() throws Exception {
        var requests = new ArrayList<ChatRequest>();
        var client = new OpenAiCompatClient("http://localhost", "k") {
            public ChatStream chatStream(ChatRequest request) {
                requests.add(request);
                int i = requests.size();
                if (i <= 2) return TestStreams.of(new StreamChunk.ToolCallDelta(0, "s" + i,
                        i == 1 ? "skill_load" : "skill_list", i == 1 ? "{\"name\":\"review\"}" : "{}"), new StreamChunk.Done("tool_calls", null));
                return TestStreams.of(new StreamChunk.Delta("完成"), new StreamChunk.Done("stop", null));
            }
        };
        try (var agent = agent(client, "")) {
            finish(agent.runTurn("代码审查"));
            assertFalse(requests.get(0).messages().getFirst().content().contains("FULL_SKILL_RULE"));
            assertTrue(requests.get(1).messages().getFirst().content().contains("FULL_SKILL_RULE"));
            assertTrue(requests.get(2).messages().getFirst().content().contains("FULL_SKILL_RULE"));
            assertEquals("代码审查", agent.session().title());
            finish(agent.runTurn("另一个任务"));
            assertFalse(requests.getLast().messages().getFirst().content().contains("FULL_SKILL_RULE"));
        }
    }
    @Test void explicitInvocationLoadsBeforeRequestAndInvalidInvocationSendsNothing() throws Exception {
        var requests = new ArrayList<ChatRequest>();
        var client = new OpenAiCompatClient("http://localhost", "k") {
            public ChatStream chatStream(ChatRequest request) {
                requests.add(request);
                return TestStreams.of(new StreamChunk.Delta("完成"), new StreamChunk.Done("stop", null));
            }
        };
        try (var agent = agent(client, "disable-model-invocation: true\n")) {
            assertThrows(IllegalArgumentException.class, () -> agent.runSkillTurn("absent", ""));
            assertTrue(requests.isEmpty());
            assertTrue(agent.session().messages().isEmpty());
            finish(agent.runSkillTurn("review", "审查入口"));
            assertTrue(requests.getFirst().messages().getFirst().content().contains("FULL_SKILL_RULE"));
            assertTrue(requests.getFirst().messages().getLast().content().contains("审查入口"));
        }
    }
    @Test void compactionKeepsFullSkillAndRestartDoesNotReactivateIt() throws Exception {
        var compacted = new java.util.concurrent.atomic.AtomicBoolean();
        var rounds = new java.util.concurrent.atomic.AtomicInteger();
        var client = new OpenAiCompatClient("http://localhost", "k") {
            public ChatStream chatStream(ChatRequest request) {
                if (request.tools() == null) return TestStreams.of(new StreamChunk.Delta("读取进展"), new StreamChunk.Done("stop", null));
                assertTrue(request.messages().getFirst().content().contains("FULL_SKILL_RULE"));
                if (request.messages().stream().anyMatch(m -> m.content() != null && m.content().startsWith("[迭代摘要"))) compacted.set(true);
                int i = rounds.getAndIncrement();
                return i < 6 ? TestStreams.of(new StreamChunk.ToolCallDelta(0, "read" + i, "read_file", "{\"path\":\"large.txt\"}"),
                        new StreamChunk.Done("tool_calls", null)) : TestStreams.of(new StreamChunk.Delta("完成"), new StreamChunk.Done("stop", null));
            }
        };
        String original;
        try (var agent = agent(client, "", 8000, new ReadFileTool())) {
            Files.writeString(dir.resolve("large.txt"), "x".repeat(6500));
            original = agent.session().id();
            finish(agent.runSkillTurn("review", "读取文件并分析"));
            assertTrue(compacted.get());
            assertTrue(agent.session().title().contains("review"));
        }
        var restarted = new OpenAiCompatClient("http://localhost", "k") {
            public ChatStream chatStream(ChatRequest request) {
                assertFalse(request.messages().getFirst().content().contains("FULL_SKILL_RULE"));
                return TestStreams.of(new StreamChunk.Delta("完成"), new StreamChunk.Done("stop", null));
            }
        };
        try (var agent = agent(restarted, "")) {
            assertEquals(original, agent.session().id());
            finish(agent.runTurn("新的任务"));
        }
    }
    @Test void switchedSessionInvalidatesOldServiceAndKeepsNewSessionEmpty() throws Exception {
        var captured = new java.util.concurrent.atomic.AtomicReference<com.jaco.skill.SkillService>();
        Tool capture = new Tool() {
            public String name() { return "capture"; }
            public String description() { return "capture"; }
            public com.fasterxml.jackson.databind.JsonNode schema() { return JsonSchema.object().build(); }
            public String summary(com.fasterxml.jackson.databind.JsonNode args) { return "capture"; }
            public ToolResult execute(com.fasterxml.jackson.databind.JsonNode args, ToolContext ctx) {
                captured.set(ctx.skills()); return ToolResult.success("captured");
            }
        };
        var blocked = new java.util.concurrent.CountDownLatch(1);
        var worker = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var client = new OpenAiCompatClient("http://localhost", "k") {
            int calls;
            public ChatStream chatStream(ChatRequest request) {
                if (calls++ == 0) return TestStreams.of(new StreamChunk.ToolCallDelta(0, "capture", "capture", "{}"), new StreamChunk.Done("tool_calls", null));
                worker.set(Thread.currentThread());
                blocked.countDown();
                return TestStreams.of();
            }
        };
        try (var agent = agent(client, "", 100000, capture); var handle = agent.runSkillTurn("review", "任务")) {
            assertTrue(blocked.await(3, java.util.concurrent.TimeUnit.SECONDS));
            agent.newSession();
            assertThrows(ToolException.class, () -> captured.get().load("review", false));
            assertThrows(ToolException.class, captured.get()::context);
            assertTrue(agent.session().messages().isEmpty());
            handle.cancel();
            worker.get().join(3000);
            assertFalse(worker.get().isAlive());
        }
    }
    @Test void explicitArchiveFailureNeverRequestsModel() throws Exception {
        try (var agent = agent(null, "")) {
            Files.createDirectory(dir.resolve("sessions").resolve(agent.session().id() + ".archive.jsonl"));
            assertThrows(java.io.UncheckedIOException.class, () -> agent.runSkillTurn("review", "task"));
            assertTrue(agent.session().messages().isEmpty());
            assertTrue(agent.skillsCommand("").contains("review"));
        }
    }
}
