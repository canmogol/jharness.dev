package dev.jharness.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.Settings;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.PermissionGate;

class MemoryTest {

    @TempDir
    Path tmp;

    @Test
    void bothJharnessMdFilesReachTheSystemPrompt() throws Exception {
        Path home = tmp.resolve("home");
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.createDirectories(home.resolve(".jharness"));
        Files.writeString(ws.resolve("JHARNESS.md"), "Build with mvn -q verify.");
        Files.writeString(home.resolve(".jharness/JHARNESS.md"), "Keep answers short.");
        ReplayProvider[] r = new ReplayProvider[1];
        try (Session s = Session.open(home, ws, Settings.defaults(), Session.Access.gate(PermissionGate.allowAll()), (bus, turns) -> {
            r[0] = ReplayProvider.ofReplies(List.of("ok"), bus, turns);
            return r[0];
        })) {
            s.loop().turn("hi", d -> {});
        }
        String system = r[0].requests().getFirst().system();
        assertTrue(system.contains("# Project instructions (JHARNESS.md)\n\nBuild with mvn -q verify."), system);
        assertTrue(system.contains("Keep answers short."));
    }
}
