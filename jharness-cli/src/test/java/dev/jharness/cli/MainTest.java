package dev.jharness.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
