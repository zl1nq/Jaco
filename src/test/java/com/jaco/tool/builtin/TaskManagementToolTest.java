package com.jaco.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TaskManagementToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void statusIsNonblockingAndCannotExecuteConsoleCancelSyntax() throws Exception {
        var tool = new TaskStatusTool(id -> id.isEmpty() ? "任务列表" : "报告: " + id);
        assertEquals("任务列表", tool.execute(mapper.readTree("{}"), null));
        assertEquals("报告: task-1", tool.execute(mapper.readTree("{\"task_id\":\"task-1\"}"), null));
        for (String invalid : List.of("null", "[]", "{\"task_id\":null}", "{\"task_id\":1}",
                "{\"task_id\":\"\"}", "{\"task_id\":\"cancel task-1\"}")) {
            assertTrue(tool.execute(mapper.readTree(invalid), null).startsWith("ERROR:"));
        }
    }

    @Test void cancelRequiresExplicitTaskId() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var tool = new TaskCancelTool(id -> { calls.incrementAndGet(); return "取消 " + id; });
        for (String invalid : List.of("null", "[]", "{}", "{\"task_id\":null}",
                "{\"task_id\":false}", "{\"task_id\":\" \"}")) {
            assertTrue(tool.execute(mapper.readTree(invalid), null).startsWith("ERROR:"));
        }
        assertEquals(0, calls.get());
        assertEquals("取消 task-1", tool.execute(mapper.readTree("{\"task_id\":\" task-1 \"}"), null));
        assertEquals(1, calls.get());
    }
}
