package dev.jharness.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.agent.Session;
import dev.jharness.config.Settings;
import dev.jharness.config.UiSettings;
import dev.jharness.events.Event;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;
import dev.jharness.events.SessionLog;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.Prompter;

/** Spec 042: the status line's values, its built-in line, the script, and the refresh throttle. */
class StatusLineTest {

    @TempDir
    Path tmp;

    private static Event event(String agent, int turn, String type, com.fasterxml.jackson.databind.node.ObjectNode body) {
        EventBus bus = new EventBus("s-test");
        return bus.publish(new Origin(agent, turn), type, body);
    }

    @Test
    void valuesComeFromEvents() {
        StatusState s = new StatusState();
        s.profile = "claude";
        s.model = "claude-opus-5-5";
        s.contextWindow = 200_000;
        s.workspace = "shop";
        assertEquals("claude (claude-opus-5-5) · ~0% of 200K · ask · shop", s.line(UiSettings.STATUS_ITEMS));
        assertTrue(s.apply(event("main", 3, "turn.started", Json.object().put("prompt", "go"))));
        assertTrue(s.apply(event("main", 3, "model.completed", Json.object().put("provider", "anthropic").put("model", "m").put("stopReason", "end_turn")
                .put("inputTokens", 80_000).put("outputTokens", 2_000).put("cacheReadTokens", 76_500))));
        assertTrue(s.apply(event("sub-1", 1, "subagent.started", Json.object().put("task", "t").put("customAgent", "default").put("model", "m")
                .put("maxSteps", 5).put("background", true))));
        assertTrue(s.apply(event("sub-2", 1, "subagent.started", Json.object().put("task", "t").put("customAgent", "default").put("model", "m")
                .put("maxSteps", 5).put("background", true))));
        assertTrue(s.apply(event("sub-2", 1, "subagent.waiting", Json.object().put("summary", "q").put("api", "ask"))));
        assertFalse(s.apply(event("sub-3", 1, "subagent.started", Json.object().put("task", "t").put("customAgent", "default").put("model", "m")
                .put("maxSteps", 5).put("background", false))), "a foreground subagent isn't listed");
        assertFalse(s.apply(event("main", 3, "model.delta", Json.object().put("text", "x"))), "streamed chunks never refresh");
        assertFalse(s.apply(event("sub-1", 1, "model.completed", Json.object().put("provider", "p").put("model", "m").put("stopReason", "s")
                .put("inputTokens", 5))), "a subagent's count isn't the main context");
        assertEquals("claude (claude-opus-5-5) · 41% of 200K · ask · 1 running, 1 held · cache 77K read · turn 3 · working · shop", s.line(UiSettings.STATUS_ITEMS));
        assertEquals("turn 3 · working · 41% of 200K", s.line(List.of("turn", "context")));
        s.plan = true;
        assertTrue(s.line(List.of("mode")).equals("[plan]"));
        assertTrue(s.apply(event("sub-1", 1, "subagent.completed", Json.object().put("stopReason", "done").put("steps", 1).put("durationMs", 5))));
        JsonNode snap = s.snapshot(UiSettings.STATUS_ITEMS);
        assertEquals(41, snap.get("context").get("percent").asInt());
        assertEquals(0, snap.get("agents").get("running").asInt());
        assertEquals(1, snap.get("agents").get("held").asInt());
        assertEquals(76_500, snap.get("cache").get("readTokens").asInt());
        assertEquals(3, snap.get("turn").asInt());
        assertTrue(snap.get("busy").asBoolean());
        assertTrue(snap.get("line").asText().contains("1 held"));
        // Spec 055: a held main-agent question counts as held; the turn's end clears "working".
        assertTrue(s.apply(event("main", 3, "question.waiting", Json.object().put("api", "writeFile").put("summary", "write a.txt"))));
        assertEquals(2, s.snapshot(UiSettings.STATUS_ITEMS).get("agents").get("held").asInt());
        assertTrue(s.apply(event("main", 3, "question.wait_ended", Json.object().put("api", "writeFile").put("outcome", "asked"))));
        assertTrue(s.apply(event("main", 3, "turn.completed", Json.object().put("stopReason", "done").put("steps", 1))));
        assertEquals("turn 3", s.line(List.of("turn")));
        assertEquals("1.5K", StatusState.kilo(1_500));
        assertEquals("33K", StatusState.kilo(32_768));
        assertEquals("1M", StatusState.kilo(1_000_000));
    }

    private StatusScript script(String js) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home-" + Math.abs(js.hashCode())));
        Path f = Files.createDirectories(home.resolve(".jharness")).resolve("statusline.js");
        Files.writeString(f, js);
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        return StatusScript.load(home, Settings.defaults().sandbox());
    }

    @Test
    void theScriptGetsTheSnapshotAndNoApi() throws Exception {
        try (StatusScript s = script("function status(s) { return s.model + ' ' + s.context.percent + '% ' + typeof api + '\\u001b[31m'; }")) {
            StatusScript.Outcome o = s.run(Json.object().put("model", "q").set("context", Json.object().put("percent", 12)));
            assertTrue(o.ok(), String.valueOf(o));
            assertTrue(o.line().startsWith("q 12% undefined"), o.line());
            assertFalse(o.line().contains("\u001b"), "escaped for the terminal");
        }
    }

    @Test
    void scriptFailuresAreReported() throws Exception {
        try (StatusScript s = script("function status(s) { throw new Error('boom'); }")) {
            StatusScript.Outcome o = s.run(Json.object());
            assertEquals("error", o.reason());
            assertTrue(o.error().contains("boom"), o.error());
        }
        try (StatusScript s = script("function status(s) { return 42; }")) {
            assertEquals("type", s.run(Json.object()).reason());
        }
        try (StatusScript s = script("const x = 1;")) {
            assertTrue(s.run(Json.object()).error().contains("no status(s) function"));
        }
        try (StatusScript s = script("function status(s) { return api.readFile('a'); }")) {
            assertEquals("error", s.run(Json.object()).reason(), "no api");
        }
        try (StatusScript s = script("function status(s) { const a = []; while (true) { a.push('xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx' + a.length); } }")) {
            StatusScript.Outcome o = s.run(Json.object());
            assertFalse(o.ok());
            assertTrue(List.of("limit", "timeout", "error").contains(o.reason()), o.toString());
        }
        Path home = Files.createDirectories(tmp.resolve("home-open/.jharness"));
        Path f = Files.writeString(home.resolve("statusline.js"), "function status() { return 'x'; }");
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-rw-rw-"));
        var e = assertThrows(java.io.IOException.class, () -> StatusScript.load(tmp.resolve("home-open"), Settings.defaults().sandbox()));
        assertTrue(e.getMessage().contains("others can write it"), e.getMessage());
        assertNull(StatusScript.load(tmp.resolve("nobody"), Settings.defaults().sandbox()), "no file, no script");
    }

    @Test
    void aSlowScriptIsTurnedOff() throws Exception {
        try (StatusScript s = script("function status(s) { if (!s.slow) return 'warm'; const t = Date.now(); while (Date.now() - t < 5000) {} return 'late'; }")) {
            assertEquals("warm", s.run(Json.object()).line());
            StatusScript.Outcome o = s.run(Json.object().put("slow", true));
            assertEquals("timeout", o.reason(), o.toString());
            assertTrue(s.off());
        }
    }

    @Test
    void refreshesAreThrottledAndFailuresFallBack() throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home-t"));
        Path ws = Files.createDirectories(tmp.resolve("shop"));
        List<String> painted = new CopyOnWriteArrayList<>();
        List<String> notices = new CopyOnWriteArrayList<>();
        Path log;
        try (Session session = Session.open(home, ws, Settings.defaults(), Session.Access.prompter(op -> Prompter.Answer.ONCE),
                (bus, turns) -> ReplayProvider.ofReplies(List.of(), bus, turns));
                StatusScript bad = script("function status(s) { throw new Error('bad line'); }");
                StatusLine line = new StatusLine(session, UiSettings.STATUS_ITEMS, bad, painted::add, notices::add)) {
            session.bus().subscribe(line::onEvent);
            for (int i = 1; i <= 50; i++) {
                session.bus().publish(Origin.main(i), "turn.started", Json.object().put("prompt", "p" + i));
            }
            long until = System.currentTimeMillis() + 15_000;
            while ((painted.isEmpty() || !painted.getLast().contains("turn 50")) && System.currentTimeMillis() < until) {
                Thread.sleep(50);
            }
            assertTrue(painted.size() <= 5, "50 events, few refreshes: " + painted);
            assertTrue(painted.getLast().contains("turn 50") && painted.getLast().contains("shop"), "the default line in its place: " + painted);
            assertEquals(1, notices.size(), "one notice per distinct failure: " + notices);
            assertTrue(notices.getFirst().contains("bad line"), notices.toString());
            log = session.logPath();
        }
        List<JsonNode> events = SessionLog.read(log);
        assertEquals(1, events.stream().filter(e -> e.get("type").asText().equals("statusline.failed")).count());
        assertTrue(events.stream().anyMatch(e -> e.get("type").asText().equals("statusline.ready") && e.get("from").asText().equals("script")));
        assertTrue(events.stream().noneMatch(e -> e.get("type").asText().equals("sandbox.run_started") && e.path("agent").asText().equals("statusline")), "the script's isolate events stay off the log");
    }
}
