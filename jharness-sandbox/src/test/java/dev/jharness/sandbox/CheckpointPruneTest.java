package dev.jharness.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Spec 057: other sessions' checkpoints go after files.checkpointDays. */
class CheckpointPruneTest {

    @TempDir
    Path tmp;

    private Path session(Path home, Path ws, String id, Duration age) throws Exception {
        Path d = Checkpoints.rootFor(home, ws, id);
        Files.createDirectories(d.resolve("1/blobs"));
        Files.writeString(d.resolve("1/blobs/1"), "saved");
        Files.writeString(d.resolve("1/manifest.json"), "{}");
        FileTime t = FileTime.from(Instant.now().minus(age));
        try (var walk = Files.walk(d)) {
            for (Path x : walk.toList()) {
                Files.setLastModifiedTime(x, t);
            }
        }
        return d;
    }

    @Test
    void oldSessionsGoAndTheCurrentOneStays() throws Exception {
        Path home = tmp.resolve("home");
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path old = session(home, ws, "s-old", Duration.ofDays(31));
        Path recent = session(home, ws, "s-recent", Duration.ofDays(29));
        Path current = session(home, ws, "s-current", Duration.ofDays(90));
        // A session that was old but wrote one file yesterday is still in use.
        Path touched = session(home, ws, "s-touched", Duration.ofDays(60));
        Files.setLastModifiedTime(touched.resolve("1/blobs/1"), FileTime.from(Instant.now().minus(Duration.ofDays(1))));
        // Another project's old checkpoints are not this project's to remove.
        Path other = session(home, Files.createDirectories(tmp.resolve("other")), "s-other", Duration.ofDays(400));
        // A link in the project folder is never followed.
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("keep.txt"), "x");
        Files.setLastModifiedTime(outside, FileTime.from(Instant.now().minus(Duration.ofDays(100))));
        Files.createSymbolicLink(current.getParent().resolve("s-link"), outside);

        assertEquals(new Checkpoints.Pruned(0, 0), Checkpoints.prune(home, ws, "s-current", 0, Instant.now()), "0 keeps them for ever");
        assertTrue(Files.exists(old));

        Checkpoints.Pruned p = Checkpoints.prune(home, ws, "s-current", 30, Instant.now());
        assertEquals(new Checkpoints.Pruned(1, 7), p, "s-old: 5 bytes of blob and 2 of manifest");
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(recent) && Files.exists(current) && Files.exists(touched) && Files.exists(other));
        assertTrue(Files.exists(outside.resolve("keep.txt")));
        assertEquals(new Checkpoints.Pruned(0, 0), Checkpoints.prune(home, Files.createDirectories(tmp.resolve("fresh")), "s-x", 30, Instant.now()),
                "no checkpoints yet");
    }
}
