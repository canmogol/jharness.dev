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
                bus.publish(Origin.main(1), "model.delta", Json.object().put("text", "line " + i + " \"quoted\"\n"));
            }
            bus.publishGuest(Origin.main(1), "{\"type\":\"agent.done\"}");

            Path expected = home.resolve(".jharness/sessions").resolve(SessionLog.projectHash(ws)).resolve(bus.session() + ".jsonl");
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
            bus.publish(Origin.main(1), "model.delta", Json.object().put("text", "while interrupted"));
            Thread.interrupted();
            bus.publish(Origin.main(1), "model.delta", Json.object().put("text", "after"));
            assertEquals(2, SessionLog.read(log.path()).size());
        }
    }

    @Test
    void projectHashUsesRealPath() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path link = Files.createSymbolicLink(tmp.resolve("link"), ws);
        assertEquals(SessionLog.projectHash(ws), SessionLog.projectHash(link));
        assertEquals(16, SessionLog.projectHash(ws).length());
    }
}
