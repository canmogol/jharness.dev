package dev.jharness.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import dev.jharness.config.Settings;
import dev.jharness.events.SessionLog;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.sandbox.Checkpoints;
import dev.jharness.security.Operation;
import dev.jharness.security.PermissionGate;
import dev.jharness.security.Prompter;

/** Spec 056: api.deleteFile and api.deleteFolder. */
class DeleteTest {

    @TempDir
    Path tmp;

    private ReplayProvider replay;

    private Path project() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws")).toRealPath();
        Files.writeString(ws.resolve("a.txt"), "alpha\n");
        Files.createDirectories(ws.resolve("build/classes/empty"));
        Files.writeString(ws.resolve("build/out.txt"), "one\n");
        Files.writeString(ws.resolve("build/classes/App.class"), "two\n");
        return ws;
    }

    /** Runs one turn of code and returns the value it ended with, as the model would see it. */
    private String turn(Session s, String js) {
        s.loop().turn("go", d -> {});
        String last = null;
        for (var r : replay.requests()) {
            String m = r.messages().getLast().content();
            if (m.startsWith(AgentLoop.RESULT_PREFIX)) {
                last = m;
            }
        }
        return last;
    }

    private Session open(Path ws, Settings settings, Session.Access access, String... replies) throws Exception {
        return Session.open(tmp.resolve("home"), ws, settings, access, (bus, turns) -> replay = ReplayProvider.ofReplies(List.of(replies), bus, turns));
    }

    private static String code(String js) {
        return "```js\n" + js + "\n```";
    }

    /** AC-01, AC-02, AC-04: both deletes, their results and events, the diff, and undo. */
    @Test
    void deletesAreSavedAndUndone() throws Exception {
        Path ws = project();
        Path log;
        try (Session s = open(ws, Settings.defaults(), Session.Access.gate(PermissionGate.allowAll()),
                code("JSON.stringify([JSON.parse(api.deleteFile('a.txt')), JSON.parse(api.deleteFolder('build'))])"), "Deleted.")) {
            log = s.logPath();
            String result = turn(s, null);
            assertTrue(result.contains("{\"path\":\"a.txt\",\"bytes\":6}"), result);
            assertTrue(result.contains("{\"path\":\"build\",\"files\":2,\"bytes\":8}"), result);
            assertFalse(Files.exists(ws.resolve("a.txt")));
            assertFalse(Files.exists(ws.resolve("build")));
            assertTrue(s.loop().lastTurnDiff().contains("-alpha"), s.loop().lastTurnDiff());

            Checkpoints.Restore u = s.loop().undo(false);
            assertTrue(u.done(), u.message());
            assertEquals("alpha\n", Files.readString(ws.resolve("a.txt")));
            assertEquals("one\n", Files.readString(ws.resolve("build/out.txt")));
            assertEquals("two\n", Files.readString(ws.resolve("build/classes/App.class")));
            assertFalse(Files.exists(ws.resolve("build/classes/empty")), "empty folders aren't restored");
        }
        List<JsonNode> events = SessionLog.read(log);
        assertTrue(events.stream().anyMatch(e -> e.get("type").asText().equals("file.changed") && e.get("path").asText().equals("a.txt")
                && e.get("deleted").asBoolean() && e.get("diff").asText().contains("-alpha")));
        assertTrue(events.stream().anyMatch(e -> e.get("type").asText().equals("folder.deleted") && e.get("path").asText().equals("build")
                && e.get("files").asInt() == 2));
        assertEquals(3, events.stream().filter(e -> e.get("type").asText().equals("checkpoint.saved")).count());
    }

    /** AC-03: refused before any question, with a gate that allows everything. */
    @Test
    void refusals() throws Exception {
        Path ws = project();
        Files.writeString(ws.resolve(".env"), "SECRET=1\n");
        Files.createDirectories(ws.resolve(".git"));
        Files.writeString(ws.resolve(".git/config"), "[core]\n");
        Files.createDirectories(ws.resolve(".jharness"));
        Files.createDirectories(ws.resolve("conf"));
        Files.writeString(ws.resolve("conf/.env"), "SECRET=2\n");
        Files.createDirectories(ws.resolve("linked"));
        Files.createSymbolicLink(ws.resolve("linked/out"), tmp);
        Files.createDirectories(ws.resolve("many"));
        for (int i = 0; i <= 1000; i++) {
            Files.writeString(ws.resolve("many/f" + i), "");
        }
        Files.createDirectories(ws.resolve("big"));
        Files.write(ws.resolve("big/blob.bin"), new byte[(10 << 20) + 1]);
        Files.createDirectories(ws.resolve("sub/.git"));
        Files.writeString(ws.resolve("sub/.git/HEAD"), "ref\n");
        List<String> calls = List.of("deleteFolder('.')", "deleteFolder('')", "deleteFolder('" + tmp + "')", "deleteFile('../x')",
                "deleteFolder('.git')", "deleteFile('.git/config')", "deleteFolder('.jharness')", "deleteFile('.env')",
                "deleteFolder('conf')", "deleteFolder('linked')", "deleteFolder('many')", "deleteFolder('big')", "deleteFile('big/blob.bin')",
                "deleteFolder('sub')", "deleteFolder('sub/.git')", "deleteFile('build')", "deleteFolder('a.txt')", "deleteFile('nope.txt')",
                "deleteFolder('nope')");
        StringBuilder js = new StringBuilder("const out = [];\n");
        for (String c : calls) {
            String label = c.replace("'", "\\'");
            js.append("try { out.push('").append(label).append(" -> ' + api.").append(c).append("); } catch (e) { out.push('").append(label)
                    .append(" -> ERR ' + e.message); }\n");
        }
        js.append("out.join('\\n')");
        List<Operation> asked = new ArrayList<>();
        PermissionGate gate = op -> {
            asked.add(op);
            return dev.jharness.security.Decision.allow("test");
        };
        try (Session s = open(ws, Settings.defaults(), Session.Access.gate(gate), code(js.toString()), "Done.")) {
            String result = turn(s, null);
            for (String c : calls) {
                assertTrue(result.contains(c + " -> ERR") || result.contains(c + " -> {\"error\""), c + " wasn't refused: " + result);
            }
            assertTrue(asked.stream().noneMatch(o -> o.api().startsWith("delete")), "never asked: " + asked);
        }
        for (String p : List.of(".env", ".git/config", ".jharness", "conf/.env", "linked/out", "many/f0", "big/blob.bin", "sub/.git/HEAD",
                "build/out.txt", "a.txt")) {
            assertTrue(Files.exists(ws.resolve(p), java.nio.file.LinkOption.NOFOLLOW_LINKS), p + " is still there");
        }
        assertTrue(Files.exists(tmp.resolve("home")), "nothing outside the workspace went");
    }

    /** AC-01, AC-02: deleteFile follows the write rules; deleteFolder asks even in accept-edits; "always" saves a delete rule. */
    @Test
    void approvals() throws Exception {
        Path ws = project();
        List<Operation> asked = new ArrayList<>();
        Prompter always = op -> {
            asked.add(op);
            return Prompter.Answer.ALWAYS;
        };
        String js = "api.deleteFile('a.txt'); api.deleteFolder('build/classes'); "
                + "api.writeFile('build/classes/B.class', 'b'); api.deleteFolder('build/classes'); 'ok'";
        try (Session s = open(ws, Settings.parse("{\"permissions\": {\"mode\": \"accept-edits\"}}", "t"), Session.Access.prompter(always),
                code(js), "Done.")) {
            String result = turn(s, null);
            assertTrue(result.contains("ok"), result);
        }
        assertEquals(1, asked.size(), "the file wasn't asked, the folder once, then its saved rule allowed it: " + asked);
        Operation q = asked.getFirst();
        assertEquals(Operation.Kind.DELETE, q.kind());
        assertEquals("delete folder build/classes/ and its 1 file (4 bytes): build/classes/App.class", q.summary());
        String rules = Files.readString(dev.jharness.security.ProjectRules.fileFor(tmp.resolve("home"), ws));
        assertTrue(rules.contains("\"kind\":\"delete\"") && rules.contains("\"path\":\"build/classes\""), rules);

        // Plan mode refuses both; bypass asks neither.
        Path ws2 = Files.createDirectories(tmp.resolve("ws2")).toRealPath();
        Files.writeString(ws2.resolve("x.txt"), "x");
        Files.createDirectories(ws2.resolve("d"));
        String both = "const r = []; for (const f of [() => api.deleteFile('x.txt'), () => api.deleteFolder('d')]) { try { r.push(f()); } catch (e) { r.push('ERR ' + e.message); } } r.join(' | ')";
        try (Session s = Session.open(tmp.resolve("home2"), ws2, Settings.parse("{\"permissions\": {\"mode\": \"plan\"}}", "t"),
                Session.Access.prompter(always), (bus, turns) -> replay = ReplayProvider.ofReplies(List.of(code(both), "Done."), bus, turns))) {
            String result = turn(s, null);
            assertEquals(2, result.split("plan mode is read-only", -1).length - 1, result);
        }
        assertTrue(Files.exists(ws2.resolve("x.txt")) && Files.exists(ws2.resolve("d")));
        asked.clear();
        try (Session s = Session.open(tmp.resolve("home3"), ws2, Settings.parse("{\"permissions\": {\"mode\": \"bypass\"}}", "t"),
                Session.Access.prompter(always), (bus, turns) -> replay = ReplayProvider.ofReplies(List.of(code(both), "Done."), bus, turns))) {
            turn(s, null);
        }
        assertTrue(asked.isEmpty(), asked.toString());
        assertFalse(Files.exists(ws2.resolve("x.txt")) || Files.exists(ws2.resolve("d")));
    }
}
