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
import org.jline.terminal.impl.DumbTerminal;
import org.jline.terminal.impl.ExternalTerminal;
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

    /**
     * In-process terminals, so output reaches {@link #screen} on the writing thread.
     * {@code TerminalBuilder.system(false)} may give a real PTY, whose output is copied to the stream
     * by a background thread, so a test could read the screen before the question reached it.
     */
    private Terminal terminal(String input) throws Exception {
        Terminal t = new DumbTerminal("test", "dumb", new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                screen, StandardCharsets.UTF_8);
        t.setSize(new org.jline.terminal.Size(200, 50));
        return t;
    }

    /** An xterm in raw mode, as JLine reads a real one: Enter is \r, Ctrl-J is \n. */
    private Terminal xterm(String input) throws Exception {
        var raw = new org.jline.terminal.Attributes();
        raw.setInputFlag(org.jline.terminal.Attributes.InputFlag.ICRNL, false);
        return new ExternalTerminal(null, "test", "xterm-256color", new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                screen, StandardCharsets.UTF_8, Terminal.SignalHandler.SIG_DFL, false, raw, new org.jline.terminal.Size(200, 50));
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

    private Session replaySession(Path home, Path ws, String settingsJson, Tui tui, String... replies) throws Exception {
        return Session.open(home, ws, Settings.parse(settingsJson, "t"), Session.Access.prompter(tui.prompter()),
                (bus, turns) -> ReplayProvider.ofReplies(List.of(replies), bus, turns));
    }

    @Test
    void stepErrorsAndTheLogPathAreShown() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws3"));
        Tui tui = new Tui(terminal("go\n/events\n/events loud\n/exit\n"), null);
        Path log;
        try (Session session = replaySession(tmp.resolve("home3"), ws, "{}", tui, "```js\nconst a = 1;\nnull.x;\n```", "Sorry.")) {
            log = session.logPath();
            tui.run(session);
        }
        String sc = screen();
        assertTrue(sc.contains("Session log: " + log), sc);
        assertTrue(sc.contains("▸ step 1: error"), sc);
        assertTrue(sc.contains("  line 2: ") && sc.contains("null"), sc);
        assertTrue(sc.contains("Events: steps (none, steps or all). Session log: " + log), sc);
        assertTrue(sc.contains("Usage: /events [none|steps|all]"), sc);
        assertFalse(sc.contains("\u001b["), "no color on a dumb terminal");
    }

    /** Spec 020 AC-06. */
    @Test
    void modelRetriesShowAsSteps() throws Exception {
        Tui tui = new Tui(terminal(""), null);
        tui.onEvent(new dev.jharness.events.Event("e1", java.time.Instant.now(), "s", "main", 1, "host", "model.retry",
                Json.object().put("provider", "anthropic").put("model", "m").put("attempt", 2).put("attempts", 5)
                        .put("waitMs", 6500).put("reason", "HTTP 429")));
        assertTrue(screen().contains("▸ HTTP 429 from anthropic, retrying in 7 s (attempt 2 of 5)"), screen());
    }

    @Test
    void eventLevels() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws4"));
        Tui tui = new Tui(terminal("one\n/events none\ntwo\n/exit\n"), null);
        try (Session session = replaySession(tmp.resolve("home4"), ws, "{\"ui\":{\"events\":\"all\"}}", tui,
                "```js\n1\n```", "first done", "```js\n2\n```", "second done")) {
            tui.run(session);
        }
        String sc = screen();
        int split = sc.indexOf("Events: none");
        String all = sc.substring(0, split);
        String none = sc.substring(split);
        assertTrue(all.contains("· turn.started {\"prompt\":\"one\"}"), all);
        assertTrue(all.contains("· loop.result {"), all);
        assertTrue(all.contains("▸ step 1: ok"), all);
        assertFalse(all.contains("· model.delta"), all);
        assertTrue(none.contains("second done"), none);
        assertFalse(none.contains("▸ step"), none);
        assertFalse(none.contains("· "), none);
    }

    @Test
    void altEnterAndCtrlJAddLines() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws5"));
        Terminal t = xterm("first\u001b\rsecond\nthird\r/exit\r");
        Tui tui = new Tui(t, null, java.util.Map.of());
        Path log;
        try (Session session = replaySession(tmp.resolve("home5"), ws, "{}", tui, "Got **three** lines.")) {
            log = session.logPath();
            tui.run(session);
        }
        var started = dev.jharness.events.SessionLog.read(log).stream().filter(e -> e.get("type").asText().equals("turn.started")).toList();
        assertEquals(1, started.size(), started + " screen: " + screen().replace("\u001b", "<ESC>"));
        assertEquals("first\nsecond\nthird", started.getFirst().get("prompt").asText());
        assertTrue(screen().contains("\u001b[1mthree\u001b[0m"), "colored reply: " + screen());
    }

    @Test
    void shiftEnterAddsLinesAndTheKeyModeIsOnOnlyWhileThePromptReads() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws14"));
        // Shift+Enter (modifyOtherKeys), Shift+Enter (CSI u), Ctrl+Enter (modifyOtherKeys), then Enter.
        Terminal t = xterm("a\u001b[27;2;13~b\u001b[13;2uc\u001b[27;5;13~d\r/exit\r");
        Tui tui = new Tui(t, null, java.util.Map.of());
        Path log;
        try (Session session = replaySession(tmp.resolve("home14"), ws, "{}", tui, "Four lines.")) {
            log = session.logPath();
            tui.run(session);
        }
        var started = dev.jharness.events.SessionLog.read(log).stream().filter(e -> e.get("type").asText().equals("turn.started")).toList();
        assertEquals(1, started.size(), started + " screen: " + screen().replace("\u001b", "<ESC>"));
        assertEquals("a\nb\nc\nd", started.getFirst().get("prompt").asText());
        String sc = screen();
        int on = sc.indexOf(Tui.MODIFY_KEYS_ON);
        int off = sc.indexOf(Tui.MODIFY_KEYS_OFF);
        int reply = sc.indexOf("Four lines.");
        assertTrue(on >= 0 && off > on && reply > off, "on, then off before the turn runs: " + sc.replace("\u001b", "<ESC>"));
        assertEquals(sc.split(java.util.regex.Pattern.quote(Tui.MODIFY_KEYS_ON), -1).length,
                sc.split(java.util.regex.Pattern.quote(Tui.MODIFY_KEYS_OFF), -1).length, "every on has an off");
    }

    @Test
    void dumbTerminalsGetNoKeyMode() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws14d"));
        Terminal t = terminal("/exit\n");
        Tui tui = new Tui(t, null, java.util.Map.of());
        try (Session session = replaySession(tmp.resolve("home14d"), ws, "{}", tui, "unused")) {
            tui.run(session);
        }
        assertFalse(screen().contains("\u001b[>4"), screen());
    }

    /** Spec 021 AC-01: --debug starts with every event and the thinking shown. */
    @Test
    void debugShowsEventsAndThinking() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws9"));
        Tui tui = new Tui(terminal("one\n/exit\n"), null);
        tui.debug();
        List<com.fasterxml.jackson.databind.JsonNode> lines = List.of(Json.object().put("thinking", "an idea\n").put("reply", "Answer."));
        try (Session session = Session.open(tmp.resolve("home9"), ws, Settings.defaults(),
                Session.Access.prompter(tui.prompter()), (bus, turns) -> new ReplayProvider(lines, bus, turns))) {
            tui.run(session);
        }
        String sc = screen();
        assertTrue(sc.contains("an idea"), sc);
        assertTrue(sc.contains("· turn.started"), sc);
    }

    /** Spec 024 AC-03: every command in the help is completed, each with its description. */
    @Test
    void commandsComeFromTheHelp() {
        assertEquals(List.of("/model", "/diff", "/undo", "/rewind", "/events", "/thinking", "/network", "/help", "/exit"),
                List.copyOf(Tui.COMMANDS.keySet()));
        assertEquals("list model profiles", Tui.COMMANDS.get("/model"));
        assertEquals("quit (or Ctrl-D)", Tui.COMMANDS.get("/exit"));
    }

    /** Spec 024 AC-01, AC-02: "/" then Tab prints the help; "/mo" then Tab completes /model; other text keeps its Tab behavior. */
    @Test
    void slashTabPrintsTheHelpAndCompletesCommands() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws10"));
        Tui tui = new Tui(xterm("/\texit\r"), null, java.util.Map.of());
        try (Session session = Session.open(tmp.resolve("home10"), ws, Settings.defaults(), Session.Access.prompter(tui.prompter()),
                (bus, turns) -> ReplayProvider.ofReplies(List.of(), bus, turns))) {
            tui.run(session);
        }
        String sc = screen();
        assertEquals(1, sc.split("Commands:", -1).length - 1, "the help once, from Tab: " + sc);
        assertTrue(sc.contains("/network reload re-read both rules files"), sc);

        screen.reset();
        Tui tui2 = new Tui(xterm("/mo\t\r/thi\t\r/exit\r"), null, java.util.Map.of());
        try (Session session = Session.open(tmp.resolve("home11"), ws, Settings.defaults(), Session.Access.prompter(tui2.prompter()),
                (bus, turns) -> ReplayProvider.ofReplies(List.of(), bus, turns))) {
            tui2.run(session);
        }
        sc = screen();
        assertTrue(sc.contains("No model profiles in settings.json."), "/mo + Tab ran /model: " + sc);
        assertTrue(sc.contains("Thinking: shown"), "/thi + Tab ran /thinking: " + sc);
        assertFalse(sc.contains("Unknown command"), sc);

        screen.reset();
        Tui tui3 = new Tui(xterm("/e\txit\r"), null, java.util.Map.of());
        try (Session session = Session.open(tmp.resolve("home12"), ws, Settings.defaults(), Session.Access.prompter(tui3.prompter()),
                (bus, turns) -> ReplayProvider.ofReplies(List.of(), bus, turns))) {
            tui3.run(session);
        }
        sc = screen();
        assertTrue(sc.contains("vents") && sc.contains("(show the event level and the session log file)") && sc.contains("(quit (or Ctrl-D))"),
                "both matches listed with descriptions (JLine colors the typed /e): " + sc);
    }

    @Test
    void thinkingShownDimOrHidden() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws6"));
        Tui tui = new Tui(terminal("one\n/thinking\ntwo\n/exit\n"), null);
        List<com.fasterxml.jackson.databind.JsonNode> lines = List.of(
                Json.object().put("thinking", "first idea\n").put("reply", "Answer one."),
                Json.object().put("thinking", "second idea\n").put("reply", "Answer two."));
        try (Session session = Session.open(tmp.resolve("home6"), ws, Settings.parse("{\"ui\":{\"thinking\":\"show\"}}", "t"),
                Session.Access.prompter(tui.prompter()), (bus, turns) -> new ReplayProvider(lines, bus, turns))) {
            tui.run(session);
        }
        String sc = screen();
        assertTrue(sc.contains("first idea"), sc);
        assertTrue(sc.indexOf("first idea") < sc.indexOf("Answer one."), sc);
        assertTrue(sc.contains("Thinking: hidden"), sc);
        assertFalse(sc.contains("second idea"), sc);
        assertTrue(sc.contains("Answer two."), sc);
    }

    @Test
    void networkCommands() throws Exception {
        Path home = tmp.resolve("home7");
        Path ws = Files.createDirectories(tmp.resolve("ws7"));
        Files.createDirectories(home.resolve(".jharness"));
        Files.writeString(Session.generalNetworkRules(home), "default ask\n");
        Tui tui = new Tui(terminal("/network\n/network allow API.example.com:443\n/network deny *.ads.example.com\n/network allow bad!\n"
                + "/network deny x.example.com --project\n/network remove *.ads.example.com\n/network remove nothing.example.com\n"
                + "/network reload\n/network list\n/network frob\n/exit\n"), null);
        try (Session session = Session.open(home, ws, Settings.parse("{\"models\":{\"lan\":{\"provider\":\"openai-compatible\","
                + "\"baseUrl\":\"http://192.168.68.140:1234/v1\",\"model\":\"q\"}}}", "t"), Session.Access.prompter(tui.prompter()),
                (bus, turns) -> ReplayProvider.ofReplies(List.of(), bus, turns))) {
            tui.run(session);
            assertEquals(dev.jharness.net.Rule.Action.ALLOW, session.proxy().rules()
                    .decide("api.example.com", 443, java.net.InetAddress.ofLiteral("93.184.216.34")).action(), "reloaded after the edit");
        }
        String sc = screen();
        assertTrue(sc.contains("General rules: " + Session.generalNetworkRules(home)), sc);
        assertTrue(sc.contains("  default ask"), sc);
        assertTrue(sc.contains("network.rules (project not trusted: only deny lines count)"), sc);
        assertTrue(sc.contains("Model endpoints, allowed automatically: 192.168.68.140:1234"), sc);
        assertTrue(sc.contains("Built-in denies (unless an allow names the address): 127.0.0.0/8"), sc);
        assertTrue(sc.contains("Added \"allow api.example.com:443\" to"), sc);
        assertTrue(sc.contains("Added \"deny *.ads.example.com\" to"), sc);
        assertTrue(sc.contains("bad host"), sc);
        assertTrue(sc.contains("This project isn't trusted"), sc);
        assertTrue(sc.contains("Removed 1 line(s)"), sc);
        assertTrue(sc.contains("No rule names nothing.example.com"), sc);
        assertTrue(sc.contains("Network rules reloaded."), sc);
        assertTrue(sc.contains("Usage: /network [list|allow X|deny X|remove X|reload] [--project]"), sc);
        assertEquals("default ask\nallow api.example.com:443\n", Files.readString(Session.generalNetworkRules(home)));
    }

    @Test
    void networkQuestion() throws Exception {
        Operation op = new Operation("network", Operation.Kind.NETWORK, "connect api.example.com:443",
                Json.object().put("host", "api.example.com").put("port", 443));
        Tui tui = new Tui(terminal("a\n"), null);
        assertEquals(Prompter.Answer.ALWAYS, tui.prompter().ask(op));
        assertTrue(screen().contains("Allow network: connect api.example.com:443?"), screen());
    }
}
