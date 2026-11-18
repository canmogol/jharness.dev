package dev.jharness.testkit;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.agent.Session;
import dev.jharness.agent.TurnResult;
import dev.jharness.config.Settings;
import dev.jharness.events.SessionLog;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.PermissionGate;

/** The P1 gate scenario with recorded replies (AC-08): search, read, fix, commit, finish. */
class GateScenarioTest {

    @TempDir
    Path tmp;

    @Test
    void fixesTheSeededBugAndCommits() throws Exception {
        Path repo = SampleRepo.create(tmp.resolve("repo"));
        ReplayProvider[] replay = new ReplayProvider[1];
        TurnResult r;
        Path log;
        // Permissions arrive in the next spec; this scenario approves everything.
        try (Session s = Session.open(tmp.resolve("home"), repo, Settings.defaults(), PermissionGate.allowAll(), (bus, turns) -> {
            replay[0] = ReplayProvider.ofReplies(SampleRepo.gateReplies(), bus, turns);
            return replay[0];
        })) {
            r = s.loop().turn(SampleRepo.TASK, d -> {});
            log = s.logPath();
            List<String> results = replay[0].requests().stream().skip(1).map(q -> q.messages().getLast().content()).toList();
            assertTrue(results.get(0).contains(SampleRepo.CALCULATOR), results.get(0));
            assertTrue(results.get(1).contains(SampleRepo.BUGGY_LINE));
            assertTrue(results.get(2).contains("+        " + SampleRepo.FIXED_LINE), results.get(2));
            assertTrue(results.get(3).contains("committed"), results.get(3));
        }
        assertEquals("done", r.stopReason());
        assertEquals(4, r.steps());
        assertTrue(SampleRepo.fixed(repo));
        assertEquals("Fix Calculator.add to add", SampleRepo.log(repo).getFirst());
        var events = SessionLog.read(log);
        assertTrue(events.stream().anyMatch(e -> e.get("type").asText().equals("process.completed")
                && e.toString().contains("Fix Calculator.add")), "the commit is in the session log");
    }
}
