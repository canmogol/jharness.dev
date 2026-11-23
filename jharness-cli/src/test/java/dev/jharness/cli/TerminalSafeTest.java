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

    /** Spec 022. */
    @Test
    void liveTextKeepsLinesAndDropsCarriageReturns() {
        assertEquals("a\n\tb\\e[2Jc\\x9bd\\u202eeX", TerminalSafe.text("a\n\tb\u001b[2Jc\u009bd\u202ee\rX"));
        assertEquals("plain é ✓ 😀", TerminalSafe.text("plain é ✓ 😀"));
    }

    @Test
    void streamCatchesCharactersSplitAcrossWrites() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        var s = TerminalSafe.stream(new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8));
        assertSame(s, TerminalSafe.stream(s), "not wrapped twice");
        byte[] all = "é\u009b\u202e😀\u001b[1m\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (byte b : all) {
            s.write(b);
        }
        s.print("ok\u0007");
        s.flush();
        assertEquals("é\\x9b\\u202e😀\\e[1m\nok\\x07", bytes.toString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
