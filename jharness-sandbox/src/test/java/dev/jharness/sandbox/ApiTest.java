package dev.jharness.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.events.Event;
import dev.jharness.events.Json;
import dev.jharness.security.DefaultGate;
import dev.jharness.security.PermissionGate;

class ApiTest {

    @TempDir
    Path tmp;
    Fixture fx;
    AgentIsolate iso;

    @BeforeEach
    void setUp() throws Exception {
        fx = new Fixture(tmp);
        iso = Fixture.isolate(Fixture.profile(Map.of()), fx.bus);
    }

    @AfterEach
    void tearDown() {
        iso.close();
    }

    private RunResult run(PermissionGate gate, String js) {
        return iso.run(js, fx.services(gate));
    }

    private String ok(PermissionGate gate, String js) {
        RunResult r = run(gate, js);
        assertEquals("ok", r.status(), r.toString());
        return r.value();
    }

    @Test
    void allEightMethods() throws Exception {
        PermissionGate all = PermissionGate.allowAll();
        var ctx = Json.parse(ok(all, "api.readContext()"));
        assertEquals(fx.ws.root().toString(), ctx.get("workspace").asText());
        assertEquals("main", ctx.get("git").get("branch").asText());
        assertTrue(ctx.get("git").get("dirty").asBoolean());
        assertTrue(ctx.get("files").toString().contains("src/"));

        assertTrue(ok(all, "api.readFile('src/App.java')").contains("return 41"));
        assertEquals("{\"path\":\"notes/n.txt\",\"bytes\":5}", ok(all, "api.writeFile('notes/n.txt', 'hello')"));
        assertEquals("hello", Files.readString(fx.root.resolve("notes/n.txt")));
        assertEquals("{\"path\":\"src/App.java\",\"replacements\":1}", ok(all, "api.editFile('src/App.java', 'return 41', 'return 42')"));
        assertTrue(Files.readString(fx.root.resolve("src/App.java")).contains("return 42"));

        var files = Json.parse(ok(all, "api.listFiles({glob: '*.java'})"));
        assertEquals("[\"src/App.java\"]", files.get("files").toString());
        var grep = Json.parse(ok(all, "api.grep(JSON.stringify({pattern: 'TODO', path: 'src'}))"));
        assertEquals(2, grep.get("matches").get(0).get("line").asInt());

        var status = Json.parse(ok(all, "api.git({op: 'status'})"));
        assertEquals(0, status.get("exitCode").asInt());
        ok(all, "api.git({op: 'add', paths: ['src/App.java']})");
        var commit = Json.parse(ok(all, "api.git({op: 'commit', message: 'Fix answer'})"));
        assertEquals(0, commit.get("exitCode").asInt(), commit.toString());
        assertTrue(Json.parse(ok(all, "api.git({op: 'log', oneline: true})")).get("output").asText().contains("Fix answer"));

        String id = ok(all, "api.emit({type: 'agent.note', text: 'done'})");
        Event e = fx.events.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
        assertEquals("guest", e.source());
        assertEquals("agent.note", e.type());
    }

    @Test
    void everyCallEmitsPermissionAndCallEvents() {
        ok(new DefaultGate(), "api.readFile('src/App.java')");
        var perm = fx.events.stream().filter(e -> e.type().equals("permission.decided")).findFirst().orElseThrow();
        assertEquals("readFile", perm.body().get("api").asText());
        assertEquals("read", perm.body().get("kind").asText());
        assertTrue(perm.body().get("allowed").asBoolean());
        var call = fx.events.stream().filter(e -> e.type().equals("api.call")).findFirst().orElseThrow();
        assertEquals("ok", call.body().get("status").asText());
    }

    @Test
    void defaultGateDeniesStateChangesWithAClearError() throws Exception {
        RunResult r = run(new DefaultGate(), "const x = 1;\napi.writeFile('a.txt', 'x');\n");
        assertEquals("error", r.status());
        assertEquals(2, r.line(), r.toString());
        assertTrue(r.error().contains("needs approval"), r.error());
        assertFalse(Files.exists(fx.root.resolve("a.txt")));
        assertTrue(run(new DefaultGate(), "api.editFile('src/App.java', '41', '42')").error().contains("needs approval"));
        assertTrue(run(new DefaultGate(), "api.git({op: 'commit', message: 'x'})").error().contains("needs approval"));
        assertEquals("ok", run(new DefaultGate(), "api.git({op: 'diff'})").status());
        assertTrue(fx.events.stream().anyMatch(e -> e.type().equals("api.call") && e.body().get("status").asText().equals("denied")));
        assertEquals("permission denied: writeFile needs approval",
                ok(new DefaultGate(), "try { api.writeFile('a', 'b'); 'no' } catch (e) { e.message.split(',')[0] }"));
    }

    @Test
    void pathsOutsideTheWorkspaceAreRejected() throws Exception {
        Files.writeString(tmp.resolve("secret.txt"), "s");
        Files.createSymbolicLink(fx.root.resolve("link"), tmp);
        PermissionGate all = PermissionGate.allowAll();
        for (String js : new String[] {"api.readFile('../secret.txt')", "api.readFile('" + tmp.resolve("secret.txt") + "')",
                "api.readFile('link/secret.txt')", "api.writeFile('../x.txt', 'x')", "api.writeFile('link/x.txt', 'x')",
                "api.listFiles({path: '..'})", "api.grep({pattern: 's', path: 'link'})", "api.git({op: 'add', paths: ['../secret.txt']})"}) {
            RunResult r = run(all, js);
            assertEquals("error", r.status(), js);
            assertTrue(r.error().contains("outside the workspace"), js + " -> " + r.error());
        }
        assertFalse(Files.exists(tmp.resolve("x.txt")));
    }

    @Test
    void argumentErrors() {
        PermissionGate all = PermissionGate.allowAll();
        assertTrue(run(all, "api.editFile('src/App.java', 'nope', 'x')").error().contains("not found"));
        ok(all, "api.writeFile('d.txt', 'a a')");
        assertTrue(run(all, "api.editFile('d.txt', 'a', 'b')").error().contains("occurs 2 times"));
        assertTrue(run(all, "api.grep({})").error().contains("pattern"));
        assertTrue(run(all, "api.git({op: 'push'})").error().contains("no remote named \"origin\""));
        assertTrue(run(all, "api.emit({type: 'api.call'})").error().contains("agent.*"));
        assertTrue(run(all, "api.readFile('src')").error().contains("folder"));
        assertTrue(run(all, "api.listFiles('not json')").error().contains("JSON"));
    }

    /** Spec 017 AC-07: kinds, the prompt names the remote URL, and bad remotes fail before the gate. */
    @Test
    void gitNetworkOpsAskWithTheUrl() throws Exception {
        List<dev.jharness.security.Operation> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        PermissionGate deny = op -> {
            asked.add(op);
            return dev.jharness.security.Decision.deny("test");
        };
        new ProcessBuilder("git", "remote", "add", "origin", "https://example.com/a/b.git").directory(fx.root.toFile()).start().waitFor();
        assertTrue(run(deny, "api.git({op: 'push', ref: 'main'})").error().contains("denied"));
        assertTrue(run(deny, "api.git({op: 'clone', url: 'https://example.com/a/lib.git'})").error().contains("denied"));
        assertTrue(run(deny, "api.git({op: 'fetch'})").error().contains("denied"));
        assertEquals(List.of("git push origin main → https://example.com/a/b.git", "git clone https://example.com/a/lib.git into lib",
                "git fetch origin → https://example.com/a/b.git"), asked.stream().map(dev.jharness.security.Operation::summary).toList());
        assertEquals(List.of(dev.jharness.security.Operation.Kind.GIT_WRITE, dev.jharness.security.Operation.Kind.GIT_WRITE,
                dev.jharness.security.Operation.Kind.GIT_READ), asked.stream().map(dev.jharness.security.Operation::kind).toList());

        asked.clear();
        new ProcessBuilder("git", "remote", "set-url", "origin", "git@github.com:a/b.git").directory(fx.root.toFile()).start().waitFor();
        assertTrue(run(deny, "api.git({op: 'pull'})").error().contains("https:// remotes only"));
        assertTrue(run(deny, "api.git({op: 'clone', url: 'https://example.com/x.git', dir: '../x'})").error().contains("outside"),
                "the clone folder passes the file policy");
        assertTrue(asked.isEmpty(), "rejected before the gate: " + asked);
    }

    @Test
    void readFileRejectsBinaryAndLargeFiles() throws Exception {
        Files.write(fx.root.resolve("b.bin"), new byte[] {(byte) 0xff, (byte) 0xfe, 0});
        assertTrue(run(new DefaultGate(), "api.readFile('b.bin')").error().contains("UTF-8"));
        Files.write(fx.root.resolve("big.txt"), new byte[(int) HostApi.MAX_READ_BYTES + 1]);
        assertTrue(run(new DefaultGate(), "api.readFile('big.txt')").error().contains("bytes"));
    }
}
