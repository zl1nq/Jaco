package com.jaco.tool;

import com.jaco.tool.builtin.*;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ToolPromptsTest {
    @Test void stableOrderAndVisibleToolFiltering() {
        var registry = new ToolRegistry(new ReadFileTool(), new EditFileTool(), new TaskTool());
        String all = registry.prompts(null);
        assertTrue(all.indexOf("## 工具 read_file") < all.indexOf("## 工具 edit_file"));
        assertTrue(all.indexOf("## 工具 edit_file") < all.indexOf("## 工具 task"));
        assertEquals(all, registry.prompts(null));
        assertTrue(registry.prompts(Set.of()).isEmpty());
        String child = registry.prompts(Set.of("read_file", "task"));
        assertTrue(child.contains("## 工具 read_file"));
        assertFalse(child.contains("## 工具 edit_file"));
        assertFalse(child.contains("## 工具 task\n"));
    }
    @Test void retainsCriticalToolRulesWithShortDescriptions() {
        var read = new ReadFileTool(); var edit = new EditFileTool(); var web = new WebFetchTool();
        try {
            assertTrue(read.prompt().contains("next_column_offset"));
            assertTrue(read.prompt().contains("expected_version"));
            assertTrue(edit.prompt().contains("原子替换"));
            assertTrue(edit.prompt().contains("preserve"));
            assertTrue(web.prompt().contains("不可信"));
            assertTrue(web.prompt().contains("fetch_id"));
            assertTrue(new ProcessPollTool(null).prompt().contains("has_more"));
            assertTrue(new AskUserQuestionTool().prompt().contains("不能代替写入或命令权限"));
            assertTrue(read.description().length() < read.prompt().length());
        } finally { web.close(); }
    }
}
