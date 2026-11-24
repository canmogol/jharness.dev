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
        assertEquals(Prompter.Answer.UNATTENDED, p.ask(op), "no turn runs, so no one is asked (spec 027)");
        p.idle = false;
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

    /** Spec 026: a subagent's start, steps and end say whose they are. */
    @Test
    void subagentLinesNameTheSubagent() throws Exception {
        Tui tui = new Tui(terminal(""), null);
        tui.onEvent(new dev.jharness.events.Event("e1", java.time.Instant.now(), "s", "sub-1", 1, "host", "subagent.started",
                Json.object().put("task", "Find   the\nbug").put("customAgent", "reviewer").put("maxSteps", 30)));
        tui.onEvent(new dev.jharness.events.Event("e2", java.time.Instant.now(), "s", "sub-1", 1, "host", "loop.code",
                Json.object().put("step", 1).put("lines", 2)));
        tui.onEvent(new dev.jharness.events.Event("e3", java.time.Instant.now(), "s", "sub-1", 1, "host", "subagent.completed",
                Json.object().put("stopReason", "done").put("steps", 1).put("durationMs", 5)));
        String sc = screen();
        assertTrue(sc.contains("▸ [sub-1] started as reviewer: Find the bug"), sc);
        assertTrue(sc.contains("▸ [sub-1] step 1: running 2 lines"), sc);
        assertTrue(sc.contains("▸ [sub-1] done after 1 steps"), sc);
    }

    /** Spec 027 AC-07: a background subagent shows only its start and end. */
    @Test
    void backgroundSubagentsShowOnlyStartAndEnd() throws Exception {
        Tui tui = new Tui(terminal(""), null);
        tui.onEvent(new dev.jharness.events.Event("e1", java.time.Instant.now(), "s", "sub-2", 1, "host", "subagent.started",
                Json.object().put("task", "Scan").put("customAgent", "default").put("maxSteps", 30).put("background", true)));
        tui.onEvent(new dev.jharness.events.Event("e2", java.time.Instant.now(), "s", "sub-2", 1, "host", "loop.code",
                Json.object().put("step", 1).put("lines", 2)));
        tui.onEvent(new dev.jharness.events.Event("e3", java.time.Instant.now(), "s", "sub-2", 1, "host", "subagent.completed",
                Json.object().put("stopReason", "stopped").put("steps", 1).put("durationMs", 5)));
        String sc = screen();
        assertTrue(sc.contains("▸ [sub-2] started in the background: Scan"), sc);
        assertFalse(sc.contains("running 2 lines"), sc);
        assertTrue(sc.contains("▸ [sub-2] stopped after 1 steps (/agents sub-2 shows its events)"), sc);
    }

    /** Spec 027 AC-06, AC-08: /agents, /agents NAME, /stop, and /undo waiting for a running background subagent. */
    @Test
    void agentsAndStopCommands() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws27"));
        // Main spawns a background subagent and waits 5 s, so the subagent takes its reply (an endless loop) first.
        Tui tui = new Tui(terminal("go\n/agents\n/agents sub-1\n/agents sub-9\n/undo\n/stop sub-9\n/stop all\n/stop all\n/agents\n/exit\n"), null);
        try (Session session = replaySession(tmp.resolve("home27"), ws, "{}", tui,
                "```js\napi.spawnAgent({task: 'spin   forever', background: true}); JSON.parse(api.waitAgent({agent: 'sub-1', timeoutMs: 5000})).status\n```",
                "```js\nwhile (true) {}\n```", "Started it.")) {
            tui.run(session);
        }
        String sc = screen();
        assertTrue(sc.contains("▸ [sub-1] started in the background: spin forever"), sc);
        assertTrue(sc.matches("(?s).*NAME +STATUS +STEPS +AGENT +MODEL +TIME +TASK.*"), sc);
        assertTrue(sc.matches("(?s).*sub-1 +running +[01] +default +session .*spin forever.*"), sc);
        assertTrue(sc.contains("events of sub-1 (jharness log --agent sub-1 shows all):") && sc.contains("[sub-1]  subagent.started"), sc);
        assertTrue(sc.contains("No subagent sub-9 in this session. Subagents: sub-1."), sc);
        assertTrue(sc.contains("/undo waits for the background subagents to end: sub-1 still running. Stop them with /stop all."), sc);
        assertTrue(sc.contains("sub-9 isn't running. Running: sub-1"), sc);
        assertTrue(sc.contains("Stopped sub-1."), sc);
        assertTrue(sc.contains("No subagents are running."), sc);
        assertTrue(sc.matches("(?s).*sub-1 +stopped .*"), sc);
    }

    /** Spec 030: /follow shows a running background subagent's reply and steps until it ends. */
    @Test
    void followShowsABackgroundSubagentLive() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws30"));
        // Main starts sub-1 and waits until its first step runs, so sub-1 takes its reply (a 2 s block) before main's next.
        Tui tui = new Tui(terminal("go\n/follow sub-9\n/follow\n/follow sub-1\n/agents\n/exit\n"), null);
        try (Session session = replaySession(tmp.resolve("home30"), ws, "{}", tui,
                "```js\napi.spawnAgent({task: 'count to two', background: true});\n"
                        + "for (let i = 0; i < 200 && JSON.parse(api.agents())[0].steps < 1; i++) { api.waitAgent({agent: 'sub-1', timeoutMs: 50}); }\n'ok'\n```",
                "```js\nconst t = Date.now(); while (Date.now() - t < 2000) {}\n'slept'\n```",
                "Started it.",
                "Sub says hi.\nAll done.")) {
            tui.run(session);
        }
        String sc = screen();
        assertTrue(sc.contains("No subagent sub-9 in this session. Running: sub-1."), sc);
        int follow = sc.indexOf("Following sub-1 (count to two, 1 steps so far).");
        assertTrue(follow > 0, sc);
        String watched = sc.substring(follow);
        assertTrue(watched.contains("▸ [sub-1] step 1: ok"), watched);
        assertTrue(watched.contains("Sub says hi.\nAll done."), "its reply streams like the main loop's: " + watched);
        assertTrue(watched.contains("▸ [sub-1] done after 1 steps"), watched);
        assertTrue(watched.contains("sub-1 has ended; /agents sub-1 shows its events."), watched);
        assertTrue(watched.contains("sub-1 isn't running; /agents sub-1 shows its events."), watched);
        assertFalse(sc.substring(0, follow).contains("Sub says hi."), "not shown before /follow");
    }

    /** Spec 032: /switch talks to a subagent, whose questions come to the user, and /switch main goes back. */
    @Test
    void switchTalksToASubagent() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws32"));
        Tui tui = new Tui(terminal("go\n/switch sub-9\n/switch sub-1\nblue\ny\n/agents\n/switch main\n/exit\n"), null);
        try (Session session = replaySession(tmp.resolve("home32"), ws, "{}", tui,
                "```js\nJSON.parse(api.waitAgent(JSON.parse(api.spawnAgent({task: 'pick a color', background: true})).agent)).reply\n```",
                "Which color?",
                "It asks which color.",
                "```js\napi.writeFile('c.txt', 'blue')\n```",
                "Saved blue.")) {
            tui.run(session);
        }
        String sc = screen();
        assertTrue(sc.contains("No subagent sub-9 in this session. Subagents: sub-1."), sc);
        assertTrue(sc.contains("Talking to sub-1 (pick a color). Your messages go to it and its questions come to you"), sc);
        assertTrue(sc.contains("sub-1> blue"), "the prompt names the subagent: " + sc);
        assertTrue(sc.contains("Allow writeFile: write c.txt"), "its question was asked: " + sc);
        assertTrue(sc.contains("▸ [sub-1] step 1: ok") && sc.contains("Saved blue."), sc);
        assertEquals("blue", Files.readString(ws.resolve("c.txt")));
        assertTrue(sc.contains("Back to the main agent; sub-1 keeps its conversation (/switch sub-1 to go on)."), sc);
    }

    /** Spec 033: a background question waits, the user sees a notice, and /switch asks it. */
    @Test
    void aHeldQuestionIsAnsweredThroughSwitch() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws33"));
        Tui tui = new Tui(terminal("go\n/agents\n/switch sub-1\ny\n/switch main\n/exit\n"), null);
        try (Session session = Session.open(tmp.resolve("home33"), ws, Settings.defaults(),
                Session.Access.prompter(tui.prompter()).interactive(true),
                (bus, turns) -> ReplayProvider.ofReplies(List.of(
                        "```js\napi.spawnAgent({task: 'save c', background: true});\n"
                                + "for (let i = 0; i < 400 && JSON.parse(api.agents())[0].status !== 'waiting'; i++) { api.waitAgent({agent: 'sub-1', timeoutMs: 50}); }\n"
                                + "JSON.parse(api.agents())[0].status\n```",
                        "```js\napi.writeFile('c.txt', 'saved')\n```",
                        "Started it.",
                        "Saved c.txt."), bus, turns))) {
            tui.run(session);
        }
        String sc = screen();
        assertTrue(sc.contains("▸ [sub-1] is waiting for you: write c.txt (5 bytes) (/switch sub-1 to answer)"), sc);
        assertTrue(sc.matches("(?s).*sub-1 +waiting .*"), "/agents shows it waiting: " + sc);
        assertTrue(sc.contains("sub-1 is running; you can type when its turn ends."), sc);
        assertTrue(sc.contains("Allow writeFile: write c.txt (5 bytes)?"), sc);
        assertTrue(sc.contains("Saved c.txt."), sc);
        assertEquals("saved", Files.readString(ws.resolve("c.txt")));
    }

    /** Spec 035: a user's slash command runs as a turn, and /commands and /help list it. */
    @Test
    void userSlashCommands() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws35"));
        Path home = tmp.resolve("home35");
        Path c = Files.createDirectories(home.resolve(".jharness/commands"));
        Files.writeString(c.resolve("greet.md"), "---\ndescription: say hello to someone\n---\nSay hello to $ARGUMENTS.\n");
        Files.writeString(c.resolve("count.js"), "function run(args) { return {print: 'count: ' + args.split(' ').length}; }");
        Tui tui = new Tui(terminal("/greet Bob\n/count a b c\n/commands\n/help\n/nope\n/exit\n"), null);
        ReplayProvider[] replay = new ReplayProvider[1];
        try (Session session = Session.open(home, ws, Settings.defaults(), Session.Access.prompter(tui.prompter()),
                (bus, turns) -> replay[0] = ReplayProvider.ofReplies(List.of("Hello Bob!"), bus, turns))) {
            tui.run(session);
        }
        String sc = screen();
        assertTrue(sc.contains("/greet → Say hello to Bob.") && sc.contains("Hello Bob!"), sc);
        assertEquals("Say hello to Bob.", replay[0].requests().getFirst().messages().getFirst().content());
        assertTrue(sc.contains("count: 3"), sc);
        assertTrue(sc.matches("(?s).*/greet +say hello to someone.*"), sc);
        assertTrue(sc.contains("Your commands:"), sc);
        assertTrue(sc.contains("Unknown command /nope"), sc);
    }

    /** Spec 036: /plan, the [plan] prompt, a proposed plan approved by the user, and the todo list. */
    @Test
    void planModeAndTodos() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws36"));
        Tui tui = new Tui(terminal("/todos\n/plan\nplan the change\ny\n/todos\n/exit\n"), null);
        try (Session session = replaySession(tmp.resolve("home36"), ws, "{}", tui,
                "```js\napi.todos([{text: 'read the code', status: 'done'}, {text: 'write the plan', status: 'in_progress'}, 'carry it out']);\n"
                        + "JSON.parse(api.proposePlan('1. **Edit** a.txt\\n2. Run the tests')).approved\n```",
                "Approved, carrying it out.")) {
            tui.run(session);
            assertFalse(session.planMode(), "the approved plan turned plan mode off");
        }
        String sc = screen();
        assertTrue(sc.contains("No todo list yet."), sc);
        assertTrue(sc.contains("Plan mode is on: the agent can read but not change anything"), sc);
        assertTrue(sc.contains("[plan] > plan the change"), "the prompt says plan mode is on: " + sc);
        assertTrue(sc.contains("▸ todos (1/3 done)") && sc.contains("✓ read the code") && sc.contains("▸ write the plan") && sc.contains("☐ carry it out"), sc);
        assertTrue(sc.contains("▸ Plan:") && sc.contains("1. **Edit** a.txt") && sc.contains("2. Run the tests"), sc);
        assertTrue(sc.contains("Leave plan mode and carry out this plan? [y] yes  [n] no, keep planning: y"), sc);
        assertTrue(sc.contains("▸ plan mode off: the plan was approved"), sc);
        assertTrue(sc.contains("Approved, carrying it out."), sc);
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
        assertEquals(List.of("/model", "/agent", "/hooks", "/commands", "/agents", "/follow", "/switch", "/stop", "/plan", "/todos", "/diff", "/undo", "/rewind", "/events", "/thinking", "/network", "/help", "/exit"),
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

    /** Spec 025: /agent lists the agents; the banner names the running one. */
    @Test
    void agentCommandAndBanner() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws13"));
        Path home = tmp.resolve("home13");
        Path d = Files.createDirectories(home.resolve(".jharness/agents/reviewer"));
        Files.writeString(d.resolve("agent.json"), "{\"description\":\"reviews\"}");
        Tui tui = new Tui(terminal("/agent\n/exit\n"), null);
        var reviewer = dev.jharness.agent.AgentDefinition.resolve(home, ws, false, "reviewer");
        try (Session session = Session.open(home, ws, Settings.defaults(), Session.Access.prompter(tui.prompter()).agent(reviewer),
                (bus, turns) -> ReplayProvider.ofReplies(List.of(), bus, turns))) {
            tui.run(session);
        }
        String sc = screen();
        assertTrue(sc.contains("· agent reviewer ·"), sc);
        assertTrue(sc.contains("  default  the built-in agent") && sc.contains("* reviewer  (user) reviews"), sc);
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
        tui.prompter().idle = false;
        assertEquals(Prompter.Answer.ALWAYS, tui.prompter().ask(op));
        assertTrue(screen().contains("Allow network: connect api.example.com:443?"), screen());
    }
}
