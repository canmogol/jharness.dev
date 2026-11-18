package dev.jharness.testkit;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jharness.agent.Session;
import dev.jharness.agent.TurnResult;
import dev.jharness.config.Settings;
import dev.jharness.llm.ReplayProvider;
import dev.jharness.security.PermissionGate;

/** Esc during a turn (spec 007, AC-06). */
class InterruptTest {

    @TempDir
    Path tmp;

    private static TurnResult interruptAfter(Session s, String prompt, long ms) throws Exception {
        CompletableFuture<TurnResult> f = CompletableFuture.supplyAsync(() -> s.loop().turn(prompt, d -> {}));
        Thread.sleep(ms);
        long t0 = System.nanoTime();
        s.loop().interrupt();
        TurnResult r = f.get(5, TimeUnit.SECONDS);
        assertTrue((System.nanoTime() - t0) / 1e9 < 1.0, "the turn took more than a second to stop");
        return r;
    }

    @Test
    void stopsARunningBlockAndTheSessionGoesOn() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        try (Session s = Session.open(tmp.resolve("home"), ws, Settings.defaults(), Session.Access.gate(PermissionGate.allowAll()),
                (bus, turns) -> ReplayProvider.ofReplies(List.of("```js\nwhile (true) {}\n```", "still here"), bus, turns))) {
            TurnResult r = interruptAfter(s, "spin", 500);
            assertEquals("interrupted", r.stopReason());
            assertTrue(s.loop().conversation().getLast().content().contains("stopped this code with Esc"));
            TurnResult next = s.loop().turn("again", d -> {});
            assertEquals("done", next.stopReason());
            assertEquals("still here", next.reply());
        }
    }

    @Test
    void stopsAHangingModelStream() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        try (StubModelServer stub = new StubModelServer()) {
            stub.enqueue(StubModelServer.Reply.hanging(
                    "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"thinking\"},\"finish_reason\":null}]}\n\n", 30_000));
            Settings settings = Settings.parse("{\"model\":\"o\",\"models\":{\"o\":{\"provider\":\"openai-compatible\",\"baseUrl\":\""
                    + stub.baseUrl() + "/v1\",\"model\":\"m\"}}}", "t");
            try (Session s = Session.open(tmp.resolve("home"), ws, settings, null, Session.Access.gate(PermissionGate.allowAll()))) {
                TurnResult r = interruptAfter(s, "hi", 700);
                assertEquals("interrupted", r.stopReason(), String.valueOf(r));
            }
        }
    }
}
