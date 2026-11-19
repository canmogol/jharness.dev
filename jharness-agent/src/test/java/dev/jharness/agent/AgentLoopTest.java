package dev.jharness.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.Settings;
import dev.jharness.events.Event;
import dev.jharness.events.SessionLog;
import dev.jharness.llm.ChatMessage;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.DefaultGate;

class AgentLoopTest {

    @TempDir
    Path tmp;
    Session session;
    ReplayProvider replay;
    final List<Event> events = new ArrayList<>();

    private Session open(String settingsJson, String... replies) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.createDirectories(ws.resolve("src"));
        Files.writeString(ws.resolve("src/App.java"), "class App {\n  int answer() { return 41; }\n}\n");
        Settings s = Settings.parse(settingsJson, "test");
        session = Session.open(tmp.resolve("home"), ws, s, Session.Access.gate(new DefaultGate()), (bus, turns) -> {
            replay = ReplayProvider.ofReplies(List.of(replies), bus, turns);
            return replay;
        });
        session.bus().subscribe(events::add);
        return session;
    }

    @AfterEach
    void tearDown() throws Exception {
        if (session != null) {
            session.close();
        }
    }

    private String lastUserMessage(int request) {
        List<ChatMessage> m = replay.requests().get(request).messages();
        return m.getLast().content();
    }

    @Test
    void runsTheBlockAndSendsTheResultBack() throws Exception {
        open("{}", "Let me read it.\n```js\napi.readFile('src/App.java')\n```", "The answer method returns 41.");
        TurnResult r = session.loop().turn("What does answer return?", d -> {});
        assertEquals("done", r.stopReason());
        assertEquals(1, r.steps());
        assertEquals("The answer method returns 41.", r.reply());
        String fed = lastUserMessage(1);
        assertTrue(fed.startsWith(AgentLoop.RESULT_PREFIX), fed);
        assertTrue(fed.contains("return 41"), fed);
        assertTrue(replay.requests().getFirst().system().contains("declare const api"));
        assertEquals(List.of("turn.started", "loop.code", "loop.result", "turn.completed"),
                events.stream().map(Event::type).filter(t -> t.startsWith("turn.") || t.startsWith("loop.")).toList());
        assertTrue(events.stream().allMatch(e -> e.turn() == 1));
    }

    @Test
    void errorsGoBackWithLineNumbersAndTheNextStepIsFresh() throws Exception {
        open("{}", "```js\nglobalThis.left = 1;\nconst a = 1;\nnull.x;\n```", "```js\ntypeof left\n```", "ok");
        TurnResult r = session.loop().turn("go", d -> {});
        assertEquals(2, r.steps());
        String first = lastUserMessage(1);
        assertTrue(first.contains("Error at line 3"), first);
        assertTrue(lastUserMessage(2).contains("Result: undefined"), lastUserMessage(2));
        List<Event> results = events.stream().filter(e -> e.type().equals("loop.result")).toList();
        assertEquals("error", results.get(0).body().get("status").asText());
        assertEquals(3, results.get(0).body().get("line").asInt());
        assertFalse(results.get(0).body().get("error").asText().isBlank(), results.get(0).body().toString());
        assertFalse(results.get(1).body().has("error"), results.get(1).body().toString());
        assertFalse(results.get(1).body().has("line"));
    }

    @Test
    void limitsGoBackWithTheLimitName() throws Exception {
        open("{\"sandbox\":{\"main\":{\"sandbox.MaxCPUTime\":\"1s\"}}}", "```js\nwhile (true) {}\n```", "stopping");
        session.loop().turn("spin", d -> {});
        assertTrue(lastUserMessage(1).contains("sandbox.MaxCPUTime"), lastUserMessage(1));
        assertTrue(events.stream().anyMatch(e -> e.type().equals("loop.result") && e.body().get("status").asText().equals("limit")
                && e.body().has("error")));
    }

    @Test
    void stopsAtMaxSteps() throws Exception {
        open("{\"agent\":{\"maxSteps\":2}}", "```js\n1\n```", "```js\n2\n```", "```js\n3\n```");
        TurnResult r = session.loop().turn("loop", d -> {});
        assertEquals("max_steps", r.stopReason());
        assertEquals(2, r.steps());
        assertEquals(0, replay.remaining());
    }

    @Test
    void modelErrorsEndTheTurn() throws Exception {
        open("{}", "```js\n1\n```");
        TurnResult r = session.loop().turn("go", d -> {});
        assertEquals("error", r.stopReason());
        assertTrue(r.error().contains("no more replies"));
        var done = events.stream().filter(e -> e.type().equals("turn.completed")).findFirst().orElseThrow();
        assertEquals("error", done.body().get("stopReason").asText());
    }

    @Test
    void resultsAreCapped() throws Exception {
        open("{\"timeouts\":{\"outputChars\":100}}", "```js\n'x'.repeat(1000) + 'END'\n```", "ok");
        session.loop().turn("big", d -> {});
        String fed = lastUserMessage(1);
        assertTrue(fed.contains("characters cut"), fed);
        assertTrue(fed.endsWith("END"), fed);
        assertTrue(fed.length() < 200, String.valueOf(fed.length()));
    }

    @Test
    void laterBlocksDontRunAndTheModelIsTold() throws Exception {
        open("{}", "```js\n'first'\n```\nand\n```js\n'second'\n```\n```javascript\n'third'\n```", "```js\n'second'\n```", "done");
        TurnResult r = session.loop().turn("go", d -> {});
        assertEquals("done", r.stopReason());
        assertEquals(2, r.steps());
        String fed = lastUserMessage(1);
        assertTrue(fed.contains("first"), fed);
        assertFalse(fed.contains("third"), fed);
        assertTrue(fed.endsWith("\n\nNote: your reply had 2 more js block(s) after the first; they did not run. "
                + "Send the next one in your next reply."), fed);
        assertFalse(lastUserMessage(2).contains("Note:"), lastUserMessage(2));
        List<Event> code = events.stream().filter(e -> e.type().equals("loop.code")).toList();
        assertEquals(2, code.get(0).body().get("ignoredBlocks").asInt());
        assertFalse(code.get(1).body().has("ignoredBlocks"), code.get(1).body().toString());
    }

    @Test
    void theNoteIsNeverCut() throws Exception {
        open("{\"timeouts\":{\"outputChars\":100}}", "```js\n'x'.repeat(1000) + 'END'\n```\n```js\n'next'\n```", "ok");
        session.loop().turn("big", d -> {});
        String fed = lastUserMessage(1);
        assertTrue(fed.contains("characters cut"), fed);
        assertTrue(fed.contains("END\n\nNote: your reply had 1 more js block(s)"), fed);
        assertTrue(fed.endsWith("Send the next one in your next reply."), fed);
    }

    @Test
    void conversationAndTurnNumbersCarryAcrossTurns() throws Exception {
        open("{}", "first answer", "second answer");
        session.loop().turn("one", d -> {});
        List<String> deltas = new ArrayList<>();
        session.loop().turn("two", deltas::add);
        assertEquals(List.of("second answer"), deltas);
        assertEquals(4, session.loop().conversation().size());
        assertEquals(3, replay.requests().get(1).messages().size());
        assertTrue(events.stream().filter(e -> e.type().equals("turn.started")).map(Event::turn).toList().equals(List.of(1, 2)));
        var logged = SessionLog.read(session.logPath());
        assertEquals("session.started", logged.getFirst().get("type").asText());
    }
}
