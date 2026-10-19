package com.jaco.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentRunnerGateTest {

    @Test
    void clipMiddleKeepsHeadAndTail() {
        String text = "HEAD" + "x".repeat(1000) + "TAIL";
        String clipped = AgentRunner.clipMiddle(text, 100);
        assertTrue(clipped.startsWith("(工具输出过长，仅保留头尾预览"));
        assertTrue(clipped.contains("HEAD"));
        assertTrue(clipped.endsWith("TAIL"));
        assertTrue(clipped.contains("…(中间省略)…"));
        assertFalse(clipped.contains("recall"));
        assertFalse(clipped.contains("已归档"));
        assertTrue(clipped.length() <= 100);
    }

    @Test
    void clipMiddleShorterThanKeepEachIsSafe() {
        String clipped = AgentRunner.clipMiddle("ab", 10);
        assertEquals("ab", clipped);
    }

    @Test void unicodeAndTinyBudgetsNeverProduceUnpairedSurrogates() {
        String text = "😀".repeat(500);
        for (int budget = 0; budget < 200; budget++) {
            String clipped = AgentRunner.clipMiddle(text, budget);
            assertTrue(clipped.length() <= budget);
            for (int i = 0; i < clipped.length(); i++) {
                char c = clipped.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    assertTrue(i + 1 < clipped.length() && Character.isLowSurrogate(clipped.charAt(++i)));
                } else assertFalse(Character.isLowSurrogate(c));
            }
        }
    }
}
