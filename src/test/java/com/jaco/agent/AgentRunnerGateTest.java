package com.jaco.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunnerGateTest {

    @Test
    void clipMiddleKeepsHeadAndTail() {
        String text = "HEAD" + "x".repeat(1000) + "TAIL";
        String clipped = AgentRunner.clipMiddle(text, 100);
        assertTrue(clipped.startsWith("(工具输出过大已存档，共 1008 字符，保留头尾各 100 字符"));
        assertTrue(clipped.contains("HEAD"));
        assertTrue(clipped.endsWith("TAIL"));
        assertTrue(clipped.contains("…(中间省略)…"));
        assertTrue(clipped.contains("recall"));
    }

    @Test
    void clipMiddleShorterThanKeepEachIsSafe() {
        String clipped = AgentRunner.clipMiddle("ab", 10);
        // keepEach 大于原文时收缩到原文长度，substring 不越界
        assertEquals("(工具输出过大已存档，共 2 字符，保留头尾各 2 字符；完整内容可用 recall 工具取回)\nab\n…(中间省略)…\nab",
                clipped);
    }
}
