package dev.jharness.testkit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.agent.Session;
import dev.jharness.agent.TurnResult;
import dev.jharness.config.Settings;
import dev.jharness.security.PermissionGate;

/**
 * The P1 gate scenario against real models (AC-09). Skipped unless {@code OLLAMA_URL} (model from
 * {@code OLLAMA_MODEL}, default {@code qwen2.5-coder:7b}) or {@code ANTHROPIC_API_KEY} (model from
 * {@code ANTHROPIC_MODEL}) is set.
 */
class LiveLoopTest {

    @TempDir
    Path tmp;

    private void scenario(String settingsJson) throws Exception {
        Path repo = SampleRepo.create(tmp.resolve("repo"));
        Settings s = Settings.parse(settingsJson, "live");
        TurnResult r;
        try (Session session = Session.open(tmp.resolve("home"), repo, s, null, Session.Access.gate(PermissionGate.allowAll()))) {
            r = session.loop().turn(SampleRepo.TASK, System.out::print);
            System.out.println("\n[live] stop=" + r.stopReason() + " steps=" + r.steps() + " log=" + session.logPath());
        }
        assertEquals("done", r.stopReason(), r.toString());
        assertTrue(SampleRepo.fixed(repo), "the bug is not fixed");
        assertNotEquals("Initial commit", SampleRepo.log(repo).getFirst(), "no commit was made");
    }

    @Test
    void ollama() throws Exception {
        String url = System.getenv("OLLAMA_URL");
        assumeTrue(url != null && !url.isBlank(), "OLLAMA_URL not set");
        String model = System.getenv().getOrDefault("OLLAMA_MODEL", "qwen2.5-coder:7b");
        scenario("{\"model\":\"o\",\"models\":{\"o\":{\"provider\":\"openai-compatible\",\"baseUrl\":\"" + url + "\",\"model\":\"" + model
                + "\"}},\"agent\":{\"maxSteps\":15}}");
    }

    @Test
    void claude() throws Exception {
        assumeTrue(System.getenv("ANTHROPIC_API_KEY") != null, "ANTHROPIC_API_KEY not set");
        String model = System.getenv().getOrDefault("ANTHROPIC_MODEL", "claude-sonnet-5-5");
        scenario("{\"model\":\"c\",\"models\":{\"c\":{\"provider\":\"anthropic\",\"model\":\"" + model + "\"}},\"agent\":{\"maxSteps\":15}}");
    }
}
