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
        return xterm(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
    }

    /** An xterm whose keys come from a stream, such as {@link Keys} (spec 055). */
    private Terminal xterm(java.io.InputStream input) throws Exception {
        var raw = new org.jline.terminal.Attributes();
        raw.setInputFlag(org.jline.terminal.Attributes.InputFlag.ICRNL, false);
        return new ExternalTerminal(null, "test", "xterm-256color", input,
                screen, StandardCharsets.UTF_8, Terminal.SignalHandler.SIG_DFL, false, raw, new org.jline.terminal.Size(200, 50));
    }

    /** A live TUI on an xterm: each message is sent after the previous turn ended, then /exit (spec 055). */
    private Tui live(String... messages) throws Exception {
        Tui[] h = new Tui[1];
        java.util.List<Object> script = new java.util.ArrayList<>();
        for (String m : messages) {
            script.add(m);
            script.add(Keys.settled(() -> h[0]));
        }
        script.add("/exit\r");
        h[0] = new Tui(xterm(new Keys(script.toArray())), null, java.util.Map.of());
        return h[0];
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
        // Main spawns a background subagent and spins 2 s, so the subagent takes its reply (an endless loop) first.
        Tui tui = new Tui(terminal("go\n/agents\n/agents sub-1\n/agents sub-9\n/undo\n/stop sub-9\n/stop all\n/stop all\n/agents\n/exit\n"), null);
        try (Session session = replaySession(tmp.resolve("home27"), ws, "{}", tui,
                "```js\napi.spawnAgent({task: 'spin   forever', background: true}); const t = Date.now(); while (Date.now() - t < 2000) {} 'running'\n```",
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
        try (Session session = replaySession(tmp.resolve("home30"), ws, "{\"agent\": {\"autoTurns\": 0}}", tui,
                "```js\napi.spawnAgent({task: 'count to two', background: true});\n"
                        + "for (let i = 0; i < 200 && JSON.parse(api.agents())[0].steps < 1; i++) { const t = Date.now(); while (Date.now() - t < 50) {} }\n'ok'\n```",
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
        try (Session session = replaySession(tmp.resolve("home32"), ws, "{\"agent\": {\"autoTurns\": 0}}", tui,
                "```js\napi.spawnAgent({task: 'pick a color', background: true});\n"
                        + "for (let i = 0; i < 400 && JSON.parse(api.agents())[0].status === 'running'; i++) { const t = Date.now(); while (Date.now() - t < 50) {} }\n'started'\n```",
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
        try (Session session = Session.open(tmp.resolve("home33"), ws, Settings.parse("{\"agent\": {\"autoTurns\": 0}}", "t"),
                Session.Access.prompter(tui.prompter()).interactive(true),
                (bus, turns) -> ReplayProvider.ofReplies(List.of(
                        "```js\napi.spawnAgent({task: 'save c', background: true});\n"
                                + "for (let i = 0; i < 400 && JSON.parse(api.agents())[0].status !== 'waiting'; i++) { const t = Date.now(); while (Date.now() - t < 50) {} }\n"
                                + "JSON.parse(api.agents())[0].status\n```",
                        "```js\napi.writeFile('c.txt', 'saved')\n```",
                        "Started it.",
                        "Saved c.txt."), bus, turns))) {
            tui.run(session);
        }
        String sc = screen();
        assertTrue(sc.contains("▸ [sub-1] is waiting for you: write c.txt (5 bytes) (/answer sub-1 to answer)"), sc);
        assertTrue(sc.matches("(?s).*sub-1 +waiting .*"), "/agents shows it waiting: " + sc);
        assertTrue(sc.contains("sub-1 is running; you can type when its turn ends."), sc);
        assertTrue(sc.contains("Allow writeFile: write c.txt (5 bytes)?"), sc);
        assertTrue(sc.contains("Saved c.txt."), sc);
        assertEquals("saved", Files.readString(ws.resolve("c.txt")));
    }

    /** Spec 054 AC-04: /answer asks a held question in place; the user stays in the main chat. */
    @Test
    void answerAsksAHeldQuestionInPlace() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws54a"));
        Tui tui = new Tui(terminal("go\n/answer sub-9\n/answer\ny\n/agents\n/answer\n/exit\n"), null);
        try (Session session = Session.open(tmp.resolve("home54a"), ws, Settings.parse("{\"agent\": {\"autoTurns\": 0}}", "t"),
                Session.Access.prompter(tui.prompter()).interactive(true),
                (bus, turns) -> ReplayProvider.ofReplies(List.of(
                        "```js\napi.spawnAgent({task: 'save c', background: true});\n"
                                + "for (let i = 0; i < 400 && JSON.parse(api.agents())[0].status !== 'waiting'; i++) { const t = Date.now(); while (Date.now() - t < 50) {} }\n"
                                + "JSON.parse(api.agents())[0].status\n```",
                        "```js\napi.writeFile('c.txt', 'saved')\n```",
                        "Started it.",
                        "Saved c.txt."), bus, turns))) {
            tui.run(session);
        }
        String sc = screen();
        assertTrue(sc.contains("▸ [sub-1] is waiting for you: write c.txt (5 bytes) (/answer sub-1 to answer)"), sc);
        assertTrue(sc.contains("sub-9 isn't waiting for an answer. Waiting: sub-1."), sc);
        assertTrue(sc.contains("sub-1 asks:\n") && sc.contains("Allow writeFile: write c.txt (5 bytes)?"), sc);
        assertTrue(sc.contains("Back to the main chat."), sc);
        assertFalse(sc.contains("sub-1> "), "the prompt stays the main one: " + sc);
        assertTrue(sc.contains("No question is waiting for an answer."), sc);
        assertEquals("saved", Files.readString(ws.resolve("c.txt")));
    }

    /** Spec 054 AC-02: a finished background subagent's reply starts a main turn by itself. */
    @Test
    void aFinishedSubagentStartsAMainTurn() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws54b"));
        Tui tui = new Tui(terminal("go\n"), null);
        ReplayProvider[] replay = new ReplayProvider[1];
        try (Session session = Session.open(tmp.resolve("home54b"), ws, Settings.defaults(), Session.Access.prompter(tui.prompter()),
                (bus, turns) -> replay[0] = ReplayProvider.ofReplies(List.of(
                        "```js\napi.spawnAgent({task: 'count TODOs', background: true});\n"
                                + "for (let i = 0; i < 400 && JSON.parse(api.agents())[0].status === 'running'; i++) { const t = Date.now(); while (Date.now() - t < 50) {} }\n'started'\n```",
                        "Found 3 TODOs.",
                        "Started it.",
                        "sub-1 found 3 TODOs."), bus, turns))) {
            tui.run(session);
        }
        String sc = screen();
        int notice = sc.indexOf("▸ the main agent goes on with the finished subagents' replies (Esc stops it)");
        assertTrue(notice > sc.indexOf("Started it."), sc);
        assertTrue(sc.indexOf("sub-1 found 3 TODOs.") > notice, sc);
        String last = replay[0].requests().getLast().messages().getLast().content();
        assertTrue(last.contains("sub-1 (done after 0 steps): Found 3 TODOs.") && last.endsWith(Session.AUTO_PROMPT), last);
    }

    /** Spec 054 AC-02: the user sits at an empty prompt; a subagent that finishes then wakes it and the main agent goes on. */
    @Test
    void aFinishWakesAnIdlePrompt() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws54d"));
        var keys = new java.io.PipedOutputStream();
        var in = new java.io.PipedInputStream(keys);
        Terminal t = new DumbTerminal("test", "dumb", in, screen, StandardCharsets.UTF_8);
        t.setSize(new org.jline.terminal.Size(200, 50));
        Tui tui = new Tui(t, null);
        try (Session session = Session.open(tmp.resolve("home54d"), ws, Settings.defaults(), Session.Access.prompter(tui.prompter()),
                (bus, turns) -> ReplayProvider.ofReplies(List.of(
                        "```js\napi.spawnAgent({task: 'slow job', background: true});\n"
                                + "for (let i = 0; i < 400 && JSON.parse(api.agents())[0].steps < 1 && JSON.parse(api.agents())[0].status === 'running'; i++) {"
                                + " const t = Date.now(); while (Date.now() - t < 20) {} }\n'started'\n```",
                        "```js\nconst t = Date.now(); while (Date.now() - t < 1500) {}\n'slept'\n```",
                        "Started it.",
                        "The slow job is done.",
                        "It finished."), bus, turns))) {
            Thread run = new Thread(() -> tui.run(session));
            run.start();
            keys.write("go\n".getBytes(StandardCharsets.UTF_8));
            keys.flush();
            long end = System.currentTimeMillis() + 20_000;
            while (!screen().contains("It finished.") && System.currentTimeMillis() < end) {
                Thread.sleep(20);
            }
            keys.write("/exit\n".getBytes(StandardCharsets.UTF_8));
            keys.flush();
            run.join(10_000);
            assertFalse(run.isAlive(), "the TUI ended");
        }
        String sc = screen();
        assertTrue(sc.indexOf("▸ the main agent goes on with the finished subagents' replies") > sc.indexOf("Started it."), sc);
        assertTrue(sc.contains("It finished."), "the woken prompt ran the turn: " + sc);
        assertFalse(sc.contains("[interrupted]"), sc);
    }

    /** Spec 054 AC-03: agent.autoTurns caps the automatic turns in a row; then replies wait for the user's message. */
    @Test
    void automaticTurnsStopAtTheLimit() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws54c"));
        Tui tui = new Tui(terminal("go\nthanks\n"), null);
        String spawn = "```js\napi.spawnAgent({task: 'job', background: true});\n"
                + "for (let i = 0; i < 400 && JSON.parse(api.agents()).some(a => a.status === 'running'); i++) { const t = Date.now(); while (Date.now() - t < 50) {} }\n'started'\n```";
        ReplayProvider[] replay = new ReplayProvider[1];
        try (Session session = Session.open(tmp.resolve("home54c"), ws, Settings.parse("{\"agent\": {\"autoTurns\": 1}}", "t"),
                Session.Access.prompter(tui.prompter()),
                (bus, turns) -> replay[0] = ReplayProvider.ofReplies(List.of(
                        spawn, "first job done", "Started one.",
                        spawn, "second job done", "Started another.",
                        "Both are done."), bus, turns))) {
            tui.run(session);
        }
        String sc = screen();
        assertEquals(1, sc.split("the main agent goes on with", -1).length - 1, "one automatic turn: " + sc);
        assertTrue(sc.contains("▸ the main agent went on by itself 1 time in a row (agent.autoTurns); the next replies wait for your message"), sc);
        assertTrue(sc.contains("Both are done."), sc);
        String last = replay[0].requests().getLast().messages().getLast().content();
        assertTrue(last.contains("sub-2 (done after 0 steps): second job done") && last.endsWith("thanks"), "it came with the message: " + last);
    }

    /**
     * Spec 058 AC-01, AC-02: the prompt starts on the row above the status line. The terminal says the cursor is on row 5
     * of 50, so it moves down 44 rows to row 49; keys typed before that answer still reach the prompt.
     */
    @Test
    void thePromptStartsAtTheBottom() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws58"));
        Tui tui = new Tui(xterm("he\u001b[5;1Rllo\r"), null, java.util.Map.of());
        Path log;
        try (Session session = replaySession(tmp.resolve("home58"), ws, "{}", tui, "Hi.")) {
            log = session.logPath();
            tui.run(session);
        }
        String sc = screen();
        assertTrue(sc.contains("\u001b[6n"), "asked where the cursor is: " + sc.replace("\u001b", "<ESC>"));
        String after = sc.substring(sc.indexOf("\u001b[6n") + 4).replace("\r", "");
        int n = 0;
        while (n < after.length() && after.charAt(n) == '\n') {
            n++;
        }
        assertEquals(44, n, "moved to row 49: " + sc.replace("\u001b", "<ESC>"));
        var started = dev.jharness.events.SessionLog.read(log).stream().filter(e -> e.get("type").asText().equals("turn.started")).toList();
        assertEquals("hello", started.getFirst().get("prompt").asText(), "keys around the answer were kept");
    }

    /** Spec 058 AC-03: a terminal that never answers holds the start up for at most a moment, and the prompt stays put. */
    @Test
    void aTerminalThatDoesntAnswerIsntWaitedFor() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws58b"));
        var keys = new java.io.PipedOutputStream();
        var raw = new org.jline.terminal.Attributes();
        raw.setInputFlag(org.jline.terminal.Attributes.InputFlag.ICRNL, false);
        Terminal t = new ExternalTerminal(null, "test", "xterm-256color", new java.io.PipedInputStream(keys), screen, StandardCharsets.UTF_8,
                Terminal.SignalHandler.SIG_DFL, false, raw, new org.jline.terminal.Size(200, 50));
        Tui tui = new Tui(t, null, java.util.Map.of());
        try (Session session = replaySession(tmp.resolve("home58b"), ws, "{}", tui, "unused")) {
            Thread run = new Thread(() -> tui.run(session));
            long start = System.nanoTime();
            run.start();
            long end = System.currentTimeMillis() + 10_000;
            while (!screen().contains("> ") && System.currentTimeMillis() < end) {
                Thread.sleep(10);
            }
            assertTrue(System.nanoTime() - start < 3_000_000_000L, "the prompt came without an answer");
            keys.write("/exit\r".getBytes(StandardCharsets.UTF_8));
            keys.flush();
            run.join(10_000);
            assertFalse(run.isAlive());
        }
        assertFalse(screen().contains("\r\n\n\n"), "no move without an answer");
    }

    /** Spec 060: a shipped tool missing from ~/.jharness/tools/ is named once at the start, with the command that adds it. */
    @Test
    void missingShippedToolsAreNamedOnce() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws60"));
        Path home = tmp.resolve("home60");
        dev.jharness.process.ToolDefinitions.seed(home);
        Files.delete(dev.jharness.process.ToolDefinitions.userFolder(home).resolve("go.json"));
        Path log;
        for (int i = 0; i < 2; i++) {
            screen.reset();
            Tui tui = new Tui(terminal("/exit\n"), null);
            try (Session session = replaySession(home, ws, "{}", tui, "unused")) {
                log = session.logPath();
                tui.run(session);
            }
            String sc = screen();
            boolean shown = sc.contains("This JHarness ships a tool you don't have in ~/.jharness/tools/: go. Add it with: jharness tools reset go");
            assertEquals(i == 0, shown, "run " + i + ": " + sc);
            if (i == 0) {
                assertTrue(dev.jharness.events.SessionLog.read(log).stream().anyMatch(e -> e.get("type").asText().equals("tools.updates")
                        && e.get("missing").toString().equals("[\"go\"]")));
            }
        }
    }

    /** Spec 056: a folder delete asks in accept-edits mode, then the TUI says what went. */
    @Test
    void aFolderDeleteAsksAndIsShown() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws56"));
        Files.createDirectories(ws.resolve("build"));
        Files.writeString(ws.resolve("build/out.txt"), "x");
        Tui tui = new Tui(terminal("clean\ny\n/exit\n"), null);
        try (Session session = replaySession(tmp.resolve("home56"), ws, "{\"permissions\": {\"mode\": \"accept-edits\"}}", tui,
                "```js\napi.deleteFolder('build')\n```", "Cleaned.")) {
            tui.run(session);
        }
        String sc = screen();
        assertTrue(sc.contains("Allow deleteFolder: delete folder build/ and its 1 file (1 bytes): build/out.txt?"), sc);
        assertTrue(sc.contains("▸ deleted folder build/ (1 file)"), sc);
        assertFalse(Files.exists(ws.resolve("build")));
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
        Tui tui = live("first\u001b\rsecond\nthird\r");
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
        Tui tui = live("a\u001b[27;2;13~b\u001b[13;2uc\u001b[27;5;13~d\r");
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
        assertEquals(List.of("/agent", "/agents", "/answer", "/commands", "/compact", "/context", "/diff", "/events", "/exit", "/follow", "/help", "/hooks", "/mcp",
                "/model", "/network", "/plan", "/rewind", "/stop", "/switch", "/thinking", "/todos", "/undo", "/usage", "/view"), List.copyOf(Tui.COMMANDS.keySet()));
        // Spec 046: the help lists the commands in alphabetical order, then the keys.
        assertEquals(Tui.COMMANDS.keySet().stream().sorted().toList(), List.copyOf(Tui.COMMANDS.keySet()));
        assertTrue(Tui.HELP.indexOf("Keys:") > Tui.HELP.indexOf("/view") && Tui.HELP.indexOf("@PATH") > Tui.HELP.indexOf("Keys:"), Tui.HELP);
        assertEquals("list model profiles", Tui.COMMANDS.get("/model"));
        assertEquals("quit (also /quit, /q, or Ctrl-D)", Tui.COMMANDS.get("/exit"));
    }

    /** Spec 039 AC-02, AC-05: /context shows the count and threshold; /compact summarizes between turns. */
    @Test
    void contextAndCompact() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws39"));
        Tui tui = new Tui(terminal("/context\n/compact\nhello\n/compact\n/context\n/exit\n"), null);
        try (Session session = Session.open(tmp.resolve("home39"), ws, Settings.defaults(), Session.Access.prompter(tui.prompter()),
                (bus, turns) -> ReplayProvider.ofReplies(List.of("Hi there.", "They greeted me."), bus, turns))) {
            tui.run(session);
        }
        String sc = screen();
        assertTrue(sc.contains("Context: about ") && sc.contains("of 32,768 tokens") && sc.contains("compacts at about 26,214"), sc);
        assertTrue(sc.contains("Nothing to compact yet."), sc);
        assertTrue(sc.contains("Compacted the conversation: about "), sc);
        assertTrue(sc.contains("Turns up to 1 are kept as a summary."), sc);
    }

    /** Spec 040 AC-04, AC-06: @path attaches a file and says so; a missing one is named; Tab completes paths. */
    @Test
    void mentionsAttachAndComplete() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws40"));
        Files.writeString(ws.resolve("README.md"), "# Shop\n");
        Tui tui = new Tui(terminal("Explain @README.md and @gone.txt\n/exit\n"), null);
        try (Session session = Session.open(tmp.resolve("home40"), ws, Settings.defaults(), Session.Access.prompter(tui.prompter()),
                (bus, turns) -> ReplayProvider.ofReplies(List.of("It is a shop."), bus, turns))) {
            tui.run(session);
        }
        String sc = screen();
        assertTrue(sc.contains("▸ attached README.md (7 characters)"), sc);
        assertTrue(sc.contains("▸ not attached: @gone.txt: not found"), sc);
        assertTrue(sc.contains("It is a shop."), sc);

        screen.reset();
        Tui tui2 = new Tui(xterm("@REA\t\u0015/exit\r"), null, java.util.Map.of());
        try (Session session = Session.open(tmp.resolve("home41"), ws, Settings.defaults(), Session.Access.prompter(tui2.prompter()),
                (bus, turns) -> ReplayProvider.ofReplies(List.of(), bus, turns))) {
            tui2.run(session);
        }
        assertTrue(screen().contains("README.md"), "Tab completed the path: " + screen());
    }

    /** Spec 041 AC-01: /quit and /q leave like /exit; the help lists /exit alone. */
    @Test
    void quitAliases() throws Exception {
        for (String q : List.of("/quit", "/q")) {
            screen.reset();
            Path ws = Files.createDirectories(tmp.resolve("ws-" + q.substring(1)));
            Tui tui = new Tui(terminal(q + "\nhello\n"), null);
            try (Session session = Session.open(tmp.resolve("home-" + q.substring(1)), ws, Settings.defaults(), Session.Access.prompter(tui.prompter()),
                    (bus, turns) -> ReplayProvider.ofReplies(List.of("never sent"), bus, turns))) {
                tui.run(session);
            }
            assertFalse(screen().contains("never sent") || screen().contains("Unknown command"), q + ": " + screen());
        }
        assertFalse(Tui.COMMANDS.containsKey("/quit") || Tui.COMMANDS.containsKey("/q"));
        assertTrue(Tui.BUILT_IN.containsAll(List.of("/exit", "/quit", "/q", "/help")));
    }

    /** Spec 041 AC-02: a command file named like an alias is not used, and the TUI says so at the start. */
    @Test
    void aCommandFileCantTakeAnAlias() throws Exception {
        Path home = tmp.resolve("home-clash");
        Files.createDirectories(home.resolve(".jharness/commands"));
        Files.writeString(home.resolve(".jharness/commands/q.md"), "Quick task");
        Path ws = Files.createDirectories(tmp.resolve("ws-clash"));
        Tui tui = new Tui(terminal("/q\n"), null);
        try (Session session = Session.open(home, ws, Settings.defaults(), Session.Access.prompter(tui.prompter()),
                (bus, turns) -> ReplayProvider.ofReplies(List.of("never sent"), bus, turns))) {
            tui.run(session);
        }
        assertTrue(screen().contains("Command ~/.jharness/commands/q.md is not used: /q is a built-in command"), screen());
        assertFalse(screen().contains("never sent"), screen());
    }

    /** Spec 041 AC-03 to AC-05: each command's argument choices. */
    @Test
    void argumentChoices() throws Exception {
        Path home = tmp.resolve("home-args");
        Files.createDirectories(home.resolve(".jharness/commands"));
        Files.writeString(home.resolve(".jharness/network.rules"), "allow example.com\ndeny *.ads.example:443\n");
        Files.writeString(home.resolve(".jharness/commands/ship.md"), "---\nargs: patch, minor\n---\nShip $ARGUMENTS");
        Path ws = Files.createDirectories(tmp.resolve("ws-args"));
        Settings st = Settings.parse("{\"models\":{\"local\":{\"provider\":\"openai-compatible\",\"baseUrl\":\"http://127.0.0.1:1/v1\",\"model\":\"q\"},"
                + "\"claude\":{\"provider\":\"anthropic\",\"model\":\"c\"}}}", "t");
        try (Session s = Session.open(home, ws, st, Session.Access.prompter(op -> dev.jharness.security.Prompter.Answer.ONCE),
                (bus, turns) -> ReplayProvider.ofReplies(List.of("one", "two"), bus, turns))) {
            s.turn("a", d -> {});
            s.turn("b", d -> {});
            assertEquals(List.of("none", "steps", "all"), Tui.argChoices("/events", List.of(), s));
            assertEquals(List.of(), Tui.argChoices("/events", List.of("all"), s));
            assertEquals(java.util.Set.of("local", "claude"), java.util.Set.copyOf(Tui.argChoices("/model", List.of(), s)));
            assertEquals(List.of(), Tui.argChoices("/agents", List.of(), s), "no subagents yet");
            assertEquals(List.of("all"), Tui.argChoices("/stop", List.of(), s));
            assertEquals(List.of("main"), Tui.argChoices("/switch", List.of(), s));
            assertEquals(List.of(), Tui.argChoices("/answer", List.of(), s), "no held questions");
            assertEquals(List.of("1", "2"), Tui.argChoices("/rewind", List.of(), s));
            assertEquals(List.of("--force"), Tui.argChoices("/rewind", List.of("1"), s));
            assertEquals(List.of("allow", "deny", "remove", "reload"), Tui.argChoices("/network", List.of(), s));
            assertEquals(List.of("example.com", "*.ads.example:443"), Tui.argChoices("/network", List.of("remove"), s));
            assertEquals(List.of(), Tui.argChoices("/network", List.of("allow"), s), "the host is typed");
            assertEquals(List.of("--project"), Tui.argChoices("/network", List.of("allow", "x.org"), s));
            assertEquals(List.of(), Tui.argChoices("/network", List.of("reload"), s));
            assertEquals(List.of(), Tui.argChoices("/thinking", List.of(), s), "it takes no arguments");
            assertEquals(List.of("patch", "minor"), Tui.argChoices("/ship", List.of(), s));
            assertEquals(List.of(), Tui.argChoices("/ship", List.of("patch"), s));
            assertEquals(List.of(), Tui.argChoices("/nope", List.of(), s));
        }
    }

    /** Spec 041 AC-03: Tab after a command lists its choices; Tab again cycles through them. */
    @Test
    void tabCyclesThroughArgumentChoices() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws-cycle"));
        Tui tui = new Tui(xterm("/events \t\t\t\r\r/exit\r"), null, java.util.Map.of());
        try (Session session = Session.open(tmp.resolve("home-cycle"), ws, Settings.defaults(), Session.Access.prompter(tui.prompter()),
                (bus, turns) -> ReplayProvider.ofReplies(List.of(), bus, turns))) {
            tui.run(session);
        }
        String sc = screen();
        assertTrue(sc.contains("none") && sc.contains("steps") && sc.contains("all"), "listed: " + sc);
        assertTrue(sc.contains("Events: steps"), "the second Tab in the menu picked steps: " + sc);
    }

    /** Spec 042 AC-01, AC-02: the status line shows on real terminals, follows ui.statusLine, and a script can replace it. */
    @Test
    void statusLineOnRealTerminalsOnly() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("shopws"));
        Tui tui = live("hello\r");
        try (Session session = Session.open(tmp.resolve("home-st"), ws, Settings.defaults(), Session.Access.prompter(tui.prompter()),
                (bus, turns) -> ReplayProvider.ofReplies(List.of("Hi."), bus, turns))) {
            tui.run(session);
        }
        String sc = screen();
        assertTrue(sc.contains("ask · turn 1 · shopws"), "the default line after a turn: " + sc);
        assertTrue(sc.contains("\u001b[r") || sc.contains("\u001b[1;50r"), "a scroll region was set and reset: " + sc);

        screen.reset();
        Tui dumb = new Tui(terminal("hello\n/exit\n"), null);
        try (Session session = Session.open(tmp.resolve("home-st2"), ws, Settings.defaults(), Session.Access.prompter(dumb.prompter()),
                (bus, turns) -> ReplayProvider.ofReplies(List.of("Hi."), bus, turns))) {
            dumb.run(session);
        }
        assertFalse(screen().contains("· shopws"), "never on a dumb terminal: " + screen());

        screen.reset();
        Tui off = live("hello\r");
        try (Session session = Session.open(tmp.resolve("home-st3"), ws, Settings.parse("{\"ui\":{\"statusLine\":false}}", "t"),
                Session.Access.prompter(off.prompter()), (bus, turns) -> ReplayProvider.ofReplies(List.of("Hi."), bus, turns))) {
            off.run(session);
        }
        assertFalse(screen().contains("· shopws"), "ui.statusLine false: " + screen());

        screen.reset();
        Path home = tmp.resolve("home-st4");
        Files.createDirectories(home.resolve(".jharness"));
        Path js = Files.writeString(home.resolve(".jharness/statusline.js"), "function status(s) { return 'custom turn ' + s.turn + ' in ' + s.workspace; }");
        Files.setPosixFilePermissions(js, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Tui scripted = live("hello\r");
        try (Session session = Session.open(home, ws, Settings.defaults(), Session.Access.prompter(scripted.prompter()),
                (bus, turns) -> ReplayProvider.ofReplies(List.of("Hi."), bus, turns))) {
            scripted.run(session);
        }
        assertTrue(screen().contains("custom turn 0 in shopws"), "the script's line (JLine redraws only what changes after): " + screen());
    }

    /** Spec 042 AC-03: a broken script falls back to the default line, with an event and one notice. */
    @Test
    void aBrokenStatusScriptFallsBack() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("brokenws"));
        Path home = tmp.resolve("home-sb");
        Files.createDirectories(home.resolve(".jharness"));
        Path js = Files.writeString(home.resolve(".jharness/statusline.js"), "function status(s) { return {not: 'a string'}; }");
        Files.setPosixFilePermissions(js, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Tui tui = live("hello\r");
        Path log;
        try (Session session = Session.open(home, ws, Settings.defaults(), Session.Access.prompter(tui.prompter()),
                (bus, turns) -> ReplayProvider.ofReplies(List.of("Hi."), bus, turns))) {
            tui.run(session);
            log = session.logPath();
        }
        String sc = screen();
        assertTrue(sc.contains("ask · turn 1 · brokenws"), sc);
        assertEquals(1, sc.split("Status line script failed \\(type\\)", -1).length - 1, "one notice: " + sc);
        assertTrue(dev.jharness.events.SessionLog.read(log).stream().anyMatch(e -> e.get("type").asText().equals("statusline.failed")
                && e.get("reason").asText().equals("type")));
    }

    /** Spec 043 AC-05: a resumed session says what it continues, and the model gets the earlier turns. */
    @Test
    void resumeShowsWhereItLeftOff() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws-res"));
        Path home = tmp.resolve("home-res");
        try (Session s = Session.open(home, ws, Settings.defaults(), Session.Access.prompter(op -> dev.jharness.security.Prompter.Answer.ONCE),
                (bus, turns) -> ReplayProvider.ofReplies(List.of("Planned the shop."), bus, turns))) {
            s.turn("plan the shop", d -> {});
        }
        var saved = dev.jharness.agent.SessionState.find(home, ws, null);
        Tui tui = new Tui(terminal("/rewind 0\n/exit\n"), null);
        try (Session session = Session.open(home, ws, Settings.defaults(),
                Session.Access.prompter(tui.prompter()).resume(new dev.jharness.agent.SessionState.Resume(saved, false)),
                (bus, turns) -> ReplayProvider.ofReplies(List.of(), bus, turns))) {
            tui.run(session);
        }
        String sc = screen();
        assertTrue(sc.contains("Resumed " + saved.session() + ": 1 turn, 2 messages; it began with \"plan the shop\"."), sc);
        assertTrue(sc.contains("Last reply: Planned the shop."), sc);
        assertTrue(sc.contains("before the session was resumed"), sc);
    }

    /** Spec 048 AC-05: /view prints the viewer's address, the same one on a second /view. */
    @Test
    void viewPrintsTheAddress() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws-view"));
        Tui tui = new Tui(terminal("/view\n/view\n/exit\n"), null);
        try (Session session = Session.open(tmp.resolve("home-view"), ws, Settings.defaults(), Session.Access.prompter(tui.prompter()),
                (bus, turns) -> ReplayProvider.ofReplies(List.of(), bus, turns))) {
            tui.run(session);
        }
        String sc = screen();
        var m = java.util.regex.Pattern.compile("Open (http://127\\.0\\.0\\.1:\\d+/\\?t=[A-Za-z0-9_-]+)").matcher(sc);
        assertTrue(m.find(), sc);
        String first = m.group(1);
        assertTrue(m.find() && m.group(1).equals(first), "one viewer per session: " + sc);
        assertTrue(sc.contains("Read-only, on this machine only"), sc);
    }

    /** Spec 049 AC-03: /usage adds up this session's requests by agent, with the cost of a priced profile. */
    @Test
    void usagePrintsTokensAndCost() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws-usage"));
        Tui tui = new Tui(terminal("/usage\nhello\n/usage\n/exit\n"), null);
        try (Session session = Session.open(tmp.resolve("home-usage"), ws, Settings.defaults(), Session.Access.prompter(tui.prompter()),
                (bus, turns) -> new ReplayProvider(List.of(UsageTest.reply("Hi.", 1_000_000, 100_000, 400_000)), bus, turns).price(UsageTest.PRICE))) {
            tui.run(session);
        }
        String sc = screen();
        assertTrue(sc.contains("No model requests yet."), sc);
        assertTrue(sc.contains("AGENT  REQUESTS  INPUT      OUTPUT   CACHE-READ  CACHE-WRITE  COST"), sc);
        assertTrue(sc.contains("main   1         1,000,000  100,000  400,000     0            $3.42"), sc);
        assertTrue(sc.contains("INPUT is the whole prompt, cached tokens included."), sc);
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
        assertTrue(sc.contains("vents") && sc.contains("(show the event level and the session log file)") && sc.contains("(quit (also /quit, /q, or Ctrl-D))"),
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
