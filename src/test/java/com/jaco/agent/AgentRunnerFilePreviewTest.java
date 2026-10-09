package com.jaco.agent;

import com.jaco.config.ProviderConfig;
import com.jaco.hook.HookChain;
import com.jaco.llm.ChatRequest;
import com.jaco.llm.ChatStream;
import com.jaco.llm.OpenAiCompatClient;
import com.jaco.llm.StreamChunk;
import com.jaco.llm.TestStreams;
import com.jaco.session.SessionStore;
import com.jaco.tool.PermissionHook;
import com.jaco.tool.ToolRegistry;
import com.jaco.tool.ToolSandbox;
import com.jaco.tool.builtin.EditFileTool;
import com.jaco.tool.builtin.WriteFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerFilePreviewTest {
    @TempDir
    Path tmp;

    private AgentRunner runner(String tool) throws Exception {
        return runner(tool, null);
    }

    private AgentRunner runner(String tool, String expectedVersion) throws Exception {
        OpenAiCompatClient client = new OpenAiCompatClient("http://localhost", "k") {
            int requestIndex;
            @Override
            public ChatStream chatStream(ChatRequest request) throws java.io.IOException {
                if (requestIndex++ % 2 == 0) {
                    String version;
                    try {
                        version = "sha256:" + java.util.HexFormat.of().formatHex(
                                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(tmp.resolve("code.txt"))));
                    } catch (java.security.NoSuchAlgorithmException e) {
                        throw new AssertionError(e);
                    }
                    if (expectedVersion != null) {
                        version = expectedVersion;
                    }
                    String args = tool.equals("edit_file")
                            ? "{\"path\":\"code.txt\",\"old_text\":\"old\",\"new_text\":\"new\",\"expected_version\":\"" + version + "\"}"
                            : "{\"path\":\"code.txt\",\"content\":\"new\",\"expected_version\":\"" + version + "\"}";
                    return TestStreams.of(new StreamChunk.ToolCallDelta(0, "c" + requestIndex, tool, args),
                            new StreamChunk.Done("tool_calls", null));
                }
                return TestStreams.of(new StreamChunk.Delta("完成"), new StreamChunk.Done("stop", null));
            }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(new WriteFileTool());
        registry.register(new EditFileTool());
        AgentRunner agent = new AgentRunner(client, new ProviderConfig("http://localhost", "k", "m", null),
                null, new SessionStore(tmp.resolve("sessions")), new HookChain(List.of(new PermissionHook())),
                registry, new ToolSandbox(tmp, List.of()), tmp, "auto", 25, 100_000, 8000);
        agent.start();
        return agent;
    }

    private List<TurnEvent> turn(AgentRunner agent, Consumer<TurnEvent.ApprovalRequest> approve) {
        return assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            List<TurnEvent> events = new ArrayList<>();
            TurnHandle handle = agent.runTurn("修改文件");
            try {
                while (true) {
                    TurnEvent event = handle.poll(100);
                    if (event == null) {
                        continue;
                    }
                    events.add(event);
                    if (event instanceof TurnEvent.ApprovalRequest request) {
                        assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.ToolPreview));
                        assertEquals("old", Files.readString(tmp.resolve("code.txt")));
                        approve.accept(request);
                    }
                    if (event instanceof TurnEvent.Done done) {
                        assertNull(done.error());
                        return events;
                    }
                }
            } finally {
                handle.cancel();
            }
        });
    }

    @Test
    void previewPrecedesConfirmationAndApprovedPlanIsAppliedForBothTools() throws Exception {
        for (String tool : List.of("write_file", "edit_file")) {
            Files.writeString(tmp.resolve("code.txt"), "old");
            var events = turn(runner(tool), request -> request.resolver().accept("y"));
            assertEquals("new", Files.readString(tmp.resolve("code.txt")));
            assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.ToolCallEnd end && end.ok()));
        }
    }

    @Test
    void rejectingPreviewLeavesFileUnchanged() throws Exception {
        Files.writeString(tmp.resolve("code.txt"), "old");
        turn(runner("edit_file"), request -> request.resolver().accept("n"));
        assertEquals("old", Files.readString(tmp.resolve("code.txt")));
    }

    @Test
    void editingWhileConfirmationIsPendingRejectsStalePlan() throws Exception {
        Files.writeString(tmp.resolve("code.txt"), "old");
        var events = turn(runner("write_file"), request -> {
            try {
                Files.writeString(tmp.resolve("code.txt"), "user change");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            request.resolver().accept("y");
        });
        assertEquals("user change", Files.readString(tmp.resolve("code.txt")));
        assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.ToolCallEnd end
                && !end.ok() && end.summary().contains("预览后发生变化")));
    }

    @Test
    void sessionAllowedToolsStillEmitPreviewWithoutAnotherConfirmation() throws Exception {
        Files.writeString(tmp.resolve("code.txt"), "old");
        AgentRunner agent = runner("write_file");
        turn(agent, request -> request.resolver().accept("a"));
        var events = turn(agent, request -> fail("会话放行后不应再次确认"));
        assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.ToolPreview));
        assertEquals("new", Files.readString(tmp.resolve("code.txt")));
    }

    @Test
    void staleReadVersionFailsBeforePreviewAndConfirmation() throws Exception {
        Files.writeString(tmp.resolve("code.txt"), "old");
        String read = new com.jaco.tool.builtin.ReadFileTool().execute(
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("path", "code.txt"),
                new com.jaco.tool.ToolContext(tmp, new ToolSandbox(tmp, List.of()), "auto", null, () -> false, null));
        String version = read.split("\n", 2)[0].substring("文件版本: ".length());
        Files.writeString(tmp.resolve("code.txt"), "old plus user change");
        var events = turn(runner("edit_file", version), request -> fail("旧版本不应进入权限确认"));
        assertFalse(events.stream().anyMatch(e -> e instanceof TurnEvent.ToolPreview));
        assertTrue(events.stream().anyMatch(e -> e instanceof TurnEvent.ToolCallEnd end
                && !end.ok() && end.summary().contains("版本不匹配")));
        assertEquals("old plus user change", Files.readString(tmp.resolve("code.txt")));
    }
}
