package dev.jharness.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    @TempDir
    Path tmp;
    Path home;
    Path ws;
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws Exception {
        home = Files.createDirectories(tmp.resolve("home"));
        ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("a.txt"), "hello file");
    }

    private int run(String... args) {
        Main m = new Main();
        m.out = new PrintStream(out, true);
        m.err = new PrintStream(err, true);
        return Main.run(m, args);
    }

    private Path replay(String... replies) throws Exception {
        Path f = tmp.resolve("r.jsonl");
        StringBuilder sb = new StringBuilder();
        for (String r : replies) {
            sb.append(dev.jharness.events.Json.write(dev.jharness.events.Json.object().put("reply", r))).append('\n');
        }
        Files.writeString(f, sb);
        return f;
    }

    @Test
    void versionAndHelp() {
        assertEquals(0, run("--version"));
        assertTrue(out.toString().contains("jharness 0.1.0"));
        assertEquals(0, run("--help"));
        assertTrue(out.toString().contains("--model"));
        assertFalse(out.toString().contains("--replay"), "test options are hidden");
    }

    /** Spec 043 AC-03, AC-04: -p with --resume goes on in the saved session; --fork copies it; errors say why. */
    @Test
    void resumeAndForkHeadless() throws Exception {
        assertEquals(2, run("--home", home.toString(), "--cwd", ws.toString(), "--resume", "-p", "go on"));
        assertTrue(err.toString().contains("no session to resume"), err.toString());
        Path r = replay("The number is 7.");
        assertEquals(0, run("--home", home.toString(), "--cwd", ws.toString(), "--replay", r.toString(), "-p", "remember 7"));
        String id = dev.jharness.agent.SessionState.find(home, ws, null).session();
        Path r2 = tmp.resolve("r2.jsonl");
        Files.writeString(r2, "{\"reply\":\"You said 7.\"}\n");
        assertEquals(0, run("--home", home.toString(), "--cwd", ws.toString(), "--replay", r2.toString(), "--resume", "-p", "what number?"));
        assertTrue(out.toString().contains("You said 7."), out.toString());
        var st = dev.jharness.agent.SessionState.find(home, ws, id);
        assertEquals(2, st.turn());
        assertEquals(List.of("remember 7", "The number is 7.", "what number?", "You said 7."),
                st.conversation().stream().map(dev.jharness.llm.ChatMessage::content).toList());
        assertEquals(0, run("--home", home.toString(), "--cwd", ws.toString(), "--replay", r2.toString(), "--fork", id, "-p", "again"));
        var f = dev.jharness.agent.SessionState.find(home, ws, "last");
        assertNotEquals(id, f.session());
        assertEquals(id, f.forkedFrom());
        assertEquals(2, dev.jharness.agent.SessionState.find(home, ws, id).turn(), "the original is unchanged");
        err.reset();
        assertEquals(2, run("--home", home.toString(), "--cwd", ws.toString(), "--resume", "--fork", "-p", "x"));
        assertTrue(err.toString().contains("not both"), err.toString());
        err.reset();
        assertEquals(2, run("--home", home.toString(), "--cwd", ws.toString(), "--resume", "s-nope", "-p", "x"));
        assertTrue(err.toString().contains("no session s-nope"), err.toString());
    }

    @Test
    void headlessRunsOneTurn() throws Exception {
        Path r = replay("```js\napi.readFile('a.txt')\n```", "The file says hello.");
        assertEquals(0, run("--home", home.toString(), "--cwd", ws.toString(), "--replay", r.toString(), "-p", "read a.txt"));
        assertTrue(out.toString().contains("The file says hello."), out.toString());
    }

    @Test
    void headlessDeniesChanges() throws Exception {
        Path r = replay("```js\napi.writeFile('a.txt', 'changed')\n```", "Could not write.");
        assertEquals(0, run("--home", home.toString(), "--cwd", ws.toString(), "--replay", r.toString(), "-p", "change it"));
        assertEquals("hello file", Files.readString(ws.resolve("a.txt")));
    }

    @Test
    void turnErrorExitsOne() throws Exception {
        Path r = replay("```js\n1\n```");
        assertEquals(1, run("--home", home.toString(), "--cwd", ws.toString(), "--replay", r.toString(), "-p", "x"));
        assertTrue(err.toString().contains("no more replies"), err.toString());
    }

    private String sessionLog() throws Exception {
        try (var files = Files.walk(home.resolve(".jharness/sessions"))) {
            Path log = files.filter(f -> f.toString().endsWith(".jsonl")).findFirst().orElseThrow();
            return Files.readString(log);
        }
    }

    @Test
    void untrustedProjectSettingsCantLoosenRules() throws Exception {
        Files.createDirectories(ws.resolve(".jharness"));
        Files.writeString(ws.resolve(".jharness/settings.json"), "{\"permissions\":{\"mode\":\"bypass\"}}");
        Path r = replay("```js\napi.writeFile('a.txt', 'changed')\n```", "done");
        assertEquals(0, run("--home", home.toString(), "--cwd", ws.toString(), "--replay", r.toString(), "-p", "change it"));
        assertEquals("hello file", Files.readString(ws.resolve("a.txt")), "bypass from an untrusted project is ignored");
        String log = sessionLog();
        assertTrue(log.contains("\"type\":\"settings.ignored\"") && log.contains("permissions.mode"), log);
        assertTrue(log.contains("\"trusted\":false"));
    }

    @Test
    void trustedProjectSettingsApply() throws Exception {
        Files.createDirectories(ws.resolve(".jharness"));
        Files.writeString(ws.resolve(".jharness/settings.json"), "{\"permissions\":{\"mode\":\"bypass\"}}");
        dev.jharness.security.TrustStore.load(home).record(ws, true);
        Path r = replay("```js\napi.writeFile('a.txt', 'changed')\n```", "done");
        assertEquals(0, run("--home", home.toString(), "--cwd", ws.toString(), "--replay", r.toString(), "-p", "change it"));
        assertEquals("changed", Files.readString(ws.resolve("a.txt")));
        assertTrue(sessionLog().contains("\"trusted\":true"));
    }

    @Test
    void settingsErrorsExitTwo() throws Exception {
        assertEquals(2, run("--home", home.toString(), "--cwd", ws.toString(), "-p", "x"));
        assertTrue(err.toString().contains("no model selected"), err.toString());
        Files.createDirectories(home.resolve(".jharness"));
        Files.writeString(home.resolve(".jharness/settings.json"), "{\"models\":{\"a\":{\"provider\":\"anthropic\",\"model\":\"m\"}}}");
        assertEquals(2, run("--home", home.toString(), "--cwd", ws.toString(), "--model", "b", "-p", "x"));
        assertTrue(err.toString().contains("\"b\""), err.toString());
        Files.writeString(home.resolve(".jharness/settings.json"), "{\"bogus\":1}");
        assertEquals(2, run("--home", home.toString(), "-p", "x"));
        assertEquals(2, run("--no-such-option"));
    }

    /** Spec 021 AC-01: with -p, --debug writes each logged event to stderr as a JSON line. */
    @Test
    void debugWritesEventsToStderr() throws Exception {
        Path r = replay("```js\napi.readFile('a.txt')\n```", "The file says hello.");
        assertEquals(0, run("--debug", "--home", home.toString(), "--cwd", ws.toString(), "--replay", r.toString(), "-p", "read a.txt"));
        var lines = err.toString().lines().filter(l -> l.startsWith("{")).map(dev.jharness.events.Json::parse).toList();
        var types = lines.stream().map(n -> n.get("type").asText()).toList();
        assertTrue(types.contains("turn.started") && types.contains("loop.result") && types.contains("turn.completed"), types.toString());
        assertFalse(types.contains("model.delta"), "live chunks stay off");
        assertTrue(out.toString().contains("The file says hello."));
    }

    private int runWith(java.util.Map<String, String> env, picocli.CommandLine.Help.Ansi ansi, String... args) {
        Main m = new Main();
        m.out = new PrintStream(out, true);
        m.err = new PrintStream(err, true);
        return Main.run(m, env, ansi, args);
    }

    /** Spec 044 AC-01: with colors on, the help has real ESC sequences, never a literal \e[. */
    @Test
    void helpKeepsItsColors() {
        assertEquals(0, runWith(java.util.Map.of(), picocli.CommandLine.Help.Ansi.ON, "--help"));
        String o = out.toString();
        assertTrue(o.contains("\u001b[") && o.contains("--debug"), o);
        assertFalse(o.contains("\\e["), "not escaped: " + o);
        out.reset();
        assertEquals(0, runWith(java.util.Map.of(), picocli.CommandLine.Help.Ansi.ON, "log", "--help"));
        assertTrue(out.toString().contains("\u001b[") && !out.toString().contains("\\e["), "subcommands too: " + out);
    }

    /** Spec 044 AC-02: NO_COLOR, or no terminal, means no ESC at all. */
    @Test
    void helpHasNoColorsWithNoColorOrWithoutATerminal() {
        assertEquals(0, runWith(java.util.Map.of("NO_COLOR", "1"), null, "--help"));
        assertFalse(out.toString().contains("\u001b"), out.toString());
        assertTrue(out.toString().contains("--debug"));
        out.reset();
        assertEquals(0, runWith(java.util.Map.of(), null, "--help"));
        assertFalse(out.toString().contains("\u001b"), "tests don't run on a terminal: " + out);
        out.reset();
        assertEquals(0, runWith(java.util.Map.of("NO_COLOR", ""), picocli.CommandLine.Help.Ansi.OFF, "--version"));
        assertFalse(out.toString().contains("\u001b"));
        assertEquals(picocli.CommandLine.Help.Ansi.AUTO, Main.helpAnsi(java.util.Map.of("NO_COLOR", ""), null), "an empty NO_COLOR doesn't count");
        assertEquals(picocli.CommandLine.Help.Ansi.OFF, Main.helpAnsi(java.util.Map.of("NO_COLOR", "yes"), null));
    }

    /** Spec 044 AC-03: a usage error echoes what was typed, escaped, even with colors on. */
    @Test
    void usageErrorsEscapeWhatWasTyped() {
        assertEquals(2, runWith(java.util.Map.of(), picocli.CommandLine.Help.Ansi.ON, "--bad\u001b]0;pwned\u0007"));
        String e = err.toString();
        assertTrue(e.contains("--bad\\e]0;pwned\\x07"), e);
        assertFalse(e.contains("\u001b]0") || e.contains("\u0007"), "no raw control characters from the arguments: " + e);
        err.reset();
        assertEquals(2, runWith(java.util.Map.of(), picocli.CommandLine.Help.Ansi.OFF, "--modl", "x"));
        assertFalse(err.toString().contains("\u001b"), err.toString());
        assertTrue(err.toString().contains("--modl"), err.toString());
    }

    /** Spec 044 AC-04: help colors don't change how replies are printed; they stay escaped. */
    @Test
    void repliesStayEscapedWithHelpColorsOn() throws Exception {
        Path r = replay("Hi \u001b[31mred\u001b[0m.");
        assertEquals(0, runWith(java.util.Map.of(), picocli.CommandLine.Help.Ansi.ON, "--home", home.toString(), "--cwd", ws.toString(),
                "--replay", r.toString(), "-p", "x"));
        String o = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(o.contains("Hi \\e[31mred\\e[0m."), o);
        assertFalse(o.contains("\u001b"), o);
    }

    /** Spec 047 AC-02: --output json prints one result object and nothing else on stdout. */
    @Test
    void jsonOutputIsOneResultObject() throws Exception {
        Path r = replay("```js\napi.writeFile('a.txt', 'changed')\n```", "Tried to write.\u001b[31m");
        assertEquals(0, run("--home", home.toString(), "--cwd", ws.toString(), "--replay", r.toString(), "--output", "json", "-p", "change a.txt"));
        String o = out.toString(java.nio.charset.StandardCharsets.UTF_8).strip();
        assertEquals(1, o.lines().count(), "one line: " + o);
        var j = dev.jharness.events.Json.parse(o);
        assertEquals("result", j.get("type").asText());
        assertEquals("done", j.get("stopReason").asText());
        assertEquals("Tried to write.\u001b[31m", j.get("reply").asText(), "the exact reply, escaped only as JSON");
        assertFalse(o.contains("\u001b"), "no raw ESC on stdout");
        assertEquals(1, j.get("steps").asInt());
        assertEquals(0, j.get("exitCode").asInt());
        assertTrue(j.get("session").asText().startsWith("s-"));
        assertEquals(2, j.get("usage").get("requests").asInt());
        assertEquals("writeFile", j.get("denied").get(0).get("api").asText(), "-p can't approve");
        assertEquals(0, j.get("filesChanged").size());
        assertEquals("hello file", Files.readString(ws.resolve("a.txt")));
    }

    /** Spec 054 AC-05: in -p a background subagent's reply starts another turn; the result is the last turn's. */
    @Test
    void headlessDeliversBackgroundReplies() throws Exception {
        String spawn = "```js\napi.spawnAgent({task: 'read a.txt', background: true});\n"
                + "for (let i = 0; i < 400 && JSON.parse(api.agents())[0].status === 'running'; i++) { const t = Date.now(); while (Date.now() - t < 50) {} }\n"
                + "'started'\n```";
        Path r = replay(spawn, "a.txt says hello file.", "Started it.", "The subagent says the file says hello file.");
        assertEquals(0, run("--home", home.toString(), "--cwd", ws.toString(), "--replay", r.toString(), "--output", "json", "-p", "read it"));
        var j = dev.jharness.events.Json.parse(out.toString(java.nio.charset.StandardCharsets.UTF_8).strip());
        assertEquals("The subagent says the file says hello file.", j.get("reply").asText());
        assertEquals("done", j.get("stopReason").asText());
        assertEquals(1, j.get("steps").asInt(), "every turn's steps");
        assertEquals("sub-1", j.get("subagents").get(0).asText());
        // With agent.autoTurns 0 the run ends after the prompt's turn, as before.
        out.reset();
        Files.createDirectories(home.resolve(".jharness"));
        Files.writeString(home.resolve(".jharness/settings.json"), "{\"agent\": {\"autoTurns\": 0}}");
        assertEquals(0, run("--home", home.toString(), "--cwd", ws.toString(), "--replay", r.toString(), "--output", "json", "-p", "read it"));
        assertEquals("Started it.", dev.jharness.events.Json.parse(out.toString(java.nio.charset.StandardCharsets.UTF_8).strip()).get("reply").asText());
    }

    /** Spec 047 AC-03, AC-05: stream-json prints every event, chunks too, then the result; --mode accept-edits lets edits through. */
    @Test
    void streamJsonAndModeFlag() throws Exception {
        Path r = replay("```js\napi.writeFile('a.txt', 'changed')\n```", "Wrote it.");
        assertEquals(0, run("--home", home.toString(), "--cwd", ws.toString(), "--replay", r.toString(), "--output", "stream-json",
                "--mode", "accept-edits", "-p", "change a.txt"));
        List<String> lines = out.toString(java.nio.charset.StandardCharsets.UTF_8).strip().lines().toList();
        List<String> types = lines.stream().map(l -> dev.jharness.events.Json.parse(l).get("type").asText()).toList();
        assertEquals("session.started", types.getFirst());
        assertTrue(types.contains("model.delta") && types.contains("turn.started") && types.contains("file.changed"), types.toString());
        assertEquals("result", types.getLast());
        var res = dev.jharness.events.Json.parse(lines.getLast());
        assertEquals("Wrote it.", res.get("reply").asText());
        assertEquals("a.txt", res.get("filesChanged").get(0).asText());
        assertEquals(0, res.get("denied").size());
        assertEquals("changed", Files.readString(ws.resolve("a.txt")));
        assertTrue(lines.stream().filter(l -> l.contains("\"type\":\"turn.started\"")).allMatch(l -> l.contains("\"session\":\"" + res.get("session").asText())),
                "events carry the session");
    }

    /** Spec 047 AC-04, AC-06: exit codes, and bad flag values. */
    @Test
    void exitCodesAndBadFlags() throws Exception {
        Path loop = replay("```js\n1\n```", "```js\n2\n```", "```js\n3\n```");
        Files.createDirectories(home.resolve(".jharness"));
        Files.writeString(home.resolve(".jharness/settings.json"), "{\"agent\": {\"maxSteps\": 1}}");
        assertEquals(1, run("--home", home.toString(), "--cwd", ws.toString(), "--replay", loop.toString(), "--output", "json", "-p", "loop"));
        var j = dev.jharness.events.Json.parse(out.toString().strip().lines().reduce((a, b) -> b).orElseThrow());
        assertEquals("max_steps", j.get("stopReason").asText());
        assertEquals(1, j.get("exitCode").asInt());
        out.reset();
        assertEquals(1, run("--home", home.toString(), "--cwd", ws.toString(), "--replay", loop.toString(), "-p", "loop"));
        assertTrue(err.toString().contains("stopped: max_steps"), err.toString());
        err.reset();
        assertEquals(2, run("--home", home.toString(), "--cwd", ws.toString(), "--output", "xml", "-p", "x"));
        assertTrue(err.toString().contains("--output must be text, json or stream-json"), err.toString());
        assertEquals(2, run("--home", home.toString(), "--cwd", ws.toString(), "--output", "json"));
        assertTrue(err.toString().contains("--output works with -p"), err.toString());
        assertEquals(2, run("--home", home.toString(), "--cwd", ws.toString(), "--mode", "yolo", "-p", "x"));
        assertTrue(err.toString().contains("--mode must be"), err.toString());
        out.reset();
        Files.writeString(home.resolve(".jharness/settings.json"), "{\"agent\": {\"maxSteps\": 0}}");
        assertEquals(2, run("--home", home.toString(), "--cwd", ws.toString(), "--output", "json", "-p", "x"));
        var bad = dev.jharness.events.Json.parse(out.toString().strip());
        assertEquals("error", bad.get("stopReason").asText());
        assertEquals(2, bad.get("exitCode").asInt());
        assertTrue(bad.get("session").isNull(), "it failed before a session opened");
    }

    /** Spec 022: headless output and errors are escaped too. */
    @Test
    void headlessOutputIsEscaped() throws Exception {
        Path r = replay("Hi \u001b]0;pwned\u0007\u009b2J\u202e.", "```js\nthrow new Error('\\u001b[2J')\n```");
        run("--home", home.toString(), "--cwd", ws.toString(), "--replay", r.toString(), "-p", "x");
        String o = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(o.contains("Hi \\e]0;pwned\\x07\\x9b2J\\u202e."), o);
        assertFalse(o.contains("\u001b") || o.contains("\u009b") || o.contains("\u202e"), o);
    }

    /** Spec 025: --agent runs as a custom agent; unknown agents and agent profiles that don't exist stop start-up. */
    @Test
    void agentOption() throws Exception {
        Path d = Files.createDirectories(home.resolve(".jharness/agents/reader"));
        Files.writeString(d.resolve("agent.json"), "{\"description\":\"only reads\",\"tools\":[\"readFile\"]}");
        Path r = replay("```js\napi.writeFile('a.txt', 'changed')\n```", "Could not write.");
        assertEquals(0, run("--home", home.toString(), "--cwd", ws.toString(), "--replay", r.toString(), "--agent", "reader", "-p", "x"));
        String log = sessionLog();
        assertTrue(log.contains("\"customAgent\":\"reader\"") && log.contains("can't use api.writeFile"), log);
        assertEquals(2, run("--home", home.toString(), "--cwd", ws.toString(), "--replay", r.toString(), "--agent", "nope", "-p", "x"));
        assertTrue(err.toString().contains("unknown agent \"nope\" (agents: reader)"), err.toString());
        Files.writeString(d.resolve("agent.json"), "{\"description\":\"x\",\"model\":\"missing\"}");
        assertEquals(2, run("--home", home.toString(), "--cwd", ws.toString(), "--agent", "reader", "-p", "x"));
        assertTrue(err.toString().contains("\"model\" names \"missing\""), err.toString());
    }

    /** Spec 035: a slash command works in -p. */
    @Test
    void headlessSlashCommand() throws Exception {
        Path c = Files.createDirectories(home.resolve(".jharness/commands"));
        Files.writeString(c.resolve("greet.md"), "Say hello to $ARGUMENTS.");
        Files.writeString(c.resolve("bad.js"), "function run() { throw new Error('broken'); }");
        Path r = replay("Hello!");
        assertEquals(0, run("--home", home.toString(), "--cwd", ws.toString(), "--replay", r.toString(), "-p", "/greet Bob"));
        assertTrue(sessionLog().contains("\"prompt\":\"Say hello to Bob.\""), sessionLog());
        assertEquals(1, run("--home", home.toString(), "--cwd", ws.toString(), "--replay", r.toString(), "-p", "/bad"));
        assertTrue(err.toString().contains("broken"), err.toString());
    }
}
