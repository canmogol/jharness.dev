package dev.jharness.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.events.Event;
import dev.jharness.events.EventBus;
import dev.jharness.events.Origin;

class CheckpointsTest {

    @TempDir
    Path tmp;
    Path ws;
    Path root;
    Checkpoints cp;
    final List<Event> events = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        ws = Files.createDirectories(tmp.resolve("ws"));
        EventBus bus = new EventBus("s-cp");
        bus.subscribe(events::add);
        root = Checkpoints.rootFor(tmp.resolve("home"), ws, "s-cp");
        cp = new Checkpoints(root, bus, () -> Origin.main(1));
    }

    /** What HostApi does around a write. */
    private void agentWrites(int turn, String name, String content) throws Exception {
        Path f = ws.resolve(name);
        cp.beforeChange(turn, f, name);
        Files.writeString(f, content);
        cp.afterChange(turn, f);
    }

    @Test
    void undoRestoresTheLastTurnAndRemovesCreatedFiles() throws Exception {
        Files.writeString(ws.resolve("a.txt"), "v0");
        agentWrites(1, "a.txt", "v1");
        agentWrites(2, "a.txt", "v2");
        agentWrites(2, "a.txt", "v2b");
        agentWrites(2, "new.txt", "created");
        Checkpoints.Restore r = cp.restore(1, false);
        assertTrue(r.done(), r.message());
        assertEquals(List.of("a.txt", "new.txt"), r.restored());
        assertEquals("v1", Files.readString(ws.resolve("a.txt")));
        assertFalse(Files.exists(ws.resolve("new.txt")));
        assertEquals(List.of(1), cp.turns());
        assertTrue(events.stream().anyMatch(e -> e.type().equals("checkpoint.restored")));
    }

    @Test
    void rewindRestoresSeveralTurns() throws Exception {
        Files.writeString(ws.resolve("a.txt"), "v0");
        agentWrites(1, "a.txt", "v1");
        agentWrites(2, "b.txt", "b");
        agentWrites(3, "a.txt", "v3");
        Checkpoints.Restore r = cp.restore(0, false);
        assertTrue(r.done());
        assertEquals(List.of(1, 2, 3), r.turns());
        assertEquals("v0", Files.readString(ws.resolve("a.txt")));
        assertFalse(Files.exists(ws.resolve("b.txt")));
        assertEquals("Nothing to undo.", cp.restore(0, false).message());
    }

    @Test
    void userEditsBlockUndoUnlessForced() throws Exception {
        Files.writeString(ws.resolve("a.txt"), "v0");
        agentWrites(1, "a.txt", "v1");
        Files.writeString(ws.resolve("a.txt"), "user edit");
        Checkpoints.Restore r = cp.restore(0, false);
        assertFalse(r.done());
        assertEquals(List.of("a.txt"), r.conflicts());
        assertTrue(r.message().contains("--force"));
        assertEquals("user edit", Files.readString(ws.resolve("a.txt")), "nothing changed");
        assertTrue(cp.restore(0, true).done());
        assertEquals("v0", Files.readString(ws.resolve("a.txt")));
    }

    @Test
    void gitIsNotUndoneAndSaysSo() throws Exception {
        agentWrites(1, "a.txt", "x");
        cp.touchedGit(1, "commit");
        Checkpoints.Restore r = cp.restore(0, false);
        assertTrue(r.message().contains("Git state was not undone") && r.message().contains("commit"), r.message());
    }

    @Test
    void largeFilesAreReportedNotSaved() throws Exception {
        Path big = ws.resolve("big.bin");
        Files.write(big, new byte[(int) Checkpoints.MAX_BYTES + 1]);
        cp.beforeChange(1, big, "big.bin");
        Files.writeString(big, "small now");
        cp.afterChange(1, big);
        Checkpoints.Restore r = cp.restore(0, false);
        assertEquals(List.of("big.bin"), r.notRestorable());
        assertTrue(r.message().contains("over 10 MB"));
    }

    @Test
    void storageIsPrivateAndDiffShowsTheTurn() throws Exception {
        Files.writeString(ws.resolve("a.txt"), "one\ntwo\n");
        agentWrites(1, "a.txt", "one\nTWO\n");
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("1"))));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("1/blobs/1"))));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("1/manifest.json"))));
        assertEquals("a.txt", Checkpoints.manifest(root, 1).get("files").get(0).get("path").asText());
        String d = cp.diff(1);
        assertTrue(d.contains("-two") && d.contains("+TWO") && d.contains("a/a.txt"), d);
    }
}
