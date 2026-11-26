package dev.jharness.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.events.Json;
import dev.jharness.events.SessionLog;
import dev.jharness.process.ToolDefinitions;
import dev.jharness.security.TrustStore;

/** Spec 028: project tools in the trust question, confirming changed ones, headless runs and {@code jharness tools}. */
class ToolsFlowTest {

    @TempDir
    Path tmp;
    Path home;
    Path ws;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    final ByteArrayOutputStream screen = new ByteArrayOutputStream();

    static final String GO = "{\"executable\":\"go\",\"proxy\":\"env\",\"operations\":{\"build\":{\"args\":[\"build\",\"./...\"]},\"vet\":{\"args\":[\"vet\"]}}}";

    @BeforeEach
    void setUp() throws Exception {
        home = Files.createDirectories(tmp.resolve("home"));
        ws = Files.createDirectories(tmp.resolve("ws")).toRealPath();
        Path tools = Files.createDirectories(ws.resolve(".jharness/tools"));
        Files.writeString(tools.resolve("go.json"), GO);
    }

    private int run(Terminal t, String... args) {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        Main m = new Main();
        m.out = new PrintStream(out, true, StandardCharsets.UTF_8);
        m.err = new PrintStream(err, true, StandardCharsets.UTF_8);
        m.terminal = t;
        return Main.run(m, args);
    }

    private Terminal terminal(String input) throws Exception {
        screen.reset();
        Terminal t = new DumbTerminal("test", "dumb", new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), screen, StandardCharsets.UTF_8);
        t.setSize(new org.jline.terminal.Size(200, 50));
        return t;
    }

    private String screen() {
        return screen.toString(StandardCharsets.UTF_8);
    }

    private Path replay(String... replies) throws Exception {
        Path f = tmp.resolve("r.jsonl");
        StringBuilder sb = new StringBuilder();
        for (String r : replies) {
            sb.append(Json.write(Json.object().put("reply", r))).append('\n');
        }
        Files.writeString(f, sb);
        return f;
    }

    private List<String> toolsInLastSession() throws Exception {
        var logs = dev.jharness.events.SessionLogs.project(home, ws);
        var events = SessionLog.read(logs.getFirst().file());
        var loaded = events.stream().filter(e -> e.get("type").asText().equals("tools.loaded")).findFirst().orElseThrow();
        List<String> names = new java.util.ArrayList<>();
        loaded.get("tools").forEach(x -> names.add(x.get("name").asText() + ":" + x.get("source").asText()));
        return names;
    }

    @Test
    void theTrustQuestionListsTheProjectsToolsAndRecordsThem() throws Exception {
        assertEquals(0, run(terminal("y\n/exit\n"), "--home", home.toString(), "--cwd", ws.toString(), "--replay", replay().toString()));
        String sc = screen();
        assertTrue(sc.contains("It also lets JHarness run the tools the project defines in .jharness/tools/:"), sc);
        assertTrue(sc.contains("  go: runs go (build, vet)"), sc);
        assertTrue(sc.contains("Project tools: go: runs go (build, vet)"), "the banner names them: " + sc);
        assertEquals(ToolDefinitions.projectHashes(ws), TrustStore.load(home).confirmedTools(ws));
        assertTrue(toolsInLastSession().contains("go:project"), toolsInLastSession().toString());
        assertTrue(Files.isRegularFile(home.resolve(".jharness/tools/maven.json")), "the shipped tools were seeded");
    }

    @Test
    void changedProjectToolsAskAgain() throws Exception {
        TrustStore.load(home).record(ws, true, ToolDefinitions.projectHashes(ws));
        Files.writeString(ws.resolve(".jharness/tools/go.json"), GO.replace("\"vet\"", "\"lint\""));
        Files.writeString(ws.resolve(".jharness/tools/rg2.json"), GO.replace("\"go\"", "\"rg\""));
        assertEquals(0, run(terminal("n\n/exit\n"), "--home", home.toString(), "--cwd", ws.toString(), "--replay", replay().toString()));
        String sc = screen();
        assertTrue(sc.contains("This project's tools are new or changed since you last confirmed them"), sc);
        assertTrue(sc.contains("  go: runs go (build, lint)") && sc.contains("  rg2: runs rg (build, vet)"), sc);
        assertFalse(toolsInLastSession().stream().anyMatch(t -> t.endsWith(":project")), "no: only the user's tools");
        assertNotEquals(ToolDefinitions.projectHashes(ws), TrustStore.load(home).confirmedTools(ws), "not confirmed");

        assertEquals(0, run(terminal("y\n/exit\n"), "--home", home.toString(), "--cwd", ws.toString(), "--replay", replay().toString()));
        assertTrue(toolsInLastSession().contains("go:project"));
        assertEquals(ToolDefinitions.projectHashes(ws), TrustStore.load(home).confirmedTools(ws), "confirmed");

        assertEquals(0, run(terminal("/exit\n"), "--home", home.toString(), "--cwd", ws.toString(), "--replay", replay().toString()));
        assertFalse(screen().contains("new or changed"), "unchanged: no question");
    }

    @Test
    void headlessRunsLeaveOutUnconfirmedProjectTools() throws Exception {
        TrustStore.load(home).record(ws, true, java.util.Map.of());
        Path r = replay("```js\nJSON.parse(api.tools()).map(t => t.name + ':' + t.source).join(',')\n```", "done");
        assertEquals(0, run(null, "--home", home.toString(), "--cwd", ws.toString(), "--replay", r.toString(), "-p", "tools"), err.toString());
        assertTrue(err.toString().contains("the project's tools go are new or changed since you confirmed them; they are left out"), err.toString());
        assertEquals(List.of("go:user", "gofmt:user", "gradle:user", "maven:user", "npm:user"), toolsInLastSession());
        TrustStore.load(home).confirmTools(ws, ToolDefinitions.projectHashes(ws));
        assertEquals(0, run(null, "--home", home.toString(), "--cwd", ws.toString(), "--replay", r.toString(), "-p", "tools"));
        assertEquals("", err.toString());
        assertTrue(toolsInLastSession().contains("go:project"));
    }

    /** Spec 045: the header, then rows whose columns start where the header's do. */
    private static void assertTableLines(String o) {
        List<String> lines = o.lines().toList();
        int h = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("NAME ")) {
                h = i;
                break;
            }
        }
        assertTrue(h >= 0, "a header: " + o);
        String header = lines.get(h);
        assertTrue(header.matches("NAME +SOURCE +EXECUTABLE +STATUS +OPERATIONS"), header);
        int[] starts = {0, header.indexOf("SOURCE"), header.indexOf("EXECUTABLE"), header.indexOf("STATUS"), header.indexOf("OPERATIONS")};
        int rows = 0;
        for (int i = h + 1; i < lines.size() && !lines.get(i).isEmpty() && Character.isLowerCase(lines.get(i).charAt(0))
                && lines.get(i).length() > starts[4]; i++) {
            String l = lines.get(i);
            for (int c = 1; c < starts.length; c++) {
                assertEquals(' ', l.charAt(starts[c] - 1), "column " + c + " starts after a space: " + l);
                assertNotEquals(' ', l.charAt(starts[c]), "column " + c + " starts at the header's column: " + l);
            }
            rows++;
        }
        assertTrue(rows >= 3, "rows for the shipped tools: " + o);
    }

    @Test
    void textTableColumnsAreAsWideAsTheirLongestValue() {
        var t = new TextTable("NAME", "STATUS", "OPERATIONS").row("go", "not found on PATH", "build, test").row("maven", "ok", "compile")
                .row("e\u001bvil", "ok", "x");
        assertEquals(List.of(
                "NAME    STATUS             OPERATIONS",
                "go      not found on PATH  build, test",
                "maven   ok                 compile",
                "e\\evil  ok                 x"), t.lines(), "the escaped value, 6 characters, sets the width");
    }

    @Test
    void toolsCommand() throws Exception {
        assertEquals(0, run(null, "tools", "--home", home.toString(), "--cwd", ws.toString()));
        String o = out.toString();
        assertTrue(o.contains("Wrote the shipped tools to"), o);
        assertTableLines(o);
        assertTrue(o.lines().anyMatch(l -> l.matches("maven +user +mvn +(ok|not found on PATH) +compile, test, verify, package, install, clean")), o);
        assertTrue(o.contains("they count once you trust the project"), o);

        assertEquals(0, run(null, "tools", "show", "maven", "--home", home.toString(), "--cwd", ws.toString()));
        assertTrue(out.toString().contains("\"executable\": \"mvn\""), out.toString());
        assertEquals(2, run(null, "tools", "show", "nope", "--home", home.toString(), "--cwd", ws.toString()));
        assertTrue(err.toString().contains("no tool nope"), err.toString());

        Path npm = home.resolve(".jharness/tools/npm.json");
        Files.writeString(npm, "{\"executable\":\"bash\",\"proxy\":\"none\",\"operations\":{\"x\":{\"args\":[\"x\"]}}}");
        Files.writeString(ws.resolve(".jharness/tools/bad.json"), "{");
        assertEquals(1, run(null, "tools", "check", "--home", home.toString(), "--cwd", ws.toString()));
        assertTrue(out.toString().contains("shells and command runners") && out.toString().contains("bad.json: not valid JSON"), out.toString());
        assertTrue(out.toString().contains("2 tool file(s) have problems."), out.toString());
        assertEquals(0, run(null, "tools", "list", "--home", home.toString(), "--cwd", ws.toString()));
        assertTrue(out.toString().lines().anyMatch(l -> l.matches("npm +broken")), out.toString());
        assertTrue(out.toString().contains("npm is broken: "), out.toString());

        assertEquals(0, run(null, "tools", "reset", "npm", "--home", home.toString(), "--cwd", ws.toString()));
        assertEquals(ToolDefinitions.defaultText("npm"), Files.readString(npm));
        assertEquals(2, run(null, "tools", "reset", "cargo", "--home", home.toString(), "--cwd", ws.toString()));
        assertTrue(err.toString().contains("cargo is not a shipped tool"), err.toString());

        Files.writeString(home.resolve(".jharness/tools/maven.json"), ToolDefinitions.defaultText("maven").replace("\"clean\":", "\"cleanup\":"));
        Files.delete(ws.resolve(".jharness/tools/bad.json"));
        TrustStore.load(home).record(ws, true, ToolDefinitions.projectHashes(ws));
        assertEquals(0, run(null, "tools", "--home", home.toString(), "--cwd", ws.toString()));
        assertTrue(out.toString().contains("Differs from the shipped default: maven (jharness tools reset NAME writes it again)."), out.toString());
        assertTrue(out.toString().lines().anyMatch(l -> l.matches("go +project +go +(ok|not found on PATH) +.+")), out.toString());
        assertEquals(2, run(null, "tools", "frob", "--home", home.toString(), "--cwd", ws.toString()));
    }
}
