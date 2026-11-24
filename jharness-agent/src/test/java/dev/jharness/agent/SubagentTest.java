package dev.jharness.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.Settings;
import dev.jharness.events.SessionLog;
import dev.jharness.llm.ChatRequest;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.Prompter;

/**
 * Spec 026: api.spawnAgent. Main agent and subagents share one replay provider here, so the replies
 * are listed in the order they are asked for.
 */
class SubagentTest {

    @TempDir
    Path tmp;
    Path home;
    Path ws;
    ReplayProvider replay;
    List<JsonNode> events;
    TurnResult result;
    Settings settings = Settings.defaults();

    private Session open(AgentDefinition agent, List<String> replies) throws Exception {
        home = Files.createDirectories(tmp.resolve("home"));
        ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("a.txt"), "old");
        return Session.open(home, ws, settings, Session.Access.prompter(op -> Prompter.Answer.ONCE).agent(agent),
                (bus, turns) -> replay = ReplayProvider.ofReplies(replies, bus, turns));
    }

    private void turn(AgentDefinition agent, String... replies) throws Exception {
        Path log;
        try (Session s = open(agent, List.of(replies))) {
            result = s.loop().turn("go", d -> {});
            log = s.logPath();
        }
        events = SessionLog.read(log);
    }

    private String resultOf(int request) {
        return replay.requests().get(request).messages().getLast().content();
    }

    private List<JsonNode> of(String type) {
        return events.stream().filter(e -> e.get("type").asText().equals(type)).toList();
    }

    private static String js(String code) {
        return "```js\n" + code + "\n```";
    }

    @Test
    void aSubagentRunsTheTaskAndReturnsItsReply() throws Exception {
        turn(null,
                js("const r = JSON.parse(api.spawnAgent({task: 'Read a.txt and tell me what it says.'})); r.agent + ' ' + r.stopReason + ' ' + r.steps + ': ' + r.reply"),
                js("api.readFile('a.txt')"),
                "a.txt says old.",
                "The subagent says a.txt says old.");
        assertEquals("done", result.stopReason());
        assertTrue(resultOf(3).contains("sub-1 done 1: a.txt says old."), resultOf(3));
        ChatRequest sub = replay.requests().get(1);
        assertTrue(sub.system().contains("# You are a subagent"), "subagent prompt");
        assertEquals("Read a.txt and tell me what it says.", sub.messages().getFirst().content(), "a fresh conversation with the task");
        JsonNode started = of("subagent.started").getFirst();
        assertEquals("sub-1", started.get("agent").asText());
        assertEquals(1, started.get("turn").asInt(), "the parent's turn");
        assertEquals("default", started.get("customAgent").asText());
        assertEquals(100, started.get("maxSteps").asInt(), "agent.subagentMaxSteps by default");
        assertEquals("session", started.get("model").asText(), "the session's provider");
        JsonNode done = of("subagent.completed").getFirst();
        assertEquals("done", done.get("stopReason").asText());
        assertTrue(of("loop.code").stream().anyMatch(e -> e.get("agent").asText().equals("sub-1")), "the subagent's steps carry its name");
        assertTrue(of("sandbox.run_started").stream().anyMatch(e -> e.get("profile").asText().equals("subagent")), "the subagent sandbox profile");
        assertTrue(of("permission.decided").stream().anyMatch(e -> e.get("api").asText().equals("spawnAgent")
                && e.get("allowed").asBoolean()), "spawning doesn't ask");
    }

    @Test
    void subagentsCantSpawnAndBadRequestsAreRefused() throws Exception {
        turn(null,
                js("api.spawnAgent('nested')"),
                js("api.spawnAgent('again')"),
                "I can't.",
                js("const out = [];\n"
                        + "for (const q of [{}, {task: 'x', color: 1}, {task: 'x', maxSteps: 0}, {task: 'x', maxSteps: 101}, {task: 'x', agent: 'nope'}]) {\n"
                        + "  try { api.spawnAgent(q); } catch (e) { out.push(e.message); }\n"
                        + "}\nout.join('\\n')"),
                "done");
        assertTrue(resultOf(2).contains("spawnAgent is not available here"), resultOf(2));
        String errors = resultOf(4);
        assertTrue(errors.contains("needs a \"task\""), errors);
        assertTrue(errors.contains("does not take \"color\""), errors);
        assertTrue(errors.contains("\"maxSteps\" must be a whole number from 1 to 100"), errors);
        assertTrue(errors.contains("unknown agent \"nope\""), errors);
        assertEquals(1, of("subagent.started").size());
    }

    @Test
    void customAgentsNarrowASubagent() throws Exception {
        Path a = Files.createDirectories(tmp.resolve("home/.jharness/agents/reader"));
        Files.writeString(a.resolve("agent.json"), "{\"description\":\"reads\",\"tools\":[\"readFile\"],\"maxSteps\":3}");
        Path p = Files.createDirectories(tmp.resolve("home/.jharness/agents/planner"));
        Files.writeString(p.resolve("agent.json"), "{\"description\":\"plans\",\"plan\":true}");
        Files.writeString(p.resolve("helpers.js"), "function hello() { return 'hi from helpers'; }");
        turn(null,
                js("JSON.parse(api.spawnAgent({task: 'write', agent: 'reader'})).reply"),
                js("api.writeFile('a.txt', 'x')"),
                "Could not write.",
                js("JSON.parse(api.spawnAgent({task: 'write', agent: 'planner'})).reply"),
                js("hello() + ' ' + api.writeFile('a.txt', 'y')"),
                "Plan mode.",
                "done");
        assertTrue(resultOf(2).contains("agent reader can't use api.writeFile"), resultOf(2));
        assertTrue(resultOf(5).contains("agent planner runs in plan mode"), resultOf(5));
        assertTrue(replay.requests().get(4).system().contains("function hello()"), "the agent's helpers are in its prompt");
        assertEquals("old", Files.readString(ws.resolve("a.txt")));
        assertEquals(3, of("subagent.started").getFirst().get("maxSteps").asInt());
        assertEquals("reader", of("subagent.started").getFirst().get("customAgent").asText());
    }

    /** A main agent's own tools still apply inside its subagents. */
    @Test
    void theMainAgentsToolsBindItsSubagents() throws Exception {
        Path a = Files.createDirectories(tmp.resolve("home/.jharness/agents/lead"));
        Files.writeString(a.resolve("agent.json"), "{\"description\":\"delegates\",\"tools\":[\"spawnAgent\",\"readFile\"]}");
        AgentDefinition lead = AgentDefinition.resolve(tmp.resolve("home"), Files.createDirectories(tmp.resolve("ws")), false, "lead");
        turn(lead, js("JSON.parse(api.spawnAgent('write')).reply"), js("api.writeFile('a.txt', 'x')"), "No.", "done");
        assertTrue(resultOf(2).contains("agent lead can't use api.writeFile"), resultOf(2));
    }

    @Test
    void modelsListsTheProfilesAndSpawnTakesOne() throws Exception {
        settings = Settings.parse("""
                {"model": "local", "models": {
                  "local": {"provider": "openai-compatible", "baseUrl": "http://localhost:1/v1", "model": "qwen", "notes": "small"},
                  "big": {"provider": "anthropic", "model": "claude-x", "apiKeyEnv": "SECRET_ENV"}}}""", "s");
        turn(null,
                js("api.models()"),
                js("const out = [];\n"
                        + "for (const q of [{task: 'x', model: 'nope'}, {task: 'x', model: 3}]) {\n"
                        + "  try { api.spawnAgent(q); } catch (e) { out.push(e.message); }\n"
                        + "}\nout.join('\\n')"),
                "done");
        String models = resultOf(1);
        assertTrue(models.contains("\"current\":\"local\""), models);
        assertTrue(models.contains("{\"name\":\"local\",\"provider\":\"openai-compatible\",\"model\":\"qwen\",\"notes\":\"small\"}"), models);
        assertTrue(models.contains("\"name\":\"big\""), models);
        assertFalse(models.contains("localhost") || models.contains("SECRET_ENV"), "no URLs or key variables");
        String errors = resultOf(2);
        assertTrue(errors.contains("unknown model profile \"nope\" (known: local, big)"), errors);
        assertTrue(errors.contains("\"model\" must be a string"), errors);
        assertEquals(0, of("subagent.started").size());
    }

    @Test
    void subagentLimitsComeFromSettings() throws Exception {
        settings = Settings.parse("{\"agent\": {\"subagentsPerTurn\": 2, \"subagentMaxSteps\": 7}}", "s");
        turn(null,
                js("let n = 0;\ntry { for (let i = 0; i < 3; i++) { api.spawnAgent('t' + i); n++; } } catch (e) { n + ': ' + e.message }"),
                "ok", "ok", "done");
        assertTrue(resultOf(3).contains("2: this turn already started 2 subagents"), resultOf(3));
        assertEquals(7, of("subagent.started").getFirst().get("maxSteps").asInt());
    }

    @Test
    void modelsIsNotAvailableInASubagent() throws Exception {
        turn(null, js("api.spawnAgent('list')"), js("api.models()"), "no", "done");
        assertTrue(resultOf(2).contains("models is not available here"), resultOf(2));
    }

    @Test
    void atMostTenPerTurn() throws Exception {
        List<String> replies = new ArrayList<>();
        replies.add(js("let n = 0;\ntry { for (let i = 0; i < 11; i++) { api.spawnAgent('t' + i); n++; } } catch (e) { n + ': ' + e.message }"));
        for (int i = 0; i < 10; i++) {
            replies.add("ok");
        }
        replies.add("done");
        turn(null, replies.toArray(String[]::new));
        assertTrue(resultOf(11).contains("10: this turn already started 10 subagents"), resultOf(11));
        assertEquals(10, of("subagent.completed").size());
    }

    /** The subagent's file changes belong to the parent's turn, so /undo restores them. */
    @Test
    void undoRestoresASubagentsChanges() throws Exception {
        try (Session s = open(null, List.of(js("api.spawnAgent('change a.txt')"), js("api.writeFile('a.txt', 'new')"), "Changed.", "done"))) {
            s.loop().turn("go", d -> {});
            assertEquals("new", Files.readString(ws.resolve("a.txt")));
            assertTrue(s.loop().lastTurnDiff().contains("+new"), s.loop().lastTurnDiff());
            s.loop().undo(false);
            assertEquals("old", Files.readString(ws.resolve("a.txt")));
        }
    }

    @Test
    void escStopsARunningSubagent() throws Exception {
        try (Session s = open(null, List.of(js("api.spawnAgent('spin')"), js("while (true) {}"), "never", "never"))) {
            Thread esc = new Thread(() -> {
                try {
                    long end = System.currentTimeMillis() + 20_000;
                    while (System.currentTimeMillis() < end && replay.requests().size() < 2) {
                        Thread.sleep(20);
                    }
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
                s.loop().interrupt();
            });
            esc.start();
            long t0 = System.nanoTime();
            TurnResult r = s.loop().turn("go", d -> {});
            esc.join();
            assertEquals("interrupted", r.stopReason());
            assertTrue(System.nanoTime() - t0 < 15_000_000_000L, "Esc stopped the subagent's loop");
        }
    }
}
