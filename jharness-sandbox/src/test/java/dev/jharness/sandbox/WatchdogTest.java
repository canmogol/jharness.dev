package dev.jharness.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

class WatchdogTest {

    @Test
    void escalatesWhenClosingDoesNotStopTheRun() throws Exception {
        List<String> steps = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        Watchdog w = new Watchdog(Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofMillis(100), steps::add);
        var never = new CompletableFuture<Void>();
        var outcome = w.await(never, new RunClock(), new Watchdog.Target() {
            public void closeContext() {
                calls.add("context");
            }

            public void closeEngine() {
                calls.add("engine");
            }
        });
        assertEquals(Watchdog.Outcome.FAILED, outcome);
        assertEquals(List.of("context_close", "engine_close", "failed"), steps);
        assertEquals(List.of("context", "engine"), calls);
    }

    @Test
    void stopsEscalatingWhenEngineCloseWorks() throws Exception {
        List<String> steps = new ArrayList<>();
        var run = new CompletableFuture<Void>();
        Watchdog w = new Watchdog(Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofSeconds(5), steps::add);
        var outcome = w.await(run, new RunClock(), new Watchdog.Target() {
            public void closeContext() {}

            public void closeEngine() {
                run.complete(null);
            }
        });
        assertEquals(Watchdog.Outcome.ENGINE_CLOSED, outcome);
        assertEquals(List.of("context_close", "engine_close"), steps);
    }

    @Test
    void finishedRunsAreNotTouched() throws Exception {
        List<String> steps = new ArrayList<>();
        var done = CompletableFuture.completedFuture(null);
        assertEquals(Watchdog.Outcome.FINISHED, new Watchdog(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), steps::add)
                .await(done, new RunClock(), null));
        assertTrue(steps.isEmpty());
    }

    @Test
    void pausedTimeIsNotCounted() throws Exception {
        RunClock c = new RunClock();
        c.pause();
        c.pause();
        Thread.sleep(150);
        c.resume();
        assertTrue(c.isPaused());
        c.resume();
        assertFalse(c.isPaused());
        assertTrue(c.activeNanos() < 100_000_000L, "paused time was counted: " + c.activeNanos());
    }
}
