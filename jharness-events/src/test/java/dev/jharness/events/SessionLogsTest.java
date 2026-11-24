package dev.jharness.events;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Spec 031 AC-03, AC-04: logs under the readable folder, newest first, and old names left alone. */
class SessionLogsTest {

    @TempDir
    Path tmp;

    @Test
    void listsTheNewLayoutNewestFirstAndIgnoresTheOld() throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path ws = Files.createDirectories(tmp.resolve("My Project")).toRealPath();
        String older = EventBus.newSessionId(Instant.parse("2026-11-24T09:00:00Z"));
        String newer = EventBus.newSessionId(Instant.parse("2026-11-24T10:52:26Z"));
        for (String id : List.of(older, newer)) {
            try (SessionLog log = SessionLog.open(home, ws, id)) {
                assertEquals(home.resolve(".jharness/sessions").resolve(SessionLog.projectFolder(ws)).resolve(id + ".jsonl"), log.path());
            }
        }
        assertTrue(SessionLog.projectFolder(ws).contains("-my-project-"), SessionLog.projectFolder(ws));
        Path dir = SessionLogs.root(home).resolve(SessionLog.projectFolder(ws));
        Files.writeString(dir.resolve("s-mwuebe36-7a5e1613eab1.jsonl"), "{}\n");
        Path old = Files.createDirectories(SessionLogs.root(home).resolve("c8167f02dd776b79"));
        Files.writeString(old.resolve("s-mwuebe36-7a5e1613eab1.jsonl"), "{}\n");
        List<SessionLogs.Entry> here = SessionLogs.project(home, ws);
        assertEquals(List.of(newer, older), here.stream().map(SessionLogs.Entry::id).toList(), "old ids are ignored");
        assertEquals(Instant.parse("2026-11-24T10:52:26Z"), here.getFirst().started());
        assertEquals(2, SessionLogs.all(home).size(), "old folders are ignored");
        assertTrue(Files.exists(old), "and not removed");
        assertEquals(dir.resolve(newer + ".jsonl"), SessionLogs.resolve(home, ws, "last"));
        assertEquals(dir.resolve(older + ".jsonl"), SessionLogs.resolve(home, ws, older.substring(0, 16)));
    }

    @Test
    void sessionsStartedInTheSameSecondKeepTheirOrder() throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path ws = Files.createDirectories(tmp.resolve("ws")).toRealPath();
        Instant at = Instant.parse("2026-11-24T10:00:00Z");
        String a = "s-2026-11-24T100000Z-ffff";
        String b = "s-2026-11-24T100000Z-0000";
        try (SessionLog log = SessionLog.open(home, ws, a)) {
            Files.setLastModifiedTime(log.path(), java.nio.file.attribute.FileTime.from(at));
        }
        try (SessionLog log = SessionLog.open(home, ws, b)) {
            Files.setLastModifiedTime(log.path(), java.nio.file.attribute.FileTime.from(at.plusMillis(400)));
        }
        assertEquals(b, SessionLogs.project(home, ws).getFirst().id(), "the one written last is last");
    }
}
