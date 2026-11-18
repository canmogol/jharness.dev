package dev.jharness.sandbox;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Waits for a run and escalates when it overruns (D-037): at the wall clock it closes the context;
 * if the run is still going {@code engineCloseAfter} later it closes the engine; if it is still
 * going {@code failAfter} after that it gives up and reports a failure. Paused time (permission
 * decisions) does not count toward the wall clock.
 */
final class Watchdog {

    interface Target {
        void closeContext();

        void closeEngine();
    }

    enum Outcome { FINISHED, CONTEXT_CLOSED, ENGINE_CLOSED, FAILED }

    private static final long TICK_MS = 20;

    private final Duration wallClock;
    private final Duration engineCloseAfter;
    private final Duration failAfter;
    private final Consumer<String> onStep;

    Watchdog(Duration wallClock, Duration engineCloseAfter, Duration failAfter, Consumer<String> onStep) {
        this.wallClock = wallClock;
        this.engineCloseAfter = engineCloseAfter;
        this.failAfter = failAfter;
        this.onStep = onStep;
    }

    /** Blocks until the run ends or the watchdog gives up; returns how far it had to go. */
    Outcome await(Future<?> run, RunClock clock, Target target) throws InterruptedException {
        while (clock.activeNanos() < wallClock.toNanos()) {
            long left = wallClock.toNanos() - clock.activeNanos();
            if (done(run, Math.min(TICK_MS, Math.max(1, TimeUnit.NANOSECONDS.toMillis(left))))) {
                return Outcome.FINISHED;
            }
        }
        if (done(run, 0)) {
            return Outcome.FINISHED;
        }
        onStep.accept("context_close");
        target.closeContext();
        if (done(run, engineCloseAfter.toMillis())) {
            return Outcome.CONTEXT_CLOSED;
        }
        onStep.accept("engine_close");
        target.closeEngine();
        if (done(run, failAfter.toMillis())) {
            return Outcome.ENGINE_CLOSED;
        }
        onStep.accept("failed");
        return Outcome.FAILED;
    }

    private static boolean done(Future<?> f, long ms) throws InterruptedException {
        try {
            f.get(ms, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (ExecutionException | java.util.concurrent.CancellationException e) {
            return true;
        }
    }
}
