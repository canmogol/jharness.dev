package dev.jharness.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.Settings;
import dev.jharness.events.SessionLog;
import dev.jharness.events.SessionLogs;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.Prompter;

/** Spec 031 AC-03: the session log, saved permissions and checkpoints share one readable folder name. */
class SessionLayoutTest {

    @TempDir
    Path tmp;

    @Test
    void sessionsProjectsAndCheckpointsUseTheSameFolder() throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path ws = Files.createDirectories(tmp.resolve("Demo App")).toRealPath();
        Files.writeString(ws.resolve("a.txt"), "old");
        String session;
        Path log;
        try (Session s = Session.open(home, ws, Settings.defaults(), Session.Access.prompter(op -> Prompter.Answer.ALWAYS),
                (bus, turns) -> ReplayProvider.ofReplies(List.of("```js\napi.writeFile('a.txt', 'new')\n```", "done"), bus, turns))) {
            s.loop().turn("change it", d -> {});
            session = s.bus().session();
            log = s.logPath();
        }
        String folder = SessionLog.projectFolder(ws);
        assertTrue(folder.matches(".*-demo-app-[0-9a-f]{7}"), folder);
        assertTrue(SessionLogs.ID.matcher(session).matches(), session);
        assertEquals(home.resolve(".jharness/sessions").resolve(folder).resolve(session + ".jsonl"), log);
        assertTrue(Files.isRegularFile(home.resolve(".jharness/projects").resolve(folder).resolve("permissions.json")), "the saved \"always\"");
        assertTrue(Files.isDirectory(home.resolve(".jharness/checkpoints").resolve(folder).resolve(session)), "the checkpoint of the write");
    }
}
