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

    @Test
    void settingsErrorsExitTwo() throws Exception {
        assertEquals(2, run("--home", home.toString(), "--cwd", ws.toString(), "-p", "x"));
        assertTrue(err.toString().contains("no model selected"), err.toString());
        Files.createDirectories(home.resolve(".jharness"));
        Files.writeString(home.resolve(".jharness/settings.json"), "{\"models\":{\"a\":{\"provider\":\"anthropic\",\"model\":\"m\"}}}");
        assertEquals(2, run("--home", home.toString(), "--cwd", ws.toString(), "--model", "b", "-p", "x"));
        assertTrue(err.toString().contains("known: a"), err.toString());
        Files.writeString(home.resolve(".jharness/settings.json"), "{\"bogus\":1}");
        assertEquals(2, run("--home", home.toString(), "-p", "x"));
        assertEquals(2, run("--no-such-option"));
    }
}
