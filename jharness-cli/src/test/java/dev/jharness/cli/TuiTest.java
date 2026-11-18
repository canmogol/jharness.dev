package dev.jharness.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.agent.Session;
import dev.jharness.config.Settings;
import dev.jharness.events.Json;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.Operation;
import dev.jharness.security.Prompter;

class TuiTest {

    @TempDir
    Path tmp;
    final ByteArrayOutputStream screen = new ByteArrayOutputStream();

    private Terminal terminal(String input) throws Exception {
        Terminal t = TerminalBuilder.builder().system(false).dumb(true).type("dumb")
                .streams(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), screen).build();
        t.setSize(new org.jline.terminal.Size(200, 50));
        return t;
    }

    private String screen() {
        return screen.toString(StandardCharsets.UTF_8);
    }

    @Test
    void sessionWithStepsApprovalsModelsAndHistory() throws Exception {
        Path home = tmp.resolve("home");
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("a.txt"), "old");
        Settings s = Settings.parse("{\"models\":{\"local\":{\"provider\":\"openai-compatible\",\"baseUrl\":\"http://127.0.0.1:1/v1\",\"model\":\"q\"},"
                + "\"claude\":{\"provider\":\"anthropic\",\"model\":\"c\"}}}", "t");
        // Line 1: a task; the approval asks once, gets "maybe" (asked again) then "y". Then /model, /help, /exit.
        Terminal t = terminal("fix a.txt\nmaybe\ny\n/diff\n/undo\n/model\n/help\n/unknown\n/exit\n");
        Path history = home.resolve(".jharness/history");
        Tui tui = new Tui(t, history);
        try (Session session = Session.open(home, ws, s, Session.Access.prompter(tui.prompter()),
                (bus, turns) -> ReplayProvider.ofReplies(List.of("Writing it.\n```js\napi.writeFile('a.txt', 'new')\n```", "Done: a.txt says new."), bus, turns))) {
            tui.run(session);
        }
        String sc = screen();
        assertTrue(sc.contains("Writing it."), sc);
        assertTrue(sc.contains("▸ step 1: running 1 lines"), sc);
        assertTrue(sc.contains("Allow writeFile: write a.txt"), sc);
        assertTrue(sc.contains("Please answer y, a or n."), sc);
        assertTrue(sc.contains("▸ step 1: ok"), sc);
        assertTrue(sc.contains("Done: a.txt says new."), sc);
        assertTrue(sc.contains("  local  openai-compatible  q") && sc.contains("  claude  anthropic  c"), sc);
        assertTrue(sc.contains("/model NAME"), sc);
        assertTrue(sc.contains("Unknown command /unknown"), sc);
        assertTrue(sc.contains("-old") && sc.contains("+new"), "diff in the approval question and /diff: " + sc);
        assertTrue(sc.contains("Restored a.txt."), sc);
        assertEquals("old", Files.readString(ws.resolve("a.txt")), "/undo restored the file");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(history)));
        assertTrue(Files.readString(history).contains("fix a.txt"), "history saved");
    }

    @Test
    void prompterAnswers() throws Exception {
        Operation op = new Operation("git", Operation.Kind.GIT_WRITE, "git commit", Json.object());
        Tui tui = new Tui(terminal("a\nn\nYES\n"), null);
        TerminalPrompter p = tui.prompter();
        assertEquals(Prompter.Answer.ALWAYS, p.ask(op));
        assertEquals(Prompter.Answer.DENY, p.ask(op));
        assertEquals(Prompter.Answer.ONCE, p.ask(op));
        assertEquals(Prompter.Answer.DENY, p.ask(op), "end of input denies");
    }

    @Test
    void trustQuestion() throws Exception {
        Tui tui = new Tui(terminal("maybe\ny\nn\n"), null);
        assertTrue(tui.askTrust(tmp));
        assertFalse(tui.askTrust(tmp));
        assertFalse(tui.askTrust(tmp), "end of input means no");
        String sc = screen();
        assertTrue(sc.contains("Do you trust " + tmp + "?"), sc);
        assertTrue(sc.contains("loosen JHarness rules") && sc.contains("Please answer y or n."), sc);
    }

    @Test
    void ctrlDQuits() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws2"));
        Tui tui = new Tui(terminal(""), null);
        try (Session session = Session.open(tmp.resolve("home2"), ws, Settings.defaults(), Session.Access.prompter(tui.prompter()),
                (bus, turns) -> ReplayProvider.ofReplies(List.of(), bus, turns))) {
            tui.run(session);
        }
        assertTrue(screen().contains("/help for commands"));
    }
}
