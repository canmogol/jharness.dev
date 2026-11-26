package dev.jharness.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.terminal.impl.ExternalTerminal;

import dev.jharness.agent.Session;
import dev.jharness.config.Settings;
import dev.jharness.events.Event;
import dev.jharness.events.Json;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.Operation;

/** Spec 022: text from the model, files, tools and servers never reaches the terminal as control sequences. */
class TuiEscapeTest {

    /** A title change, a screen clear, a C1 CSI, a bidi override and a carriage return. */
    static final String HOSTILE = "\u001b]0;pwned\u0007\u001b[2J\u009b31m‮evil\rX";
    static final String SHOWN = "\\e]0;pwned\\x07\\e[2J\\x9b31m\\u202eevilX";
    /** The same, as JavaScript string escapes, so the isolate makes the characters. */
    static final String JS = "\\u001b]0;pwned\\u0007\\u001b[2J\\u009b31m\\u202eevil\\rX";

    @TempDir
    Path tmp;
    final ByteArrayOutputStream screen = new ByteArrayOutputStream();

    private Terminal xterm(String input) throws Exception {
        return xterm(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
    }

    /** An xterm whose keys come from a stream, such as {@link Keys} (spec 055). */
    private Terminal xterm(java.io.InputStream input) throws Exception {
        var raw = new org.jline.terminal.Attributes();
        raw.setInputFlag(org.jline.terminal.Attributes.InputFlag.ICRNL, false);
        return new ExternalTerminal(null, "test", "xterm-256color", input,
                screen, StandardCharsets.UTF_8, Terminal.SignalHandler.SIG_DFL, false, raw, new org.jline.terminal.Size(200, 50));
    }

    private Terminal dumb(String input) throws Exception {
        return new DumbTerminal("test", "dumb", new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), screen, StandardCharsets.UTF_8);
    }

    private String screen() {
        return screen.toString(StandardCharsets.UTF_8);
    }

    private void assertNoRawPayload(String sc) {
        assertFalse(sc.contains("\u001b]0;pwned"), "title change reached the terminal: " + sc);
        assertFalse(sc.contains("\u001b[2J"), "screen clear reached the terminal: " + sc);
        assertFalse(sc.contains("\u009b"), "C1 CSI reached the terminal");
        assertFalse(sc.contains("‮"), "bidi override reached the terminal");
        assertFalse(sc.contains("evil\rX"), "carriage return reached the terminal");
    }

    private static Event event(String type, com.fasterxml.jackson.databind.node.ObjectNode body) {
        return new Event("e", Instant.now(), "s", "main", 1, "host", type, body);
    }

    /** A real session: workspace name, reply, thinking, approval question and diff, file change and step error. */
    @Test
    void aSessionsOutputIsEscaped() throws Exception {
        for (boolean color : List.of(true, false)) {
            screen.reset();
            Path ws = Files.createDirectories(tmp.resolve("ws-" + color + "-\u001b[2J"));
            // On a live xterm the question waits as a notice until /answer main (spec 055).
            Tui[] h = new Tui[1];
            Terminal t = color ? xterm(new Keys("go\r", Keys.held(() -> h[0]), "/answer main\r", Keys.pause(300), "y\r", Keys.settled(() -> h[0]),
                    "/events all\r", "go again\r", Keys.settled(() -> h[0]), "/exit\r"))
                    : dumb("go\ny\n/events all\ngo again\n/exit\n");
            Tui tui = h[0] = new Tui(t, null, java.util.Map.of());
            List<com.fasterxml.jackson.databind.JsonNode> lines = List.of(
                    Json.object().put("thinking", "idea " + HOSTILE + "\n").put("reply", "Reply " + HOSTILE + "\n```js\napi.writeFile('a.txt', '" + JS + "')\n```"),
                    Json.object().put("reply", "Done " + HOSTILE),
                    Json.object().put("reply", "```js\nthrow new Error('" + JS + "')\n```"),
                    Json.object().put("reply", "After."));
            try (Session session = Session.open(tmp.resolve("home-" + color), ws, Settings.parse("{\"ui\":{\"thinking\":\"show\"}}", "t"),
                    Session.Access.prompter(tui.prompter()), (bus, turns) -> new ReplayProvider(lines, bus, turns))) {
                tui.run(session);
            }
            String sc = screen();
            assertNoRawPayload(sc);
            assertTrue(sc.contains("Reply " + SHOWN), sc);
            assertTrue(sc.contains("idea " + SHOWN), sc);
            assertTrue(sc.contains("Done " + SHOWN), sc);
            assertTrue(sc.contains("+" + SHOWN), "the diff in the question: " + sc);
            assertTrue(sc.contains("ws-" + color + "-\\e[2J"), "the workspace name: " + sc);
            assertTrue(sc.contains("· model.completed"), "events shown inline: " + sc);
            assertTrue(sc.contains("line 1: Error: " + SHOWN), "the step error: " + sc);
            if (color) {
                assertTrue(sc.contains("\u001b[36m▸ step 1"), "styling still works: " + sc);
            }
        }
    }

    @Test
    void eventsAndQuestionsAreEscaped() throws Exception {
        Tui tui = new Tui(xterm("n\r"), null, java.util.Map.of());
        tui.onEvent(event("file.changed", Json.object().put("path", "a.txt").put("diff", "+" + HOSTILE + "\n")));
        tui.onEvent(event("loop.result", Json.object().put("step", 1).put("status", "error").put("error", HOSTILE)));
        tui.onEvent(event("model.retry", Json.object().put("provider", "p").put("model", "m").put("attempt", 2).put("attempts", 5)
                .put("waitMs", 1000).put("reason", HOSTILE)));
        tui.prompter().idle = false;
        tui.prompter().ask(new Operation("http", Operation.Kind.WRITE, "POST " + HOSTILE, Json.object().put("diff", "-" + HOSTILE)));
        String sc = screen();
        assertNoRawPayload(sc);
        assertTrue(sc.contains("Allow http: POST " + SHOWN + "?"), sc);
        assertTrue(sc.contains("line 1: ") || sc.contains(SHOWN), sc);
        assertTrue(sc.contains("\u001b[31m▸ step 1: error"), "the step line keeps its color: " + sc);
    }
}
