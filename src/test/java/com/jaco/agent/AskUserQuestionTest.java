package com.jaco.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaco.llm.ToolCall;
import com.jaco.tool.PermissionHook;
import com.jaco.tool.ToolContext;
import com.jaco.tool.ToolException;
import com.jaco.tool.UserQuestion;
import com.jaco.tool.builtin.AskUserQuestionTool;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AskUserQuestionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VALID = """
            {"questions":[{"id":"format","header":"格式","question":"配置用哪种格式？",
            "recommendation_reason":"便于人工编辑",
            "options":[{"id":"yaml","label":"YAML","recommended":true},
            {"id":"json","label":"JSON","description":"便于程序读取"}]}]}
            """;
    private static JsonNode args() throws Exception { return JSON.readTree(VALID); }
    private static ToolContext ctx(TurnHandle handle) {
        return new ToolContext(null, null, "auto", new AtomicReference<>(), handle::isCancelled, null);
    }

    private static final class Pending implements AutoCloseable {
        final AskUserQuestionTool tool = new AskUserQuestionTool();
        final TurnHandle handle = new TurnHandle();
        final CompletableFuture<String> result = new CompletableFuture<>();
        final Thread worker;
        Pending(JsonNode args) {
            worker = Thread.ofVirtual().unstarted(() -> {
                TurnEventSink.install(handle);
                try { result.complete(tool.execute(args, ctx(handle))); }
                catch (Exception e) { result.completeExceptionally(e); }
                finally { TurnEventSink.clear(); }
            });
            handle.setLoopThread(worker);
            worker.start();
        }
        TurnEvent.QuestionRequest question() {
            return assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                TurnEvent event;
                while ((event = handle.poll(100)) == null) assertFalse(result.isDone());
                return assertInstanceOf(TurnEvent.QuestionRequest.class, event);
            });
        }
        JsonNode result() throws Exception { return JSON.readTree(result.get(2, TimeUnit.SECONDS)); }
        @Override public void close() throws Exception {
            handle.cancel(); tool.close(); worker.join(1000); assertFalse(worker.isAlive());
        }
    }

    @Test void emptySubmitAndSkipUseRecommendationWithDistinctReasons() throws Exception {
        for (String input : List.of("", "   ", "/skip")) try (Pending pending = new Pending(args())) {
            var event = pending.question();
            event.resolver().accept(event.question().parseInput(event.requestId(), input));
            JsonNode answer = pending.result().path("answers").get(0);
            assertEquals("yaml", answer.path("selected_option_ids").get(0).asText());
            assertEquals("recommended_default", answer.path("source").asText());
            assertEquals(input.equals("/skip") ? "skipped" : "empty_submit", answer.path("default_reason").asText());
        }
    }

    @Test void silenceNeverChoosesAndDuplicateResponsesCannotChangeAnswer() throws Exception {
        try (Pending pending = new Pending(args())) {
            var event = pending.question();
            assertThrows(TimeoutException.class, () -> pending.result.get(120, TimeUnit.MILLISECONDS));
            assertTrue(event.active().getAsBoolean());
            event.resolver().accept(event.question().parseInput(event.requestId(), "2"));
            JsonNode answer = pending.result().path("answers").get(0);
            event.resolver().accept(event.question().parseInput(event.requestId(), "1"));
            assertEquals("json", answer.path("selected_option_ids").get(0).asText());
            assertEquals("user_selection", answer.path("source").asText());
            assertFalse(event.active().getAsBoolean());
        }
    }

    @Test void customTextTakesPrecedenceOverSelectionAndSkip() throws Exception {
        try (Pending pending = new Pending(args())) {
            var event = pending.question();
            event.resolver().accept(new UserQuestion.Submission(event.requestId(), "format", "json", " 自定义 😀 ", true));
            JsonNode answer = pending.result().path("answers").get(0);
            assertEquals("custom_text", answer.path("source").asText());
            assertEquals("自定义 😀", answer.path("custom_text").asText());
            assertTrue(answer.path("selected_option_ids").isEmpty());
        }
    }

    @Test void numberedInputIsValidatedAndExplicitTextCanBeNumeric() throws Exception {
        try (Pending pending = new Pending(args())) {
            var event = pending.question();
            assertThrows(ToolException.class, () -> event.question().parseInput(event.requestId(), "3"));
            assertThrows(ToolException.class, () -> event.question().parseInput(event.requestId(), "999999999999999"));
            assertThrows(ToolException.class, () -> event.question().parseInput(event.requestId(), "/text"));
            event.resolver().accept(event.question().parseInput(event.requestId(), "/text 123"));
            assertEquals("123", pending.result().path("answers").get(0).path("custom_text").asText());
        }
    }

    @Test void invalidIdsAndOversizedOrMalformedAnswersLeaveQuestionPending() throws Exception {
        try (Pending pending = new Pending(args())) {
            var event = pending.question();
            assertThrows(ToolException.class, () -> event.resolver().accept(new UserQuestion.Submission("wrong", "format", "yaml", null, false)));
            assertThrows(ToolException.class, () -> event.resolver().accept(new UserQuestion.Submission(event.requestId(), "wrong", "yaml", null, false)));
            assertThrows(ToolException.class, () -> event.resolver().accept(new UserQuestion.Submission(event.requestId(), "format", "missing", null, false)));
            for (String text : List.of("x".repeat(2001), "\u001b[2J", "\uD800")) {
                assertThrows(ToolException.class, () -> event.resolver().accept(new UserQuestion.Submission(event.requestId(), "format", null, text, false)));
            }
            assertTrue(event.active().getAsBoolean());
            event.resolver().accept(event.question().parseInput(event.requestId(), "2"));
            assertEquals("answered", pending.result().path("status").asText());
        }
    }

    @Test void mixedBatchIsSequentialAndTextQuestionCannotDefault() throws Exception {
        ObjectNode args = (ObjectNode) args();
        args.withArray("questions").addObject().put("id", "details").put("question", "补充说明？");
        try (Pending pending = new Pending(args)) {
            var first = pending.question();
            first.resolver().accept(first.question().parseInput(first.requestId(), ""));
            var second = pending.question();
            assertEquals(first.requestId(), second.requestId());
            assertThrows(ToolException.class, () -> second.resolver().accept(second.question().parseInput(second.requestId(), "")));
            first.resolver().accept(first.question().parseInput(first.requestId(), "2")); // 旧回调不能回答第二题。
            assertFalse(pending.result.isDone());
            second.resolver().accept(second.question().parseInput(second.requestId(), "/skip"));
            JsonNode result = pending.result();
            assertEquals("partial", result.path("status").asText());
            assertEquals("skipped", result.path("answers").get(1).path("status").asText());
            assertEquals("yaml", result.path("answers").get(0).path("selected_option_ids").get(0).asText());
        }
    }

    @Test void cancelSwitchAndCloseInvalidatePendingResponses() throws Exception {
        for (int action = 0; action < 3; action++) try (Pending pending = new Pending(args())) {
            var event = pending.question();
            if (action == 0) pending.handle.cancel();
            else if (action == 1) pending.tool.onSessionChanged();
            else pending.tool.close();
            assertFalse(event.active().getAsBoolean());
            event.resolver().accept(event.question().parseInput(event.requestId(), ""));
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> pending.result.get(1, TimeUnit.SECONDS));
            if (action == 0) assertInstanceOf(InterruptedException.class, failure.getCause());
            else assertInstanceOf(ToolException.class, failure.getCause());
        }
    }

    @Test void unavailableNeverAdoptsDefaultsAndPermissionIsNotRequested() throws Exception {
        var tool = new AskUserQuestionTool();
        String result = tool.execute(args(), ctx(new TurnHandle()));
        assertEquals("unavailable", JSON.readTree(result).path("status").asText());
        assertTrue(JSON.readTree(result).path("answers").isEmpty());
        assertTrue(new PermissionHook().onBeforeToolCall(new ToolCall("c", "function",
                new ToolCall.FunctionCall("ask_user_question", VALID))).proceed());
        assertFalse(com.jaco.agent.subagent.SubagentProfile.EXPLORE.tools().contains(tool.name()));
        assertFalse(com.jaco.agent.subagent.SubagentProfile.EDIT.tools().contains(tool.name()));
    }

    @Test void childEventBindingCannotAskEvenIfCalledDirectly() throws Exception {
        TurnHandle handle = new TurnHandle();
        TurnEventSink.install(handle);
        try (var binding = TurnEventSink.bindSubagent(TurnEventSink.current(), "child", () -> false)) {
            assertThrows(ToolException.class, () -> new AskUserQuestionTool().execute(args(), ctx(handle)));
            assertNull(handle.poll(1));
        } finally { TurnEventSink.clear(); }
    }

    @Test void strictQuestionValidationRejectsInvalidSchemasBeforeEmitting() throws Exception {
        var tool = new AskUserQuestionTool();
        for (String json : List.of("null", "[]", "{}", "{\"questions\":[]}", "{\"questions\":[null]}",
                "{\"questions\":[{\"id\":\"a\",\"question\":\"Q\",\"multi_select\":true}]}",
                "{\"questions\":[{\"id\":\"a\",\"question\":\"Q\",\"allow_custom\":false}]}",
                "{\"questions\":[{\"id\":\"a\",\"question\":\"Q\",\"options\":null}]}",
                "{\"questions\":[{\"id\":\"a\",\"question\":\"Q\",\"recommendation_reason\":\"R\"}]}",
                "{\"questions\":[{\"id\":\"a\",\"question\":\"Q\"},{\"id\":\"a\",\"question\":\"Q\"}]}")) {
            assertThrows(ToolException.class, () -> tool.prepare(JSON.readTree(json), null), json);
        }
        for (String mutation : List.of("no-reason", "no-recommended", "two-recommended", "duplicate-option", "control", "long")) {
            ObjectNode args = (ObjectNode) args();
            ObjectNode question = (ObjectNode) args.path("questions").get(0);
            switch (mutation) {
                case "no-reason" -> question.remove("recommendation_reason");
                case "no-recommended" -> ((ObjectNode) question.path("options").get(0)).put("recommended", false);
                case "two-recommended" -> ((ObjectNode) question.path("options").get(1)).put("recommended", true);
                case "duplicate-option" -> ((ObjectNode) question.path("options").get(1)).put("id", "yaml");
                case "control" -> question.put("question", "\u001b[2J");
                case "long" -> question.put("question", "x".repeat(1001));
            }
            assertThrows(ToolException.class, () -> tool.prepare(args, null), mutation);
        }
    }
}
