package dev.jharness.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.jline.terminal.Terminal;
import org.jline.terminal.impl.ExternalTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import dev.jharness.agent.Session;
import dev.jharness.config.Settings;
import dev.jharness.events.SessionLog;
import dev.jharness.llm.ReplayProvider;

/** Spec 055: on a real terminal the main prompt reads while the main agent works. */
@org.junit.jupiter.api.Timeout(value = 40, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
class TuiLiveTest {

    @TempDir
    Path tmp;
    final ByteArrayOutputStream screen = new ByteArrayOutputStream();
    final Tui[] tui = new Tui[1];

    /** A turn that takes about 1.5 s, then its reply. */
    static final String SLOW = "```js\nconst t = Date.now(); while (Date.now() - t < 1500) {}\n'slept'\n```";

    private Tui live(Object... keys) {
        var raw = new org.jline.terminal.Attributes();
        raw.setInputFlag(org.jline.terminal.Attributes.InputFlag.ICRNL, false);
        try {
            Terminal t = new ExternalTerminal(null, "test", "xterm-256color", new Keys(keys), screen, StandardCharsets.UTF_8,
                    Terminal.SignalHandler.SIG_DFL, false, raw, new org.jline.terminal.Size(200, 50));
            tui[0] = new Tui(t, null, java.util.Map.of());
            return tui[0];
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private String screen() {
        return screen.toString(StandardCharsets.UTF_8);
    }

    private BooleanSupplier shows(String text) {
        return () -> screen().contains(text);
    }

    private BooleanSupplier settled() {
        return Keys.settled(() -> tui[0]);
    }

    private BooleanSupplier running() {
        return () -> tui[0] != null && !tui[0].idle();
    }

    private ReplayProvider[] replay = new ReplayProvider[1];

    private Session open(String name, String settings, String... replies) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws-" + name));
        return Session.open(tmp.resolve("home-" + name), ws, Settings.parse(settings, "t"), Session.Access.prompter(tui[0].prompter()),
                (bus, turns) -> replay[0] = ReplayProvider.ofReplies(List.of(replies), bus, turns));
    }

    private static List<JsonNode> started(Path log) throws Exception {
        return SessionLog.read(log).stream().filter(e -> e.get("type").asText().equals("turn.started") && e.get("agent").asText().equals("main")).toList();
    }

    /** AC-01, AC-02: a message typed during a turn is queued and goes next; the prompt read it while the turn ran. */
    @Test
    void aMessageDuringATurnIsQueued() throws Exception {
        live("first\r", running(), "second\r", "third\r", shows("Third done."), settled(), "/exit\r");
        Path log;
        try (Session s = open("queue", "{}", SLOW, "First done.", "Second and third done.", "Third done.")) {
            log = s.logPath();
            tui[0].run(s);
        }
        String sc = screen();
        assertTrue(sc.contains("▸ queued: second (it goes to the main agent when this turn ends; Esc stops the turn)"), sc);
        assertTrue(sc.contains("▸ queued: third"), sc);
        assertTrue(sc.indexOf("▸ queued: second") < sc.indexOf("First done."), "queued while the first turn ran: " + sc);
        var turns = started(log);
        assertEquals(2, turns.size(), turns.toString());
        assertEquals("first", turns.get(0).get("prompt").asText());
        assertEquals("second\n\nthird", turns.get(1).get("prompt").asText(), "the queue went as one message");
        assertTrue(sc.contains("Second and third done."), sc);
    }

    /** AC-03: Esc on the prompt stops the turn, and a queued message goes next. */
    @Test
    void escStopsTheTurnAndTheQueueGoesNext() throws Exception {
        live("go\r", shows("running 2 lines"), "instead do this\r", shows("▸ queued"), "\u001b", shows("Done instead."), settled(), "/exit\r");
        Path log;
        try (Session s = open("esc", "{}", SLOW, "Done instead.")) {
            log = s.logPath();
            tui[0].run(s);
        }
        String sc = screen();
        assertTrue(sc.contains("[interrupted]"), sc);
        assertTrue(sc.indexOf("Done instead.") > sc.indexOf("[interrupted]"), sc);
        var turns = started(log);
        assertEquals(List.of("go", "instead do this"), turns.stream().map(t -> t.get("prompt").asText()).toList());
    }

    /** AC-04: the main agent's question is a notice; /answer main asks it; the turn goes on. */
    @Test
    void answerMainAsksAHeldQuestion() throws Exception {
        live("write it\r", Keys.held(() -> tui[0]), "/help\r", "/answer main\r", Keys.pause(300), "y\r",
                shows("Written."), settled(), "/exit\r");
        Path ws;
        Path log;
        try (Session s = open("answer", "{}", "```js\napi.writeFile('a.txt', 'new')\n```", "Written.")) {
            ws = s.workspace().root();
            log = s.logPath();
            tui[0].run(s);
        }
        String sc = screen();
        assertTrue(sc.contains("▸ the main agent is waiting for you: write a.txt (3 bytes) (/answer main to answer; Esc stops the turn)"), sc);
        assertTrue(sc.contains("Commands:"), "other commands work meanwhile: " + sc);
        assertTrue(sc.contains("The main agent asks:"), sc);
        assertTrue(sc.contains("Allow writeFile: write a.txt (3 bytes)?"), sc);
        assertTrue(sc.indexOf("Allow writeFile") > sc.indexOf("/answer main"), "asked only after /answer: " + sc);
        assertEquals("new", Files.readString(ws.resolve("a.txt")));
        assertTrue(sc.contains("Back to the main chat."), sc);
        var events = SessionLog.read(log);
        assertTrue(events.stream().anyMatch(e -> e.get("type").asText().equals("question.waiting") && e.get("api").asText().equals("writeFile")));
        assertTrue(events.stream().anyMatch(e -> e.get("type").asText().equals("question.wait_ended") && e.get("outcome").asText().equals("asked")));
    }

    /** AC-04: Esc denies a held question and stops the turn. */
    @Test
    void escDeniesAHeldQuestion() throws Exception {
        live("write it\r", Keys.held(() -> tui[0]), "\u001b", settled(), "/exit\r");
        Path ws;
        Path log;
        try (Session s = open("deny", "{}", "```js\napi.writeFile('a.txt', 'new')\n```", "Not written.")) {
            ws = s.workspace().root();
            log = s.logPath();
            tui[0].run(s);
        }
        String sc = screen();
        assertTrue(sc.contains("▸ that question was denied"), sc);
        assertFalse(Files.exists(ws.resolve("a.txt")));
        assertFalse(sc.contains("Allow writeFile"), "never asked: " + sc);
        assertTrue(SessionLog.read(log).stream().anyMatch(e -> e.get("type").asText().equals("question.wait_ended")
                && e.get("outcome").asText().equals("stopped")));
    }

    /** AC-05: commands that change the conversation wait; others work; /exit stops the turn and quits. */
    @Test
    void commandsDuringATurn() throws Exception {
        live("go\r", running(), "/undo\r", "/clear\r", "/model other\r", "/switch sub-1\r", "/usage\r", shows("/switch waits"), "/exit\r");
        long start = System.nanoTime();
        try (Session s = open("cmds", "{}", "```js\nconst t = Date.now(); while (Date.now() - t < 20000) {}\n'slept'\n```", "unused")) {
            tui[0].run(s);
        }
        assertTrue(System.nanoTime() - start < 15_000_000_000L, "/exit didn't wait for the 20 s turn");
        String sc = screen();
        assertTrue(sc.contains("/undo waits until the main agent's turn ends (Esc stops it)."), sc);
        assertTrue(sc.contains("/model waits until"), sc);
        assertTrue(sc.contains("/clear waits until the main agent's turn ends (Esc stops it)."), sc);
        assertTrue(sc.contains("/switch waits until"), sc);
        assertTrue(sc.contains("Usage this session:") || sc.contains("No model requests yet."), "/usage works meanwhile: " + sc);
        assertTrue(sc.contains("Stopping the main agent's turn."), sc);
    }

    /** AC-01 with spec 054: a finished background subagent starts a main turn while the prompt keeps reading. */
    @Test
    void anAutomaticTurnRunsBehindTheLivePrompt() throws Exception {
        live("go\r", shows("All three done."), settled(), "/exit\r");
        try (Session s = open("auto", "{}",
                "```js\napi.spawnAgent({task: 'count TODOs', background: true});\n"
                        + "for (let i = 0; i < 400 && JSON.parse(api.agents())[0].status === 'running'; i++) { const t = Date.now(); while (Date.now() - t < 50) {} }\n'started'\n```",
                "Found 3 TODOs.", "Started it.", "All three done.")) {
            tui[0].run(s);
        }
        String sc = screen();
        assertTrue(sc.contains("▸ the main agent goes on with the finished subagents' replies (Esc stops it)"), sc);
        assertTrue(sc.contains("All three done."), sc);
    }
}
