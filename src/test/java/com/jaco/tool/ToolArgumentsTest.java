package com.jaco.tool;

import com.fasterxml.jackson.databind.*;
import com.jaco.hook.*;
import com.jaco.llm.ToolCall;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolArgumentsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void invalidArgumentsNeverReachPreparationOrPermissions() throws Exception {
        var prepared = new AtomicInteger();
        var permissions = new AtomicInteger();
        var registry = new ToolRegistry();
        registry.register(new Tool() {
            public String name() { return "sample"; }
            public String description() { return "sample"; }
            public JsonNode schema() { return JsonSchema.object().integer("count", "数量", 1, 3).required("count").build(); }
            public String summary(JsonNode args) { return "sample"; }
            public PreparedToolCall prepare(JsonNode args, ToolContext ctx) { prepared.incrementAndGet(); return PreparedToolCall.text("preview", () -> "ok"); }
            public ToolResult execute(JsonNode args, ToolContext ctx) { throw new AssertionError(); }
        });
        var executor = new ToolCallExecutor(registry, new HookChain(List.of(new AgentHook() {
            public HookVerdict onBeforeToolCall(ToolCall call) { permissions.incrementAndGet(); return HookVerdict.allow(); }
        })));
        var ctx = new ToolContext(Path.of("."), null, "auto", new AtomicReference<>(), () -> false, null);
        for (String args : List.of("", "null", "[]", "{}", "{\"count\":\"1\"}", "{\"count\":1.5}",
                "{\"count\":0}", "{\"count\":4}", "{\"count\":1,\"extra\":true}")) {
            var result = executor.execute(new ToolCall("id", "function", new ToolCall.FunctionCall("sample", args)), ctx, null, e -> { });
            assertEquals(ToolExecution.Status.FAILED, result.status(), args);
        }
        assertEquals(0, prepared.get()); assertEquals(0, permissions.get());
        registry.prepare(new ToolCall("id", "function", new ToolCall.FunctionCall("sample", "{\"count\":3}")), ctx);
        assertEquals(1, prepared.get());
    }
    @Test void validatesNestedFieldsEnumsAndLengths() throws Exception {
        var schema = mapper.readTree("""
            {"type":"object","required":["items"],"additionalProperties":false,"properties":{
              "items":{"type":"array","minItems":1,"maxItems":2,"items":{
                "type":"object","required":["name"],"additionalProperties":false,"properties":{
                  "name":{"type":"string","minLength":1,"maxLength":3},
                  "mode":{"type":"string","enum":["a","b"]}}}}}}
            """);
        ToolArguments.validate(mapper.readTree("{\"items\":[{\"name\":\"abc\",\"mode\":\"a\"}]}"), schema);
        for (String item : List.of("[]", "[{\"name\":\"\"}]", "[{\"name\":\"abcd\"}]",
                "[{\"name\":true}]", "[{\"name\":\"x\",\"mode\":\"c\"}]", "[{\"name\":\"x\",\"extra\":1}]")) {
            assertThrows(ToolException.class, () -> ToolArguments.validate(mapper.readTree("{\"items\":" + item + "}"), schema), item);
        }
    }
}
