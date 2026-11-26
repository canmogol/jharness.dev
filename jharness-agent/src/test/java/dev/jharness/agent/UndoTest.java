package dev.jharness.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.Settings;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.sandbox.Checkpoints;
import dev.jharness.security.PermissionGate;

class UndoTest {

    @TempDir
    Path tmp;

    @Test
    void undoAndRewindRestoreFilesAndCutTheConversation() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("a.txt"), "v0");
        List<String> replies = List.of(
                "```js\napi.writeFile('a.txt', 'v1')\n```", "turn 1 done",
                "```js\napi.editFile('a.txt', 'v1', 'v2'); api.writeFile('b.txt', 'new')\n```", "turn 2 done",
                "```js\napi.writeFile('a.txt', 'v3')\n```", "turn 3 done");
        try (Session s = Session.open(tmp.resolve("home"), ws, Settings.defaults(), Session.Access.gate(PermissionGate.allowAll()),
                (bus, turns) -> ReplayProvider.ofReplies(replies, bus, turns))) {
            s.loop().turn("one", d -> {});
            s.loop().turn("two", d -> {});
            s.loop().turn("three", d -> {});
            assertEquals("v3", Files.readString(ws.resolve("a.txt")));
            assertEquals(List.of(1, 2, 3), s.loop().turns());

            Checkpoints.Restore u = s.loop().undo(false);
            assertTrue(u.done(), u.message());
            assertEquals("v2", Files.readString(ws.resolve("a.txt")));
            assertEquals(List.of(1, 2), s.loop().turns());
            assertEquals(8, s.loop().conversation().size(), "turn 3's four messages are gone");

            Checkpoints.Restore r = s.loop().rewind(0, false);
            assertTrue(r.done(), r.message());
            assertEquals(List.of(1, 2), r.turns());
            assertEquals("v0", Files.readString(ws.resolve("a.txt")));
            assertFalse(Files.exists(ws.resolve("b.txt")));
            assertTrue(s.loop().conversation().isEmpty());
            assertEquals("Nothing to undo.", s.loop().undo(false).message());
        }
    }

    /** Spec 057: opening a session removes this project's checkpoints older than files.checkpointDays, with an event. */
    @Test
    void oldCheckpointsArePrunedAtStart() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path home = tmp.resolve("home");
        Path old = Checkpoints.rootFor(home, ws.toRealPath(), "s-old");
        Files.createDirectories(old.resolve("1/blobs"));
        Files.writeString(old.resolve("1/blobs/1"), "saved");
        var t = java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(8)));
        try (var walk = Files.walk(old)) {
            for (Path x : walk.toList()) {
                Files.setLastModifiedTime(x, t);
            }
        }
        Path log;
        try (Session s = Session.open(home, ws, Settings.parse("{\"files\": {\"checkpointDays\": 7}}", "t"), Session.Access.gate(PermissionGate.allowAll()),
                (bus, turns) -> ReplayProvider.ofReplies(List.of("hi"), bus, turns))) {
            log = s.logPath();
        }
        assertFalse(Files.exists(old));
        var ev = dev.jharness.events.SessionLog.read(log).stream().filter(e -> e.get("type").asText().equals("checkpoints.pruned")).toList();
        assertEquals(1, ev.size());
        assertEquals(1, ev.getFirst().get("sessions").asInt());
        assertEquals(5, ev.getFirst().get("bytes").asInt());
        assertEquals(7, ev.getFirst().get("days").asInt());
    }

    @Test
    void userEditBlocksUndo() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("a.txt"), "v0");
        try (Session s = Session.open(tmp.resolve("home"), ws, Settings.defaults(), Session.Access.gate(PermissionGate.allowAll()),
                (bus, turns) -> ReplayProvider.ofReplies(List.of("```js\napi.writeFile('a.txt', 'v1')\n```", "done"), bus, turns))) {
            s.loop().turn("one", d -> {});
            Files.writeString(ws.resolve("a.txt"), "mine");
            Checkpoints.Restore u = s.loop().undo(false);
            assertFalse(u.done());
            assertEquals(List.of(1), s.loop().turns(), "turn kept");
            assertEquals(2 + 2, s.loop().conversation().size());
            assertTrue(s.loop().undo(true).done());
            assertEquals("v0", Files.readString(ws.resolve("a.txt")));
        }
    }
}
