package dev.jharness.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.config.Settings;
import dev.jharness.events.SessionLog;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.process.Executables;
import dev.jharness.security.PermissionGate;

/** Spec 013 AC-03, AC-05: {@code api.npm} through a whole session, in trusted and untrusted projects. */
class SessionBuildTest {

    @TempDir
    Path tmp;

    private List<JsonNode> session(boolean trusted) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve(trusted ? "trusted" : "untrusted"));
        Files.writeString(ws.resolve("package.json"), "{\"name\":\"t\",\"version\":\"1.0.0\",\"scripts\":{\"test\":\"node -e \\\"console.log('ran')\\\"\"}}");
        Path log;
        try (Session s = Session.open(tmp.resolve("home"), ws, Settings.defaults(), Session.Access.gate(PermissionGate.allowAll()).trusted(trusted),
                (bus, turns) -> ReplayProvider.ofReplies(List.of("```js\nconst r = JSON.parse(api.npm({op: 'test'}));\nr.exitCode + ' ' + r.output\n```",
                        "done"), bus, turns))) {
            s.loop().turn("run the tests", d -> {});
            log = s.logPath();
        }
        return SessionLog.read(log);
    }

    private static boolean sandboxAndNpm(List<JsonNode> events) {
        return events.stream().anyMatch(e -> e.get("type").asText().equals("sandbox.os") && e.get("available").asBoolean())
                && Executables.resolve("npm", Path.of("/nonexistent")).isPresent();
    }

    @Test
    void trustedProjectsRunNpmInTheSandbox() throws Exception {
        List<JsonNode> events = session(true);
        assumeTrue(sandboxAndNpm(events), "needs the OS sandbox and npm");
        JsonNode started = events.stream().filter(e -> e.get("type").asText().equals("process.started")
                && e.get("executable").asText().equals("npm")).findFirst().orElseThrow();
        assertTrue(started.get("network").asBoolean(), started.toString());
        JsonNode done = events.stream().filter(e -> e.get("type").asText().equals("process.completed")
                && e.get("executable").asText().equals("npm")).findFirst().orElseThrow();
        assertEquals(0, done.get("exitCode").asInt(), done.toString());
        assertTrue(done.get("output").asText().contains("ran"), done.toString());
        assertTrue(events.stream().anyMatch(e -> e.get("type").asText().equals("permission.decided") && e.get("kind").asText().equals("build")
                && e.get("summary").asText().equals("npm test")), "approval asked as a build");
    }

    @Test
    void untrustedProjectsCantBuild() throws Exception {
        List<JsonNode> events = session(false);
        JsonNode call = events.stream().filter(e -> e.get("type").asText().equals("api.call") && e.get("api").asText().equals("npm"))
                .findFirst().orElseThrow();
        assertEquals("error", call.get("status").asText());
        assertTrue(call.get("error").asText().contains("trust this project first"), call.toString());
        assertTrue(events.stream().noneMatch(e -> e.get("type").asText().equals("process.started")
                && e.get("executable").asText().equals("npm")));
    }
}
