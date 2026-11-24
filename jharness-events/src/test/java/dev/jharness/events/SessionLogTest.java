package dev.jharness.events;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionLogTest {

    @TempDir
    Path tmp;

    @Test
    void appendsEveryEventAndReadsBackInOrder() throws Exception {
        Path home = tmp.resolve("home");
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        EventBus bus = new EventBus(EventBus.newSessionId());
        List<Event> published = new ArrayList<>();
        try (SessionLog log = SessionLog.open(home, ws, bus.session())) {
            bus.subscribe(log);
            bus.subscribe(published::add);
            bus.publish(Origin.main(0), "session.started", Json.object().put("workspace", ws.toString()));
            for (int i = 0; i < 50; i++) {
                bus.publish(Origin.main(1), "api.call", Json.object().put("api", "readFile").put("status", "ok").put("summary", "line " + i + " \"quoted\"\n"));
            }
            bus.publishGuest(Origin.main(1), "{\"type\":\"agent.done\"}");

            Path expected = home.resolve(".jharness/sessions").resolve(SessionLog.projectFolder(ws)).resolve(bus.session() + ".jsonl");
            assertEquals(expected, log.path());
            var back = SessionLog.read(log.path());
            assertEquals(published.size(), back.size());
            for (int i = 0; i < back.size(); i++) {
                assertEquals(published.get(i).toJson(), back.get(i));
            }
        }
    }

    @Test
    void foldersAre0700AndFileIs0600() throws Exception {
        Path home = tmp.resolve("home");
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        try (SessionLog log = SessionLog.open(home, ws, "s-perm")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(log.path())));
            for (Path p = log.path().getParent(); !p.equals(home); p = p.getParent()) {
                assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(p)), p.toString());
            }
        }
    }

    @Test
    void anInterruptedThreadDoesNotCloseTheLog() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        EventBus bus = new EventBus("s-int");
        try (SessionLog log = SessionLog.open(tmp.resolve("home"), ws, bus.session())) {
            bus.subscribe(log);
            Thread.currentThread().interrupt();
            bus.publish(Origin.main(1), "turn.started", Json.object().put("prompt", "while interrupted"));
            Thread.interrupted();
            bus.publish(Origin.main(1), "turn.started", Json.object().put("prompt", "after"));
            assertEquals(2, SessionLog.read(log.path()).size());
        }
    }

    /** Spec 031 AC-01, AC-06: the folder is the real path as a slug plus 7 hex characters. */
    @Test
    void projectFolderIsAReadableSlugOfTheRealPath() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path link = Files.createSymbolicLink(tmp.resolve("link"), ws);
        assertEquals(SessionLog.projectFolder(ws), SessionLog.projectFolder(link), "the real path");
        String f = SessionLog.folderFor("/Users/xyz/projects/jharness.dev");
        assertTrue(f.matches("users-xyz-projects-jharness\\.dev-[0-9a-f]{7}"), f);
        assertTrue(SessionLog.FOLDER.matcher(f).matches());
        assertTrue(SessionLog.folderFor("/home/dev/My Project (2)/ünïcode").matches("home-dev-my-project-2-n-code-[0-9a-f]{7}"),
                SessionLog.folderFor("/home/dev/My Project (2)/ünïcode"));
        assertTrue(SessionLog.folderFor("/").matches("root-[0-9a-f]{7}"));
        assertTrue(SessionLog.folderFor("/.hidden/-x-/").matches("hidden-x-[0-9a-f]{7}"), SessionLog.folderFor("/.hidden/-x-/"));
        String longOne = SessionLog.folderFor("/" + "abcdefghij/".repeat(20) + "my-app");
        assertTrue(longOne.length() <= 80 + 8 && SessionLog.FOLDER.matcher(longOne).matches(), longOne);
        assertTrue(longOne.matches("[a-z-]*-my-app-[0-9a-f]{7}") && !longOne.startsWith("-"), "a long path keeps its end: " + longOne);
        assertNotEquals(SessionLog.folderFor("/a/b"), SessionLog.folderFor("/a-b"), "same slug, different hash");
        assertTrue(SessionLog.folderFor("/a/b").startsWith("a-b-") && SessionLog.folderFor("/a-b").startsWith("a-b-"));
        assertEquals(SessionLog.folderFor("/a/b"), SessionLog.folderFor("/a/b"), "stable");
    }

    /** Spec 031 AC-02. */
    @Test
    void sessionIdsAreTheStartTimeAndFourHexCharacters() {
        String id = EventBus.newSessionId(java.time.Instant.parse("2026-11-24T10:52:26.789Z"));
        assertTrue(id.matches("s-2026-11-24T105226Z-[0-9a-f]{4}"), id);
        assertTrue(SessionLogs.ID.matcher(EventBus.newSessionId()).matches());
        assertTrue(SessionLogs.ARG.matcher(id).matches());
        assertTrue(EventBus.newSessionId(java.time.Instant.parse("2026-11-24T10:52:26Z"))
                .compareTo(EventBus.newSessionId(java.time.Instant.parse("2026-12-01T00:00:00Z"))) < 0, "ids sort by time");
    }

    @Test
    void streamChunksStayOffTheLog() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        EventBus bus = new EventBus("s-live");
        List<Event> live = new ArrayList<>();
        try (SessionLog log = SessionLog.open(tmp.resolve("home"), ws, bus.session())) {
            bus.subscribe(log);
            bus.subscribe(live::add);
            bus.publish(Origin.main(1), "model.request", Json.object().put("provider", "p").put("model", "m"));
            bus.publish(Origin.main(1), "model.thinking_delta", Json.object().put("text", "hmm"));
            bus.publish(Origin.main(1), "model.delta", Json.object().put("text", "Hi"));
            bus.publish(Origin.main(1), "model.completed", Json.object().put("provider", "p").put("model", "m").put("stopReason", "stop")
                    .put("text", "Hi").put("thinking", "hmm"));
            assertEquals(4, live.size(), "live subscribers see every event");
            var back = SessionLog.read(log.path());
            assertEquals(List.of("model.request", "model.completed"), back.stream().map(n -> n.get("type").asText()).toList());
            assertEquals("Hi", back.getLast().get("text").asText());
        }
    }
}
