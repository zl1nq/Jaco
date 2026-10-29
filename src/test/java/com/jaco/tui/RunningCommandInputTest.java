package com.jaco.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RunningCommandInputTest {
    @Test void overflowNotifiesOnceAndBackspaceOrClearAllowsResubmission() {
        var input = new RunningCommandInput(4, true);
        "abcd".chars().forEach(input::accept);
        assertFalse(input.overflowed());
        assertEquals(RunningCommandInput.Action.OVERFLOW, input.accept('e'));
        assertEquals(RunningCommandInput.Action.NONE, input.accept('f'));
        assertEquals("abcd", input.text());
        assertEquals(RunningCommandInput.Action.SUBMIT, input.accept('\r'));
        assertTrue(input.overflowed());
        input.accept(127);
        assertFalse(input.overflowed());
        input.accept('z');
        assertEquals("abcz", input.text());
        input.accept('x');
        input.accept(21);
        assertTrue(input.isEmpty());
        assertFalse(input.overflowed());
    }

    @Test void utf16ReaderUnitsAreCombinedBeforeApplyingLimit() {
        var input = new RunningCommandInput(3, true);
        "a😀".chars().forEach(input::accept);
        assertEquals("a😀", input.text());
        input.accept(127);
        assertEquals("a", input.text());
        input.accept('b');
        assertEquals(RunningCommandInput.Action.NONE, input.accept('\uD83D'));
        assertEquals(RunningCommandInput.Action.OVERFLOW, input.accept('\uDE00'));
        assertEquals("ab", input.text());
        input.clear();
        input.accept('\uD83D');
        input.clear();
        input.accept('\uDE00');
        assertTrue(input.isEmpty());
    }

    @Test void oversizedPasteConsumesNewlinesAndEndMarkerWithoutSubmitting() {
        var input = new RunningCommandInput(4, true);
        "\033[200~abcd\r\nefgh\r\n\033[201~".chars().forEach(key ->
                assertNotEquals(RunningCommandInput.Action.SUBMIT, input.accept(key)));
        assertEquals("abcd", input.text());
        assertTrue(input.overflowed());
        input.accept(127);
        input.accept('z');
        assertEquals("abcz", input.text());
        assertEquals(RunningCommandInput.Action.SUBMIT, input.accept('\r'));
        assertFalse(input.overflowed());
    }
}
