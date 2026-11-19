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
import dev.jharness.security.Prompter;

/** Spec 012 AC-05: the session probes the OS sandbox, reports it, and removes the children's temp folder. */
class SessionSandboxTest {

    @TempDir
    Path tmp;

    @Test
    void reportsTheSandboxAndCleansUp() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path log;
        try (Session s = Session.open(tmp.resolve("home"), ws, Settings.defaults(), Session.Access.prompter(Prompter.none()),
                (bus, turns) -> ReplayProvider.ofReplies(List.of(), bus, turns))) {
            log = s.logPath();
        }
        var os = SessionLog.read(log).stream().filter(e -> e.get("type").asText().equals("sandbox.os")).findFirst().orElseThrow();
        String expected = System.getProperty("os.name").toLowerCase().contains("mac") ? "seatbelt" : "bubblewrap";
        assertEquals(expected, os.get("mechanism").asText(), os.toString());
        assertTrue(os.has("available"));
        if (!os.get("available").asBoolean()) {
            assertFalse(os.get("reason").asText().isBlank(), os.toString());
        }
        assertFalse(Files.exists(Path.of(os.get("tmp").asText())), "the private temp folder is removed on close");
    }
}
