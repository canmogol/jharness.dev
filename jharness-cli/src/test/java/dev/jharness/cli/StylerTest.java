package dev.jharness.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

class StylerTest {

    private static final String ESC = "\u001b[";

    @Test
    void plainLeavesTextAlone() {
        Styler s = new Styler(false);
        for (String l : new String[] {"# Title", "**bold** and `code`", "```js", "x = 1", "```", "- item"}) {
            assertEquals(l, s.line(l));
        }
        assertEquals("+a\n-b\n", s.diff("+a\n-b\n"));
        assertEquals("▸ step 1: error", s.error("▸ step 1: error"));
    }

    @Test
    void markdownLines() {
        Styler s = new Styler(true);
        assertEquals(ESC + "1m" + ESC + "34m## Plan" + ESC + "0m", s.line("## Plan"));
        assertEquals("a " + ESC + "1mbold" + ESC + "0m and " + ESC + "33mcode" + ESC + "0m.", s.line("a **bold** and `code`."));
        assertEquals("  " + ESC + "36m-" + ESC + "0m one", s.line("  - one"));
        assertEquals(ESC + "36m1." + ESC + "0m two", s.line("1. two"));
        assertEquals("no markup", s.line("no markup"));
    }

    @Test
    void codeFencesKeepState() {
        Styler s = new Styler(true);
        assertEquals(ESC + "2m```js" + ESC + "0m", s.line("```js"));
        assertEquals(ESC + "33m# not a heading" + ESC + "0m", s.line("# not a heading"));
        assertEquals(ESC + "33m``` inside" + ESC + "0m", s.line("``` inside"), "a fence with text doesn't close");
        assertEquals(ESC + "2m```" + ESC + "0m", s.line("```"));
        assertEquals(ESC + "1m" + ESC + "34m# heading" + ESC + "0m", s.line("# heading"));
        s.line("~~~");
        s.reset();
        assertEquals("plain", s.line("plain"), "reset forgets an open fence");
    }

    @Test
    void diffsAndStatus() {
        Styler s = new Styler(true);
        String d = s.diff("--- a/f\n+++ b/f\n@@ -1 +1 @@\n-old\n+new\n same\n");
        assertTrue(d.contains(ESC + "31m-old" + ESC + "0m"), d);
        assertTrue(d.contains(ESC + "32m+new" + ESC + "0m"), d);
        assertTrue(d.contains(ESC + "36m@@ -1 +1 @@"), d);
        assertTrue(d.contains(ESC + "1m--- a/f"), d);
        assertTrue(d.endsWith(" same\n"), d);
        assertEquals(ESC + "31mboom" + ESC + "0m", s.error("boom"));
        assertEquals(ESC + "36mstep" + ESC + "0m", s.step("step"));
        assertEquals(ESC + "2m" + ESC + "3mhmm" + ESC + "0m", s.thinking("hmm"));
        assertEquals("hmm", new Styler(false).thinking("hmm"));
    }

    @Test
    void colorFollowsTerminalAndNoColor() throws Exception {
        assertTrue(Styler.forTerminal(terminal("xterm-256color"), Map.of()).color());
        assertFalse(Styler.forTerminal(terminal("xterm-256color"), Map.of("NO_COLOR", "1")).color());
        assertTrue(Styler.forTerminal(terminal("xterm-256color"), Map.of("NO_COLOR", "")).color(), "an empty NO_COLOR doesn't count");
        assertFalse(Styler.forTerminal(terminal("dumb"), Map.of()).color());
    }

    private static Terminal terminal(String type) throws Exception {
        return TerminalBuilder.builder().system(false).type(type).dumb(type.equals("dumb"))
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()).build();
    }

    /** Spec 067: each line is styled on its own, so a line cut off above the prompt never leaves a style open. */
    @Test
    void everyLineClosesItsStyle() {
        Styler s = new Styler(true);
        String t = s.thinking("For each agent:\nDo not\n\nmore");
        for (String line : t.split("\n", -1)) {
            if (!line.isEmpty()) {
                assertTrue(line.endsWith("\u001b[0m"), "closed: " + line.replace("\u001b", "<ESC>"));
                assertTrue(line.startsWith("\u001b["), "opened: " + line.replace("\u001b", "<ESC>"));
            }
        }
        assertEquals(4, t.split("\n", -1).length, "the newlines stay");
        assertEquals("", s.thinking(""));
    }
}
