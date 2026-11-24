package dev.jharness.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.Settings;
import dev.jharness.events.SessionLog;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.Prompter;

/** Spec 034: JS hooks before and after api calls and when a turn ends. */
class HookTest {

    @TempDir
    Path tmp;
    Path home;
    Path ws;
    ReplayProvider replay;
    List<JsonNode> events;

    private Path hooks() throws Exception {
        return Files.createDirectories(tmp.resolve("home/.jharness/hooks"));
    }

    private void session(boolean trusted, String... replies) throws Exception {
        home = Files.createDirectories(tmp.resolve("home"));
        ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("a.txt"), "old");
        Path log;
        try (Session s = Session.open(home, ws, Settings.defaults(), Session.Access.prompter(op -> Prompter.Answer.ONCE).trusted(trusted),
                (bus, turns) -> replay = ReplayProvider.ofReplies(List.of(replies), bus, turns))) {
            s.loop().turn("go", d -> {});
            log = s.logPath();
        }
        events = SessionLog.read(log);
    }

    private static String js(String code) {
        return "```js\n" + code + "\n```";
    }

    private String resultOf(int request) {
        return replay.requests().get(request).messages().getLast().content();
    }

    private List<JsonNode> of(String type) {
        return events.stream().filter(e -> e.get("type").asText().equals(type)).toList();
    }

    private static final String TRY = "const out = [];\nfor (const f of [%s]) {\n  try { f(); out.push('ok'); } catch (e) { out.push(e.message); }\n}\nout.join('\\n')";

    @Test
    void aPreToolHookBlocksACallBeforeThePermissionGate() throws Exception {
        Files.writeString(hooks().resolve("guard.js"), """
                function preTool(call) {
                  if (call.api === 'writeFile' && call.detail.path === 'secret.txt') return {block: 'secret.txt is off limits'};
                }
                """);
        session(false, js(TRY.formatted("() => api.writeFile('secret.txt', 'x'), () => api.writeFile('a.txt', 'new')")), "done");
        String r = resultOf(1);
        assertTrue(r.contains("blocked by hook guard: secret.txt is off limits"), r);
        assertTrue(r.endsWith("\nok"), "other calls go on: " + r);
        assertFalse(Files.exists(ws.resolve("secret.txt")));
        assertEquals("new", Files.readString(ws.resolve("a.txt")));
        assertTrue(of("permission.decided").stream().noneMatch(e -> e.get("summary").asText().contains("secret.txt")), "the gate never saw it");
        assertTrue(of("hook.ran").stream().anyMatch(e -> e.get("outcome").asText().equals("block") && e.get("hook").asText().equals("guard")));
        assertTrue(of("api.call").stream().anyMatch(e -> e.get("status").asText().equals("blocked")));
        JsonNode loaded = of("hooks.loaded").getFirst();
        assertEquals("[\"preTool\"]", loaded.get("hooks").get(0).get("functions").toString());
    }

    @Test
    void aFailingPreToolHookBlocksTheCall() throws Exception {
        Files.writeString(hooks().resolve("broken-at-runtime.js"), "function preTool(call) { if (call.api === 'writeFile') throw new Error('boom'); }");
        session(false, js(TRY.formatted("() => api.writeFile('a.txt', 'new'), () => api.readFile('a.txt')")), "done");
        String r = resultOf(1);
        assertTrue(r.contains("blocked by hook broken-at-runtime failed (") && r.contains("boom"), r);
        assertEquals("old", Files.readString(ws.resolve("a.txt")), "fail closed");
        assertTrue(r.endsWith("\nok"), "reads go on: " + r);
    }

    @Test
    void postToolHooksSeeResultsAndTheirOwnCallsDontRunHooks() throws Exception {
        Files.writeString(hooks().resolve("audit.js"), """
                function postTool(call) {
                  if (call.api !== 'emit') api.emit({type: 'agent.audit', api: call.api, status: call.status, result: (call.result || '').slice(0, 20)});
                }
                """);
        session(false, js("api.readFile('a.txt'); 'read'"), "done");
        List<JsonNode> audit = of("agent.audit");
        assertTrue(audit.stream().anyMatch(e -> e.get("api").asText().equals("readFile") && e.get("result").asText().equals("old")), audit.toString());
        assertTrue(audit.stream().allMatch(e -> e.get("agent").asText().equals("hook-audit")), "a hook's events carry its name");
        assertTrue(audit.stream().noneMatch(e -> e.get("api").asText().equals("emit")), "a hook's own calls run no hooks");
    }

    @Test
    void aStopHookMakesTheAgentGoOnAtMostThreeTimes() throws Exception {
        Files.writeString(hooks().resolve("more.js"), "function stop(turn) { return {continue: 'not yet (' + turn.continued + ')'}; }");
        session(false, "first", "second", "third", "fourth", "never");
        assertEquals(4, replay.requests().size(), "three continues, then the turn ends");
        assertTrue(resultOf(1).startsWith("A stop hook asks you to go on before you finish:\n[more] not yet (0)"), resultOf(1));
        assertTrue(resultOf(3).contains("not yet (2)"), resultOf(3));
        assertEquals(3, of("hook.ran").stream().filter(e -> e.get("outcome").asText().equals("continue")).count());
        assertEquals("done", of("turn.completed").getFirst().get("stopReason").asText());
    }

    @Test
    void projectHooksRunOnlyInTrustedProjectsAndBrokenFilesAreReported() throws Exception {
        Path pd = Files.createDirectories(tmp.resolve("ws/.jharness/hooks"));
        Files.writeString(pd.resolve("deny-all.js"), "function preTool(call) { return call.api === 'readFile' ? {block: 'project rule'} : null; }");
        Files.writeString(hooks().resolve("Bad Name.js"), "function stop() {}");
        Files.writeString(hooks().resolve("syntax.js"), "function preTool( {");
        Files.writeString(hooks().resolve("empty.js"), "const x = 1;");
        session(false, js("api.readFile('a.txt')"), "done");
        assertFalse(resultOf(1).contains("project rule"), "untrusted: not loaded");
        JsonNode errors = of("hooks.loaded").getFirst().get("errors");
        assertTrue(errors.get("~/.jharness/hooks/Bad Name.js").asText().contains("hook names"), errors.toString());
        assertTrue(errors.get("~/.jharness/hooks/syntax.js").asText().contains("doesn't run"), errors.toString());
        assertTrue(errors.get("~/.jharness/hooks/empty.js").asText().contains("defines none"), errors.toString());
        assertEquals(List.of("deny-all.js"), Session.projectHooks(tmp.resolve("ws")));
        session(true, js("api.readFile('a.txt')"), "done");
        assertTrue(resultOf(1).contains("blocked by hook deny-all (project): project rule"), resultOf(1));
    }

    @Test
    void subagentsRunHooksToo() throws Exception {
        Files.writeString(hooks().resolve("who.js"), "function preTool(call) { if (call.agent === 'sub-1' && call.api === 'readFile') return {block: 'not for subagents'}; }");
        session(false, js("JSON.parse(api.spawnAgent('read a.txt')).reply"), js("api.readFile('a.txt')"), "I couldn't.", "done");
        assertTrue(resultOf(2).contains("blocked by hook who: not for subagents"), resultOf(2));
    }

    /** The roadmap's Phase 3 gate: the shipped example hook works. */
    @Test
    void theExampleGuardHookWorks() throws Exception {
        Path example = Path.of("..", "examples", ".jharness", "hooks", "guard.js").toAbsolutePath().normalize();
        Files.copy(example, hooks().resolve("guard.js"));
        Path repo = Files.createDirectories(tmp.resolve("ws"));
        for (String[] g : new String[][] {{"init", "-q"}, {"remote", "add", "origin", "https://example.com/x.git"}}) {
            List<String> cmd = new java.util.ArrayList<>(List.of("git"));
            cmd.addAll(List.of(g));
            Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            assertEquals(0, p.waitFor());
        }
        session(false, js(TRY.formatted("() => api.writeFile('package-lock.json', '{}'), () => api.git({op: 'push', remote: 'origin', ref: 'main'}), "
                + "() => api.writeFile('a.txt', 'new')")), "Done.", "I can't run tests here.");
        String r = resultOf(1);
        assertTrue(r.contains("blocked by hook guard: package-lock.json is written by the build tool"), r);
        assertTrue(r.contains("blocked by hook guard: pushing to main is not allowed"), r);
        assertFalse(Files.exists(ws.resolve("package-lock.json")));
        assertTrue(resultOf(2).contains("[guard] You changed files but didn't run the tests"), "the stop hook: " + resultOf(2));
        assertEquals(3, replay.requests().size(), "it continues once, then lets the agent finish");
    }
}
