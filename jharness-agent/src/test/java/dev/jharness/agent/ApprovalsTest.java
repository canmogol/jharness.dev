package dev.jharness.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.Settings;
import dev.jharness.events.Event;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.Prompter;

/** The permission engine in a session: questions asked mid-run, the wall clock paused (AC-02, AC-04). */
class ApprovalsTest {

    @TempDir
    Path tmp;
    ReplayProvider replay;

    private String lastResult(ReplayProvider r) {
        return r.requests().getLast().messages().getLast().content();
    }

    private Path ws() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("a.txt"), "old");
        return ws;
    }

    private Session open(Path ws, String settings, Prompter p, String... replies) throws Exception {
        return Session.open(tmp.resolve("home"), ws, Settings.parse(settings, "t"), Session.Access.prompter(p), (bus, turns) -> {
            replay = ReplayProvider.ofReplies(List.of(replies), bus, turns);
            return replay;
        });
    }

    @Test
    void slowAnswerDoesNotCountTowardTheWallClock() throws Exception {
        Path ws = ws();
        List<Event> events = new ArrayList<>();
        Prompter slow = op -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Prompter.Answer.ONCE;
        };
        try (Session s = open(ws, "{\"sandbox\":{\"main\":{\"wallClock\":\"1s\"}}}", slow,
                "```js\napi.writeFile('a.txt', 'new');\napi.readFile('a.txt')\n```", "done")) {
            s.bus().subscribe(events::add);
            TurnResult r = s.loop().turn("change a.txt", d -> {});
            assertEquals("done", r.stopReason());
            assertTrue(lastResult(replay).contains("Result: new"), lastResult(replay));
        }
        assertEquals("new", Files.readString(ws.resolve("a.txt")));
        assertTrue(events.stream().anyMatch(e -> e.type().equals("permission.asked")));
    }

    @Test
    void deniedAndHeadless() throws Exception {
        Path ws = ws();
        try (Session s = open(ws, "{}", Prompter.none(), "```js\napi.writeFile('a.txt', 'new')\n```", "ok")) {
            s.loop().turn("change", d -> {});
            assertTrue(lastResult(replay).contains("denied by the user"), lastResult(replay));
        }
        assertEquals("old", Files.readString(ws.resolve("a.txt")));
    }

    @Test
    void planModeFromSettings() throws Exception {
        Path ws = ws();
        try (Session s = open(ws, "{\"permissions\":{\"mode\":\"plan\"}}", op -> Prompter.Answer.ONCE,
                "```js\napi.writeFile('a.txt', 'new')\n```", "ok")) {
            s.loop().turn("change", d -> {});
            assertTrue(lastResult(replay).contains("plan mode is read-only"), lastResult(replay));
        }
        assertEquals("old", Files.readString(ws.resolve("a.txt")));
    }

    @Test
    void alwaysCarriesToTheNextSession() throws Exception {
        Path ws = ws();
        List<String> asked = new ArrayList<>();
        try (Session s = open(ws, "{}", op -> {
            asked.add(op.summary());
            return Prompter.Answer.ALWAYS;
        }, "```js\napi.writeFile('a.txt', 'one')\n```", "ok")) {
            s.loop().turn("write", d -> {});
        }
        try (Session s = open(ws, "{}", op -> {
            asked.add(op.summary());
            return Prompter.Answer.DENY;
        }, "```js\napi.writeFile('a.txt', 'two')\n```", "ok")) {
            s.loop().turn("write again", d -> {});
        }
        assertEquals(1, asked.size(), asked.toString());
        assertEquals("two", Files.readString(ws.resolve("a.txt")));
    }
}
