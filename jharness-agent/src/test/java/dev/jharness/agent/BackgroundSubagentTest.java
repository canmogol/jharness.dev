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

/**
 * Specs 027 and 054: background subagents. Each subagent gets its own replay provider, so threads don't race for replies.
 * A turn never waits for one; its reply starts a main turn ({@link Session#deliveryTurn}).
 */
class BackgroundSubagentTest {

    @TempDir
    Path tmp;
    Path ws;
    ReplayProvider main;
    final Map<String, ReplayProvider> subs = new ConcurrentHashMap<>();
    final AtomicInteger asked = new AtomicInteger();
    Settings settings = Settings.defaults();
    boolean interactive;

    private Session open(List<String> mainReplies, Map<String, List<String>> subReplies) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("a.txt"), "old");
        Session s = Session.open(home, ws, settings, Session.Access.prompter(op -> {
            asked.incrementAndGet();
            return Prompter.Answer.ONCE;
        }).interactive(interactive), (bus, turns) -> main = ReplayProvider.ofReplies(mainReplies, bus, turns));
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

    /** The main turn ends at once; the subagent's reply starts a turn of its own (spec 054 AC-02). */
    @Test
    void aBackgroundSubagentsReplyStartsAMainTurn() throws Exception {
        Path log;
        try (Session s = open(List.of(
                js("const a = JSON.parse(api.spawnAgent({task: 'read a.txt', background: true}));\na.agent + ' ' + a.status"),
                "Started it.", "It says old."),
                Map.of("sub-1", List.of(js("api.readFile('a.txt')"), "a.txt says old.")))) {
            TurnResult first = s.loop().turn("go", d -> {});
            assertEquals("Started it.", first.reply());
            assertTrue(s.awaitFinished(), "its reply is ready");
            assertTrue(s.deliveryPending());
            TurnResult auto = s.deliveryTurn(d -> {});
            assertEquals("It says old.", auto.reply());
            assertNull(s.deliveryTurn(d -> {}), "delivered once");
            assertFalse(s.awaitFinished(), "none left running");
            log = s.logPath();
        }
        assertTrue(resultOf(1).contains("sub-1 running"), resultOf(1));
        String delivered = resultOf(2);
        assertTrue(delivered.startsWith("[Background subagents that finished since your last turn]"), delivered);
        assertTrue(delivered.contains("sub-1 (done after 1 steps): a.txt says old."), delivered);
        assertTrue(delivered.endsWith(Session.AUTO_PROMPT), delivered);
        List<JsonNode> events = SessionLog.read(log);
        List<JsonNode> turns = of(events, "turn.started").stream().filter(e -> e.get("agent").asText().equals("main")).toList();
        assertFalse(turns.get(0).has("auto"));
        assertTrue(turns.get(1).get("auto").asBoolean(), "the delivery turn is marked");
        JsonNode started = of(events, "subagent.started").getFirst();
        assertTrue(started.get("background").asBoolean());
        assertEquals("sub-1", started.get("agent").asText());
        assertTrue(of(events, "loop.code").stream().anyMatch(e -> e.get("agent").asText().equals("sub-1")));
    }

    @Test
    void stopAgentStopsIt() throws Exception {
        Path log;
        try (Session s = open(List.of(
                js("const a = JSON.parse(api.spawnAgent({task: 'spin', background: true})).agent;\n"
                        + "const before = JSON.parse(api.agents())[0].status;\n"
                        + "const st = JSON.parse(api.stopAgent(a));\n"
                        + "const after = JSON.parse(api.agents())[0];\n"
                        + "before + ' ' + st.status + ' ' + after.status + ' ' + after.background + ' ' + after.task"),
                "done"),
                Map.of("sub-1", List.of(js("while (true) {}"), "never")))) {
            s.loop().turn("go", d -> {});
            log = s.logPath();
        }
        assertTrue(resultOf(1).contains("running stopped stopped true spin"), resultOf(1));
        JsonNode done = of(SessionLog.read(log), "subagent.completed").getFirst();
        assertEquals("stopped", done.get("stopReason").asText());
    }

    @Test
    void theRunningCapAndBadRequestsAreRefused() throws Exception {
        settings = Settings.parse("{\"agent\": {\"subagentsRunning\": 1}}", "s");
        try (Session s = open(List.of(
                js("const out = [];\nconst a = JSON.parse(api.spawnAgent({task: 'spin', background: true})).agent;\n"
                        + "for (const f of [() => api.spawnAgent({task: 'two', background: true}), () => api.spawnAgent({task: 'x', background: 'yes'}),\n"
                        + "    () => api.stopAgent('sub-9'), () => api.waitAgent({agent: a}), () => api.stopAgent('')]) {\n"
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
        assertTrue(errors.contains("end your turn, and start this one when one of them has finished"), errors);
        assertTrue(errors.contains("api.waitAgent was removed: end your turn instead"), errors);
        assertTrue(errors.contains("stopAgent needs a subagent name"), errors);
        assertFalse(errors.contains("no error"), errors);
    }

    @Test
    void aBackgroundSubagentNeverAsks() throws Exception {
        try (Session s = open(List.of(
                js("api.spawnAgent({task: 'write', background: true}); 'started'"), "done"),
                Map.of("sub-1", List.of(js("api.writeFile('a.txt', 'x')"), "Could not write.")))) {
            s.loop().turn("go", d -> {});
            waitIdle(s);
            String subResult = subs.get("sub-1").requests().get(1).messages().getLast().content();
            assertTrue(subResult.contains("no one answered while this ran in the background"), subResult);
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
            assertTrue(r.contains("api.waitAgent was removed"), r);
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
            assertFalse(s.deliveryPending(), "a stopped subagent starts no turn (spec 054)");
        }
    }

    /** Spec 032: the user talks to a finished subagent; its questions are asked only while it is attended. */
    @Test
    void theUserTalksToASubagentAndAnswersItsQuestions() throws Exception {
        try (Session s = open(List.of(js("api.spawnAgent({task: 'pick a color', background: true}); 'started'"), "Started.",
                "It asks which color."),
                Map.of("sub-1", List.of("Which color?", js("api.writeFile('a.txt', 'blue')"), "Saved blue.", js("api.writeFile('a.txt', 'red')"),
                        "Could not save red.")))) {
            s.loop().turn("go", d -> {});
            waitIdle(s);
            s.deliveryTurn(d -> {});
            assertTrue(resultOf(2).contains("Which color?"), resultOf(2));
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
            assertTrue(denied.contains("no one answered while this ran in the background"), "unattended again: " + denied);
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

    private static void waitFor(java.util.function.BooleanSupplier c) throws InterruptedException {
        long end = System.currentTimeMillis() + 20_000;
        while (!c.getAsBoolean() && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        assertTrue(c.getAsBoolean(), "condition reached");
    }

    /** Spec 033: in an interactive session a background question waits until the user switches to the subagent. */
    @Test
    void aHeldQuestionIsAskedWhenTheUserSwitches() throws Exception {
        interactive = true;
        Path log;
        try (Session s = open(List.of(js("api.spawnAgent({task: 'write', background: true}); 'started'"), "Started."),
                Map.of("sub-1", List.of(js("api.writeFile('a.txt', 'x')"), "Wrote it.")))) {
            s.loop().turn("go", d -> {});
            waitFor(() -> s.subagents().getFirst().status().equals("waiting"));
            assertEquals(0, asked.get(), "not asked while no one is switched to it");
            assertEquals("old", Files.readString(ws.resolve("a.txt")));
            s.attend("sub-1");
            waitFor(() -> s.runningSubagents().isEmpty());
            assertEquals(1, asked.get());
            assertEquals("x", Files.readString(ws.resolve("a.txt")));
            log = s.logPath();
        }
        List<JsonNode> events = SessionLog.read(log);
        assertEquals("write a.txt (1 bytes)", of(events, "subagent.waiting").getFirst().get("summary").asText());
        assertEquals("asked", of(events, "subagent.wait_ended").getFirst().get("outcome").asText());
    }

    @Test
    void aHeldQuestionIsDeniedAfterTheWaitOrWhenStopped() throws Exception {
        interactive = true;
        Path log;
        try (Session s = open(List.of(js("api.spawnAgent({task: 'a', background: true}); 'started'"), "Started.",
                js("api.spawnAgent({task: 'b', background: true}); 'started'"), "Started again."),
                Map.of("sub-1", List.of(js("api.writeFile('a.txt', 'x')"), "Gave up."), "sub-2", List.of(js("api.writeFile('a.txt', 'y')"), "never")))) {
            s.runner().questionWait = java.time.Duration.ofMillis(300);
            s.loop().turn("go", d -> {});
            waitFor(() -> s.runningSubagents().isEmpty());
            String r = subs.get("sub-1").requests().get(1).messages().getLast().content();
            assertTrue(r.contains("no one answered while this ran in the background"), r);
            s.runner().questionWait = java.time.Duration.ofMinutes(5);
            s.loop().turn("again", d -> {});
            waitFor(() -> s.subagents().get(1).status().equals("waiting"));
            long t0 = System.nanoTime();
            assertTrue(s.stopSubagent("sub-2"));
            assertTrue(System.nanoTime() - t0 < 4_000_000_000L, "a stop wakes the held question");
            log = s.logPath();
        }
        assertEquals(0, asked.get());
        assertEquals(List.of("timeout", "stopped"), of(SessionLog.read(log), "subagent.wait_ended").stream().map(e -> e.get("outcome").asText()).toList());
    }

    @Test
    void denyModeAndHeadlessSessionsNeverWait() throws Exception {
        interactive = true;
        settings = Settings.parse("{\"agent\": {\"backgroundQuestions\": \"deny\"}}", "s");
        try (Session s = open(List.of(js("api.spawnAgent({task: 'w', background: true}); 'started'"), "Started.", "done"),
                Map.of("sub-1", List.of(js("api.writeFile('a.txt', 'x')"), "Denied.")))) {
            s.loop().turn("go", d -> {});
            waitIdle(s);
            s.deliveryTurn(d -> {});
            assertTrue(resultOf(2).contains("Denied."), resultOf(2));
        }
        assertThrows(dev.jharness.config.ConfigException.class, () -> Settings.parse("{\"agent\": {\"backgroundQuestions\": \"ask\"}}", "s"));
        assertThrows(dev.jharness.config.ConfigException.class, () -> Settings.parse("{\"agent\": {\"backgroundQuestionWait\": \"2s\"}}", "s"));
        assertEquals(java.time.Duration.ofMinutes(10), Settings.defaults().agent().backgroundQuestionWait());
    }

    /** Spec 054 AC-04: /answer asks a held question without switching to the subagent. */
    @Test
    void answerAsksAHeldQuestionWithoutSwitching() throws Exception {
        interactive = true;
        Path log;
        try (Session s = open(List.of(js("api.spawnAgent({task: 'write', background: true}); 'started'"), "Started."),
                Map.of("sub-1", List.of(js("api.writeFile('a.txt', 'x')"), "Wrote it.")))) {
            s.loop().turn("go", d -> {});
            waitFor(() -> s.subagents().getFirst().status().equals("waiting"));
            assertEquals(List.of("sub-1"), s.heldSubagents());
            assertEquals(0, asked.get());
            assertTrue(s.answer("sub-1"), "it held a question");
            assertEquals(1, asked.get(), "asked once, through the real prompter");
            waitFor(() -> s.runningSubagents().isEmpty());
            assertEquals("x", Files.readString(ws.resolve("a.txt")));
            assertTrue(s.heldSubagents().isEmpty());
            assertFalse(s.answer("sub-1"), "nothing left to answer");
            assertTrue(assertThrows(IllegalArgumentException.class, () -> s.answer("sub-9")).getMessage().contains("no subagent sub-9"));
            log = s.logPath();
        }
        assertEquals("asked", of(SessionLog.read(log), "subagent.wait_ended").getFirst().get("outcome").asText());
    }

    /** Spec 054 AC-04: an /answer that comes after the wait ended doesn't answer the subagent's next question. */
    @Test
    void aLateAnswerDoesntCarryOver() throws Exception {
        interactive = true;
        try (Session s = open(List.of(js("api.spawnAgent({task: 'two writes', background: true}); 'started'"), "Started."),
                Map.of("sub-1", List.of(js("try { api.writeFile('a.txt', 'x'); } catch (e) {} 'one'"), js("api.writeFile('a.txt', 'y')"), "Done.")))) {
            s.runner().questionWait = java.time.Duration.ofMillis(300);
            s.loop().turn("go", d -> {});
            waitFor(() -> s.runningSubagents().isEmpty());
            assertFalse(s.answer("sub-1"), "both questions timed out; no answer was pending");
            assertEquals(0, asked.get());
            assertEquals("old", Files.readString(ws.resolve("a.txt")));
        }
    }

    @Test
    void autoTurnsSetting() throws Exception {
        assertEquals(10, Settings.defaults().agent().autoTurns());
        assertEquals(0, Settings.parse("{\"agent\": {\"autoTurns\": 0}}", "s").agent().autoTurns());
        assertEquals(100, Settings.parse("{\"agent\": {\"autoTurns\": 100}}", "s").agent().autoTurns());
        for (String bad : List.of("101", "-1", "\"5\"", "1.5")) {
            var e = assertThrows(dev.jharness.config.ConfigException.class, () -> Settings.parse("{\"agent\": {\"autoTurns\": " + bad + "}}", "s"));
            assertTrue(e.getMessage().contains("agent.autoTurns"), e.getMessage());
        }
    }
}
