package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaco.tool.ToolContext;
import com.jaco.agent.subagent.SubagentProfile;
import com.jaco.agent.subagent.SubagentRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolContext ctx() {
        return new ToolContext(Path.of("."), null, "auto", new AtomicReference<>(), () -> false, null);
    }

    @Test
    void unboundHandlerFailsGracefully() throws Exception {
        var args = MAPPER.createObjectNode();
        args.put("prompt", "调研一下");
        String out = new TaskTool().execute(args, ctx());
        assertEquals("ERROR: 子 agent 未接线", out);
    }

    @Test
    void boundHandlerReceivesPromptAndReturnsReport() throws Exception {
        TaskTool tool = new TaskTool();
        AtomicReference<String> received = new AtomicReference<>();
        tool.bind((request, cancelled) -> {
            received.set(request.prompt());
            return "报告内容";
        });
        var args = MAPPER.createObjectNode();
        args.put("prompt", "调研一下");
        assertEquals("报告内容", tool.execute(args, ctx()));
        assertEquals("调研一下", received.get());
    }

    @Test
    void profileAndToolSubsetAreValidatedAndForwardedAsImmutableRequest() throws Exception {
        TaskTool tool = new TaskTool();
        AtomicReference<SubagentRequest> received = new AtomicReference<>();
        tool.bind((request, cancelled) -> { received.set(request); return "报告"; });
        assertEquals("报告", tool.execute(MAPPER.readTree("""
                {"prompt":" 调研一下 ","profile":"explore","tools":["grep","read_file","grep"]}
                """), ctx()));
        assertEquals("调研一下", received.get().prompt());
        assertEquals(SubagentProfile.EXPLORE, received.get().profile());
        assertEquals(Set.of("grep", "read_file"), received.get().tools());
        assertThrows(UnsupportedOperationException.class, () -> received.get().tools().add("write_file"));
    }

    @Test
    void omittedToolsUseDefaultsAndExplicitEmptyArrayDisablesTools() throws Exception {
        TaskTool tool = new TaskTool();
        AtomicReference<SubagentRequest> received = new AtomicReference<>();
        tool.bind((request, cancelled) -> { received.set(request); return "报告"; });
        tool.execute(MAPPER.readTree("{\"prompt\":\"调查\"}"), ctx());
        assertEquals(SubagentProfile.EXPLORE, received.get().profile());
        assertEquals(Set.of("read_file", "list_dir", "grep", "find_files", "recall", "git_status", "git_diff"), received.get().tools());
        tool.execute(MAPPER.readTree("{\"prompt\":\"调查\",\"tools\":[]}"), ctx());
        assertTrue(received.get().tools().isEmpty());
    }

    @Test
    void invalidSelectionsNeverReachHandler() throws Exception {
        TaskTool tool = new TaskTool();
        AtomicInteger calls = new AtomicInteger();
        tool.bind((request, cancelled) -> { calls.incrementAndGet(); return "不应执行"; });
        for (String fields : List.of(
                "\"profile\":\"review\"", "\"profile\":\"\"", "\"profile\":null", "\"profile\":3",
                "\"tools\":null", "\"tools\":\"grep\"", "\"tools\":{}", "\"tools\":[null]",
                "\"tools\":[3]", "\"tools\":[\" \"]",
                "\"tools\":[\"unknown\"]", "\"tools\":[\"read_file\",\"write_file\"]",
                "\"tools\":[\"task\"]", "\"tools\":[\"run_command\"]", "\"tools\":[\"web_fetch\"]")) {
            var args = MAPPER.readTree("{\"prompt\":\"调查\"," + fields + "}");
            assertTrue(tool.execute(args, ctx()).startsWith("ERROR:"), fields);
        }
        for (String invalid : List.of("null", "[]", "{\"prompt\":1}", "{\"prompt\":null}")) {
            assertTrue(tool.execute(MAPPER.readTree(invalid), ctx()).startsWith("ERROR:"), invalid);
        }
        assertEquals(0, calls.get());
    }

    @Test
    void schemaAdvertisesOptionalPresetAndToolChoices() {
        var schema = new TaskTool().schema();
        assertEquals("[\"prompt\"]", schema.path("required").toString());
        assertEquals("[\"explore\",\"edit\"]", schema.path("properties").path("profile").path("enum").toString());
        var toolNames = schema.path("properties").path("tools").path("items").path("enum");
        assertEquals(9, toolNames.size());
        assertTrue(toolNames.toString().contains("read_file"));
        assertTrue(toolNames.toString().contains("git_diff"));
        assertTrue(new TaskTool().summary(MAPPER.createObjectNode().put("prompt", "调查")).contains("profile=explore"));
    }

    @Test
    void blankPromptRejected() throws Exception {
        TaskTool tool = new TaskTool();
        tool.bind((prompt, cancelled) -> "不应被调用");
        var args = MAPPER.createObjectNode();
        args.put("prompt", "   ");
        assertEquals("ERROR: prompt 为空", tool.execute(args, ctx()));
    }
}
