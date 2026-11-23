package dev.jharness.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Spec 021 AC-05. */
class TerminalSafeTest {

    @Test
    void escapesControlsAndBidi() {
        assertEquals("plain é ✓", TerminalSafe.escape("plain é ✓"));
        assertEquals("a\\eb\\x07c\\x9bd\\x7fe\\u202ef\\u2066g\\nh\\ti\\r", TerminalSafe.escape("a\u001bb\u0007c\u009bd\u007fe‮f⁦g\nh\ti\r"));
        assertEquals("{\"t\":\"a\\u001b\\u009b\\u202e\"}", TerminalSafe.escapeJson("{\"t\":\"a\u001b\u009b‮\"}"));
        assertEquals("{\"t\":\"é\"}", TerminalSafe.escapeJson("{\"t\":\"é\"}"));
    }
}
