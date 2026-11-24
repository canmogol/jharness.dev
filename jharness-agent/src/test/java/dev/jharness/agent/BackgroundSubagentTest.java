package dev.jharness.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.Settings;
import dev.jharness.events.SessionLog;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.Prompter;

/** Spec 027: background subagents. Each subagent gets its own replay provider, so threads don't race for replies. */
class BackgroundSubagentTest {

    @TempDir
    Path tmp;
    Path ws;
    ReplayProvider main;
    final Map<String, ReplayProvider> subs = new ConcurrentHashMap<>();
    final AtomicInteger asked = new AtomicInteger();
    Settings settings = Settings.defaults();

    private Session open(List<String> mainReplies, Map<String, List<String>> subReplies) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("a.txt"), "old");
        Session s = Session.open(home, ws, settings, Session.Access.prompter(op -> {
            asked.incrementAndGet();
            return Prompter.Answer.ONCE;
        }), (bus, turns) -> main = ReplayProvider.ofReplies(mainReplies, bus, turns));
        s.runner().testProviders = t -> subs.computeIfAbsent(t.agent(),
                n -> ReplayProvider.ofReplies(subReplies.getOrDefault(n, List.of("no reply")), s.bus(), t));
        return s;
    }

    private static String js(String code) {
        return "```js\n" + code + "\n```";
    }

    private String resultOf(int request) {
        return main.requests().get(request).messages().getLast().content();
    }

    private static List<JsonNode> of(List<JsonNode> events, String type) {
        return events.stream().filter(e -> e.get("type").asText().equals(type)).toList();
    }

    private static void waitIdle(Session s) throws InterruptedException {
        long end = System.currentTimeMillis() + 20_000;
        while (!s.runningSubagents().isEmpty() && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        assertTrue(s.runningSubagents().isEmpty(), "background subagents finished");
    }

    @Test
    void aBackgroundSubagentRunsWhileTheMainAgentWaits() throws Exception {
        Path log;
        try (Session s = open(List.of(
                js("const a = JSON.parse(api.spawnAgent({task: 'read a.txt', background: true}));\n"
                        + "const r = JSON.parse(api.waitAgent(a.agent));\na.agent + ' ' + a.status + ' ' + r.status + ': ' + r.reply"),
                "done"),
                Map.of("sub-1", List.of(js("api.readFile('a.txt')"), "a.txt says old.")))) {
            s.loop().turn("go", d -> {});
            log = s.logPath();
        }
        assertTrue(resultOf(1).contains("sub-1 running done: a.txt says old."), resultOf(1));
        List<JsonNode> events = SessionLog.read(log);
        JsonNode started = of(events, "subagent.started").getFirst();
        assertTrue(started.get("background").asBoolean());
        assertEquals("sub-1", started.get("agent").asText());
        assertTrue(of(events, "loop.code").stream().anyMatch(e -> e.get("agent").asText().equals("sub-1")));
    }

    @Test
    void waitTimesOutAndStopAgentStopsIt() throws Exception {
        Path log;
        try (Session s = open(List.of(
                js("const a = JSON.parse(api.spawnAgent({task: 'spin', background: true})).agent;\n"
                        + "const w = JSON.parse(api.waitAgent({agent: a, timeoutMs: 300}));\n"
                        + "const before = JSON.parse(api.agents())[0].status;\n"
                        + "const st = JSON.parse(api.stopAgent(a));\n"
                        + "const after = JSON.parse(api.agents())[0];\n"
                        + "w.status + ' ' + before + ' ' + st.status + ' ' + after.status + ' ' + after.background + ' ' + after.task"),
                "done"),
                Map.of("sub-1", List.of(js("while (true) {}"), "never")))) {
            s.loop().turn("go", d -> {});
            log = s.logPath();
        }
        assertTrue(resultOf(1).contains("running running stopped stopped true spin"), resultOf(1));
        JsonNode done = of(SessionLog.read(log), "subagent.completed").getFirst();
        assertEquals("stopped", done.get("stopReason").asText());
    }

    @Test
    void theRunningCapAndBadRequestsAreRefused() throws Exception {
        settings = Settings.parse("{\"agent\": {\"subagentsRunning\": 1}}", "s");
        try (Session s = open(List.of(
                js("const out = [];\nconst a = JSON.parse(api.spawnAgent({task: 'spin', background: true})).agent;\n"
                        + "for (const f of [() => api.spawnAgent({task: 'two', background: true}), () => api.spawnAgent({task: 'x', background: 'yes'}),\n"
                        + "    () => api.waitAgent('sub-9'), () => api.waitAgent({agent: a, timeoutMs: -1}), () => api.waitAgent({agent: a, color: 1}),\n"
                        + "    () => api.stopAgent('')]) {\n"
                        + "  try { f(); out.push('no error'); } catch (e) { out.push(e.message); }\n"
                        + "}\napi.stopAgent(a);\nout.join('\\n')"),
                "done"),
                Map.of("sub-1", List.of(js("while (true) {}"), "never")))) {
            s.loop().turn("go", d -> {});
        }
        String errors = resultOf(1);
        assertTrue(errors.contains("1 background subagents are running, the most at once (agent.subagentsRunning)"), errors);
        assertTrue(errors.contains("\"background\" must be true or false"), errors);
        assertTrue(errors.contains("no subagent sub-9 in this session (known: sub-1)"), errors);
        assertTrue(errors.contains("\"timeoutMs\" must be a whole number from 0 to 3600000"), errors);
        assertTrue(errors.contains("waitAgent does not take \"color\""), errors);
        assertTrue(errors.contains("stopAgent needs a subagent name"), errors);
        assertFalse(errors.contains("no error"), errors);
    }

    @Test
    void aBackgroundSubagentNeverAsks() throws Exception {
        try (Session s = open(List.of(
                js("JSON.parse(api.waitAgent(JSON.parse(api.spawnAgent({task: 'write', background: true})).agent)).reply"),
                "done"),
                Map.of("sub-1", List.of(js("api.writeFile('a.txt', 'x')"), "Could not write.")))) {
            s.loop().turn("go", d -> {});
            String subResult = subs.get("sub-1").requests().get(1).messages().getLast().content();
            assertTrue(subResult.contains("no one can answer while this runs in the background"), subResult);
        }
        assertEquals(0, asked.get(), "the user was never asked");
        assertEquals("old", Files.readString(ws.resolve("a.txt")));
    }

    @Test
    void aForegroundSubagentStillAsks() throws Exception {
        try (Session s = open(List.of(js("JSON.parse(api.spawnAgent('write')).reply"), "done"),
                Map.of("sub-1", List.of(js("api.writeFile('a.txt', 'x')"), "Wrote it.")))) {
            s.loop().turn("go", d -> {});
        }
        assertEquals(1, asked.get());
        assertEquals("x", Files.readString(ws.resolve("a.txt")));
    }

    @Test
    void uncollectedRepliesComeWithTheNextPrompt() throws Exception {
        Path log;
        try (Session s = open(List.of(js("api.spawnAgent({task: 'quick', background: true})"), "Started it.", "Thanks.", "Again."),
                Map.of("sub-1", List.of("finished quickly")))) {
            s.loop().turn("one", d -> {});
            waitIdle(s);
            assertEquals("done", s.subagents().getFirst().status());
            s.loop().turn("two", d -> {});
            s.loop().turn("three", d -> {});
            log = s.logPath();
        }
        String second = main.requests().get(2).messages().getLast().content();
        assertTrue(second.startsWith("[Background subagents that finished since your last turn]"), second);
        assertTrue(second.contains("sub-1 (done after 0 steps): finished quickly"), second);
        assertTrue(second.endsWith("\n\ntwo"), second);
        assertEquals("three", main.requests().get(3).messages().getLast().content(), "delivered once");
        assertEquals(1, of(SessionLog.read(log), "subagent.delivered").size());
    }

    @Test
    void escLeavesBackgroundSubagentsRunningAndCloseStopsThem() throws Exception {
        Path log;
        try (Session s = open(List.of(js("api.spawnAgent({task: 'spin', background: true}); while (true) {}"), "never"),
                Map.of("sub-1", List.of(js("while (true) {}"), "never")))) {
            Thread esc = new Thread(() -> {
                try {
                    long end = System.currentTimeMillis() + 20_000;
                    while (System.currentTimeMillis() < end && s.subagents().isEmpty()) {
                        Thread.sleep(20);
                    }
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
                s.loop().interrupt();
            });
            esc.start();
            TurnResult r = s.loop().turn("go", d -> {});
            esc.join();
            assertEquals("interrupted", r.stopReason());
            assertEquals(List.of("sub-1"), s.runningSubagents(), "Esc doesn't stop background subagents");
            log = s.logPath();
        }
        JsonNode done = of(SessionLog.read(log), "subagent.completed").getFirst();
        assertEquals("stopped", done.get("stopReason").asText(), "closing the session stopped it");
    }

    @Test
    void subagentsCantManageAgents() throws Exception {
        try (Session s = open(List.of(js("JSON.parse(api.spawnAgent('try')).reply"), "done"),
                Map.of("sub-1", List.of(js("const out = [];\nfor (const f of [() => api.agents(), () => api.waitAgent('sub-1'), () => api.stopAgent('sub-1')]) {\n"
                        + "  try { f(); } catch (e) { out.push(e.message); }\n}\nout.join('\\n')"), "no")))) {
            s.loop().turn("go", d -> {});
            String r = subs.get("sub-1").requests().get(1).messages().getLast().content();
            assertTrue(r.contains("agents is not available here"), r);
            assertTrue(r.contains("waitAgent is not available here"), r);
            assertTrue(r.contains("stopAgent is not available here"), r);
        }
    }

    @Test
    void stopAllFromTheUser() throws Exception {
        try (Session s = open(List.of(js("api.spawnAgent({task: 'a', background: true}); api.spawnAgent({task: 'b', background: true})"), "ok"),
                Map.of("sub-1", List.of(js("while (true) {}"), "never"), "sub-2", List.of(js("while (true) {}"), "never")))) {
            s.loop().turn("go", d -> {});
            assertEquals(2, s.runningSubagents().size());
            assertFalse(s.stopSubagent("sub-9"));
            assertTrue(s.stopSubagent("sub-1"));
            assertEquals(List.of("sub-2"), s.runningSubagents());
            assertEquals(List.of("sub-2"), s.stopSubagents());
            assertTrue(s.runningSubagents().isEmpty());
            assertEquals("stopped", s.subagents().get(1).status());
        }
    }

    /** Spec 032: the user talks to a finished subagent; its questions are asked only while it is attended. */
    @Test
    void theUserTalksToASubagentAndAnswersItsQuestions() throws Exception {
        try (Session s = open(List.of(js("JSON.parse(api.waitAgent(JSON.parse(api.spawnAgent({task: 'pick a color', background: true})).agent)).reply"),
                "It asks which color."),
                Map.of("sub-1", List.of("Which color?", js("api.writeFile('a.txt', 'blue')"), "Saved blue.", js("api.writeFile('a.txt', 'red')"),
                        "Could not save red.")))) {
            s.loop().turn("go", d -> {});
            assertTrue(resultOf(1).contains("Which color?"), resultOf(1));
            assertTrue(s.subagentOpen("sub-1"));
            s.attend("sub-1");
            TurnResult r = s.talk("sub-1", "blue", d -> {});
            assertEquals("Saved blue.", r.reply());
            assertEquals(1, asked.get(), "its question reached the user");
            assertEquals("blue", Files.readString(ws.resolve("a.txt")));
            var req = subs.get("sub-1").requests().get(1);
            assertTrue(req.messages().stream().anyMatch(m -> m.content().equals("Which color?")), "its own conversation goes on");
            assertTrue(req.messages().getLast().content().startsWith(SubagentRunner.FROM_USER) && req.messages().getLast().content().endsWith("blue"));
            s.attend(null);
            s.talk("sub-1", "now red", d -> {});
            String denied = subs.get("sub-1").requests().get(4).messages().getLast().content();
            assertTrue(denied.contains("no one can answer while this runs in the background"), "unattended again: " + denied);
            assertEquals(1, asked.get());
            assertEquals("done", s.subagents().getFirst().status());
            assertTrue(assertThrows(IllegalArgumentException.class, () -> s.talk("sub-9", "x", d -> {})).getMessage().contains("no subagent sub-9"));
        }
    }

    @Test
    void theOldestIdleSubagentsAreClosedBeyondTheLimit() throws Exception {
        settings = Settings.parse("{\"agent\": {\"subagentsPerTurn\": 20}}", "s");
        int n = SubagentRunner.MAX_OPEN + 1;
        try (Session s = open(List.of(js("for (let i = 0; i < " + n + "; i++) { api.spawnAgent('t' + i); } 'ok'"), "done"), Map.of())) {
            s.loop().turn("go", d -> {});
            assertFalse(s.subagentOpen("sub-1"), "the oldest idle one was closed");
            assertTrue(s.subagentOpen("sub-2") && s.subagentOpen("sub-" + n));
            assertTrue(assertThrows(IllegalArgumentException.class, () -> s.talk("sub-1", "x", d -> {})).getMessage().contains("closed"));
        }
    }
}
