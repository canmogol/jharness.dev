package dev.jharness.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.Settings;
import dev.jharness.events.SessionLog;
import dev.jharness.llm.ChatMessage;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.Prompter;

/** Spec 043: a session's conversation is saved after each turn; --resume continues it and --fork copies it. */
class ResumeTest {

    @TempDir
    Path tmp;
    Path home;
    Path ws;
    ReplayProvider replay;

    @BeforeEach
    void setUp() throws Exception {
        home = Files.createDirectories(tmp.resolve("home"));
        ws = Files.createDirectories(tmp.resolve("ws")).toRealPath();
        Files.writeString(ws.resolve("a.txt"), "one");
    }

    interface Script {
        void run(Session s) throws Exception;
    }

    private String session(SessionState.Resume from, List<String> replies, Script script) throws Exception {
        try (Session s = Session.open(home, ws, Settings.defaults(), Session.Access.prompter(op -> Prompter.Answer.ONCE).resume(from),
                (bus, turns) -> replay = ReplayProvider.ofReplies(replies, bus, turns))) {
            script.run(s);
            return s.bus().session();
        }
    }

    private static String js(String code) {
        return "```js\n" + code + "\n```";
    }

    @Test
    void eachTurnIsSavedPrivately() throws Exception {
        String id = session(null, List.of("Hello.", "Again."), s -> {
            s.turn("first", d -> {});
            s.turn("second", d -> {});
        });
        Path f = SessionState.pathFor(home, ws, id);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));
        SessionState st = SessionState.find(home, ws, "last");
        assertEquals(id, st.session());
        assertEquals(2, st.turn());
        assertEquals(List.of("first", "Hello.", "second", "Again."), st.conversation().stream().map(ChatMessage::content).toList());
        assertEquals("first", st.firstPrompt());
        assertEquals(ws.toString(), st.workspace());
        assertNull(st.forkedFrom());
    }

    @Test
    void resumeContinuesTheSameSession() throws Exception {
        String id = session(null, List.of(js("api.writeFile('a.txt', 'two')"), "Wrote two."), s -> s.turn("write two", d -> {}));
        SessionState saved = SessionState.find(home, ws, null);
        String again = session(new SessionState.Resume(saved, false), List.of(js("api.writeFile('a.txt', 'three')"), "Wrote three."), s -> {
            assertEquals(4, s.loop().conversation().size(), "the saved conversation is back");
            TurnResult r = s.turn("now three", d -> {});
            assertEquals("done", r.stopReason());
            var first = replay.requests().getFirst().messages();
            assertEquals("write two", first.getFirst().content(), "the model sees the earlier turns");
            assertEquals("now three", first.get(first.size() - 1).content());
            assertEquals(List.of(2), s.loop().turns(), "turns go on from the saved one");
            var e = assertThrows(IllegalArgumentException.class, () -> s.loop().rewind(0, false));
            assertTrue(e.getMessage().contains("before the session was resumed"), e.getMessage());
            assertTrue(s.loop().undo(false).done());
            assertEquals("two", Files.readString(ws.resolve("a.txt")), "the new turn's file change is undone");
            assertEquals(4, s.loop().conversation().size());
            assertEquals("Nothing to undo.", s.loop().undo(false).message(), "older turns stay");
        });
        assertEquals(id, again, "same id");
        List<JsonNode> log = SessionLog.read(SessionLog.pathFor(home, ws, id));
        assertEquals(2, log.stream().filter(e -> e.get("type").asText().equals("session.started")).count(), "one log, appended");
        JsonNode resumed = log.stream().filter(e -> e.get("type").asText().equals("session.resumed")).findFirst().orElseThrow();
        assertFalse(resumed.get("fork").asBoolean());
        assertEquals(1, resumed.get("turns").asInt());
        assertEquals(4, resumed.get("messages").asInt());
        assertEquals(4, SessionState.find(home, ws, id).conversation().size(), "the undo was saved");
    }

    @Test
    void forkLeavesTheOriginalAlone() throws Exception {
        String id = session(null, List.of("Base."), s -> s.turn("base", d -> {}));
        SessionState saved = SessionState.find(home, ws, id);
        String forked = session(new SessionState.Resume(saved, true), List.of("Branch."), s -> {
            assertEquals(SessionState.find(home, ws, s.bus().session()).forkedFrom(), id, "saved at once, with its origin");
            s.turn("branch", d -> {});
        });
        assertNotEquals(id, forked);
        assertEquals(2, SessionState.find(home, ws, id).conversation().size(), "the original is unchanged");
        SessionState f = SessionState.find(home, ws, forked);
        assertEquals(4, f.conversation().size());
        assertEquals(2, f.turn());
        assertEquals(id, f.forkedFrom());
        assertEquals("base", f.firstPrompt());
        assertEquals(forked, SessionState.find(home, ws, "last").session(), "the newest");
    }

    @Test
    void findingASession() throws Exception {
        var none = assertThrows(IOException.class, () -> SessionState.find(home, ws, null));
        assertTrue(none.getMessage().contains("no session to resume"), none.getMessage());
        String a = session(null, List.of("A."), s -> s.turn("a", d -> {}));
        Thread.sleep(5);
        String b = session(null, List.of("B."), s -> s.turn("b", d -> {}));
        assertEquals(b, SessionState.find(home, ws, null).session());
        assertEquals(a, SessionState.find(home, ws, a).session());
        assertEquals(a, SessionState.find(home, ws, a.substring(0, a.length() - 2)).session(), "a unique start");
        var many = assertThrows(IOException.class, () -> SessionState.find(home, ws, "s-"));
        assertTrue(many.getMessage().contains("matches 2 sessions"), many.getMessage());
        var gone = assertThrows(IOException.class, () -> SessionState.find(home, ws, "s-nope"));
        assertTrue(gone.getMessage().contains("jharness log lists them"), gone.getMessage());
        Files.writeString(SessionState.pathFor(home, ws, "s-broken"), "{not json");
        assertEquals(2, SessionState.list(home, ws).size(), "a broken file is skipped");
        Path other = Files.createDirectories(tmp.resolve("other"));
        assertThrows(IOException.class, () -> SessionState.find(home, other, a), "sessions belong to their workspace");
    }

    @Test
    void aCompactedSessionResumesWithItsSummary() throws Exception {
        session(null, List.of("Hi.", "Summary of the talk."), s -> {
            s.turn("hello", d -> {});
            s.loop().compact();
        });
        SessionState saved = SessionState.find(home, ws, null);
        assertEquals(1, saved.compactedThrough());
        assertTrue(saved.conversation().getFirst().content().contains("Summary of the talk."), saved.conversation().toString());
    }
}
