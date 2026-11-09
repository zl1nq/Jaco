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
        Path file = dir.resolve("skills/review/SKILL.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\nname: review\ndescription: Java review\n" + fields + "---\nFULL_SKILL_RULE");
        var tools = new ToolRegistry(new SkillListTool(), new SkillLoadTool());
        var agent = new AgentRunner(client, new ProviderConfig("http://localhost", "k", "m", null), null,
                new SessionStore(dir.resolve("sessions")), new HookChain(List.of()), tools,
                new ToolSandbox(dir, List.of()), dir, "auto", 10, 100000, 8000);
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
}
