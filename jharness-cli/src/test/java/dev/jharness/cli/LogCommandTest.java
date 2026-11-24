package dev.jharness.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.events.Json;
import dev.jharness.events.SessionLog;

/** Spec 021: {@code jharness log} lists sessions and prints one, with nothing reaching the terminal as a control sequence. */
class LogCommandTest {

    @TempDir
    Path tmp;
    Path home;
    Path ws;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws Exception {
        home = Files.createDirectories(tmp.resolve("home"));
        ws = Files.createDirectories(tmp.resolve("ws")).toRealPath();
        Files.writeString(ws.resolve("a.txt"), "hello file");
    }

    private int run(String... args) {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        Main m = new Main();
        m.out = new PrintStream(out, true, StandardCharsets.UTF_8);
        m.err = new PrintStream(err, true, StandardCharsets.UTF_8);
        return Main.run(m, args);
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private int log(String... args) {
        String[] all = new String[args.length + 5];
        all[0] = "log";
        all[1] = "--home";
        all[2] = home.toString();
        all[3] = "--cwd";
        all[4] = ws.toString();
        System.arraycopy(args, 0, all, 5, args.length);
        return run(all);
    }

    /** A real headless session with a recorded reply. */
    private void session(String prompt) throws Exception {
        Path r = tmp.resolve("r.jsonl");
        Files.writeString(r, Json.write(Json.object().put("reply", "```js\napi.readFile('a.txt')\n```")) + "\n"
                + Json.write(Json.object().put("reply", "It says hello.")) + "\n");
        assertEquals(0, run("--home", home.toString(), "--cwd", ws.toString(), "--replay", r.toString(), "-p", prompt));
    }

    /** A log written by hand, under this workspace's folder. */
    private Path handLog(String id, String... lines) throws Exception {
        Path f = SessionLog.pathFor(home, ws, id);
        Files.createDirectories(f.getParent());
        Files.writeString(f, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return f;
    }

    private static String event(String ts, int turn, String type, String bodyJson) {
        var o = Json.object().put("id", "e").put("ts", ts).put("session", "s").put("agent", "main").put("turn", turn)
                .put("source", "host").put("type", type);
        o.setAll((com.fasterxml.jackson.databind.node.ObjectNode) Json.parse(bodyJson));
        return Json.write(o);
    }

    @Test
    void listsSessionsNewestFirst() throws Exception {
        assertEquals(0, log());
        assertTrue(out().contains("No sessions for " + ws), out());
        session("first");
        Thread.sleep(5);
        session("second");
        assertEquals(0, log());
        String o = out();
        assertTrue(o.contains("ID") && o.contains("STARTED") && o.contains("TURNS"), o);
        var ids = dev.jharness.events.SessionLogs.project(home, ws).stream().map(e -> e.id()).toList();
        assertEquals(2, ids.size());
        assertTrue(o.indexOf(ids.get(0)) < o.indexOf(ids.get(1)), "newest first: " + o);
        assertTrue(o.contains("replay/"), "the model column: " + o);

        assertEquals(0, log("--limit", "1"));
        assertTrue(out().contains("1 older"), out());
        assertFalse(out().contains(ids.get(1)), out());

        assertEquals(0, log("--all"));
        assertTrue(out().contains("WORKSPACE") && out().contains(ws.toString()), out());
        assertEquals(2, log("--limit", "0"));
    }

    @Test
    void showsOneSessionAsATimeline() throws Exception {
        session("read a.txt");
        assertEquals(0, log("last"));
        String o = out();
        assertTrue(o.contains("Session log "), o);
        assertTrue(o.contains("turn.started  prompt=read a.txt"), o);
        assertTrue(o.contains("loop.result  step=1 status=ok"), o);
        assertTrue(o.contains("model.completed  stopReason="), o);
        assertTrue(o.lines().anyMatch(l -> l.startsWith("── ")), "a date line: " + o);

        assertEquals(0, log("last", "--type", "turn."));
        assertTrue(out().contains("turn.started") && out().contains("turn.completed"), out());
        assertFalse(out().contains("loop.result"), out());

        assertEquals(0, log("last", "--json", "--type", "session.started"));
        List<String> lines = out().lines().toList();
        assertEquals(1, lines.size(), out());
        assertEquals("session.started", Json.parse(lines.getFirst()).get("type").asText());

        String id = dev.jharness.events.SessionLogs.project(home, ws).getFirst().id();
        assertEquals(0, log(id.substring(0, id.length() - 4)));
        assertTrue(out().contains("turn.started"), "a unique prefix");
        Path file = dev.jharness.events.SessionLogs.project(home, ws).getFirst().file();
        assertEquals(0, run("log", file.toString()));
        assertTrue(out().contains("turn.started"), "a path");
    }

    @Test
    void cutsLongFieldsUnlessFull() throws Exception {
        String long1 = "x".repeat(300);
        handLog("s-1-aaaaaaaaaaaa", event("2026-11-23T10:00:00Z", 1, "turn.started", "{\"prompt\":\"" + long1 + "\"}"),
                event("2026-11-23T10:00:01Z", 1, "custom.thing", "{\"n\":1}"));
        assertEquals(0, log("s-1-aaaaaaaaaaaa"));
        assertTrue(out().contains("x".repeat(LogFormat.CUT) + "…"), out());
        assertFalse(out().contains(long1), out());
        assertTrue(out().contains("custom.thing  {\"n\":1}"), "unknown types show their body: " + out());
        assertEquals(0, log("s-1-aaaaaaaaaaaa", "--full"));
        assertTrue(out().contains(long1), out());
    }

    @Test
    void controlCharactersAreEscaped() throws Exception {
        String hostile = "ok\u001b]0;pwned\u0007\u009b2J\u007f‮moc.txt\nnext";
        String line = Json.write(Json.object().put("id", "e").put("ts", "2026-11-23T10:00:00Z").put("session", "s").put("agent", "main")
                .put("turn", 1).put("source", "host").put("type", "model.completed").put("provider", "p").put("model", "m")
                .put("stopReason", "end").put("text", hostile));
        handLog("s-2-bbbbbbbbbbbb", line);
        for (String[] args : List.of(new String[] {"s-2-bbbbbbbbbbbb"}, new String[] {"s-2-bbbbbbbbbbbb", "--full"},
                new String[] {"s-2-bbbbbbbbbbbb", "--json"})) {
            assertEquals(0, log(args));
            String o = out();
            for (char c : o.toCharArray()) {
                assertFalse(TerminalSafe.control(c) && c != '\n' || TerminalSafe.bidi(c),
                        "raw character " + (int) c + " in " + String.join(" ", args) + ": " + o);
            }
        }
        assertEquals(0, log("s-2-bbbbbbbbbbbb"));
        assertTrue(out().contains("ok\\e]0;pwned\\x07\\x9b2J\\x7f\\u202emoc.txt\\nnext"), out());
        assertEquals(0, log("s-2-bbbbbbbbbbbb", "--json"));
        assertEquals(hostile, Json.parse(out().strip()).get("text").asText(), "--json stays valid JSON with the same text");
    }

    /** Spec 027 AC-09. */
    @Test
    void filtersByAgentAndSource() throws Exception {
        String sub = event("2026-11-23T10:00:01Z", 1, "loop.code", "{\"step\":1,\"lines\":3}").replace("\"agent\":\"main\"", "\"agent\":\"sub-1\"");
        String guest = event("2026-11-23T10:00:02Z", 1, "agent.note", "{\"msg\":\"from js\"}").replace("\"source\":\"host\"", "\"source\":\"guest\"");
        handLog("s-4-aaaaaaaaaaaa", event("2026-11-23T10:00:00Z", 1, "turn.started", "{\"prompt\":\"go\"}"), sub, guest);
        assertEquals(0, log("s-4-aaaaaaaaaaaa", "--agent", "sub-1"));
        assertTrue(out().contains("[sub-1]  loop.code") && !out().contains("turn.started") && !out().contains("agent.note"), out());
        assertEquals(0, log("s-4-aaaaaaaaaaaa", "--agent", "main", "--agent", "sub-1"));
        assertTrue(out().contains("loop.code") && out().contains("turn.started"), out());
        assertEquals(0, log("s-4-aaaaaaaaaaaa", "--source", "guest"));
        assertTrue(out().contains("agent.note") && !out().contains("loop.code") && !out().contains("turn.started"), out());
        assertEquals(0, log("s-4-aaaaaaaaaaaa", "--source", "host", "--agent", "main"));
        assertTrue(out().contains("turn.started") && !out().contains("loop.code") && !out().contains("agent.note"), out());
        assertEquals(2, log("s-4-aaaaaaaaaaaa", "--source", "model"));
        assertTrue(err.toString().contains("--source must be host or guest"), err.toString());
    }

    @Test
    void badArgumentsAndLines() throws Exception {
        handLog("s-3-aaaaaaaaaaaa", event("2026-11-23T10:00:00Z", 0, "session.started", "{\"workspace\":\"w\"}"), "{not json",
                event("2026-11-23T10:00:02Z", 1, "turn.started", "{\"prompt\":\"after\"}"));
        handLog("s-3-abbbbbbbbbbb", event("2026-11-23T10:00:00Z", 0, "session.started", "{\"workspace\":\"w\"}"));
        assertEquals(0, log("s-3-aa"));
        assertTrue(out().contains("line 2: not JSON") && out().contains("prompt=after"), out());

        assertEquals(2, log("s-3-a"));
        assertTrue(err.toString().contains("matches 2 sessions") && err.toString().contains("s-3-abbbbbbbbbbb"), err.toString());
        assertEquals(2, log("s-9"));
        assertTrue(err.toString().contains("no session s-9"), err.toString());
        assertEquals(2, log("a;b"));
        assertTrue(err.toString().contains("not a session id"), err.toString());
        assertEquals(2, log("..%2f"));
        assertEquals(2, log("../../etc/passwd"));
        assertTrue(err.toString().contains("no such file"), err.toString());
        assertEquals(2, log("x".repeat(65)));
    }

    @Test
    void helpNamesTheCommand() {
        assertEquals(0, run("--help"));
        assertTrue(out().contains("log"), out());
        assertEquals(0, run("log", "--help"));
        assertTrue(out().contains("--type") && out().contains("SESSION"), out());
    }
}
