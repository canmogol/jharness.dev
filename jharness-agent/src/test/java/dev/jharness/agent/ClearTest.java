package dev.jharness.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.Settings;
import dev.jharness.events.SessionLog;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.PermissionGate;

/** Spec 064: /clear empties the conversation and the todo list; files, the session, its log and plan mode stay. */
class ClearTest {

    @TempDir
    Path tmp;

    @Test
    void clearStartsTheModelFreshAndKeepsTheFiles() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("a.txt"), "v0");
        ReplayProvider[] replay = new ReplayProvider[1];
        Path log;
        String session;
        try (Session s = Session.open(tmp.resolve("home"), ws, Settings.defaults(), Session.Access.gate(PermissionGate.allowAll()),
                (bus, turns) -> replay[0] = ReplayProvider.ofReplies(List.of(
                        "```js\napi.writeFile('a.txt', 'v1'); api.todos(['check a.txt'])\n```", "Wrote it.",
                        "Fresh start."), bus, turns))) {
            log = s.logPath();
            session = s.bus().session();
            s.loop().turn("write a.txt", d -> {});
            assertEquals(1, s.todos("main").size());
            int before = s.loop().conversation().size();
            assertTrue(before > 0);

            assertEquals(before, s.clear());
            assertTrue(s.loop().conversation().isEmpty());
            assertTrue(s.todos("main").isEmpty(), "the todo list goes with the conversation");
            assertEquals("v1", Files.readString(ws.resolve("a.txt")), "files stay as they are");
            assertEquals("Nothing to undo.", s.loop().undo(false).message());
            var e = assertThrows(IllegalArgumentException.class, () -> s.loop().rewind(0, false));
            assertTrue(e.getMessage().contains("turns up to 1 were cleared with /clear"), e.getMessage());
            assertEquals(0, s.clear(), "already empty");

            s.loop().turn("hello again", d -> {});
            var last = replay[0].requests().getLast().messages();
            assertEquals(1, last.size(), "the model sees only the new message: " + last);
            assertEquals("hello again", last.getFirst().content());
            assertEquals(List.of(2), s.loop().turns(), "turns go on counting");
            assertEquals(session, s.bus().session(), "the same session");
        }
        var events = SessionLog.read(log);
        assertTrue(events.stream().anyMatch(x -> x.get("type").asText().equals("conversation.cleared") && x.get("clearedThrough").asInt() == 1));
        assertTrue(events.stream().anyMatch(x -> x.get("type").asText().equals("turn.started") && x.get("prompt").asText().equals("write a.txt")),
                "the log keeps everything");

        // --resume continues after the clear: the earlier turns stay out of reach.
        try (Session r = Session.open(tmp.resolve("home"), ws, Settings.defaults(),
                Session.Access.gate(PermissionGate.allowAll()).resume(new SessionState.Resume(SessionState.find(tmp.resolve("home"), ws, null), false)),
                (bus, turns) -> ReplayProvider.ofReplies(List.of("unused"), bus, turns))) {
            assertEquals(2, r.loop().conversation().size(), "only the turn after the clear");
            assertThrows(IllegalArgumentException.class, () -> r.loop().rewind(0, false));
        }
    }
}
