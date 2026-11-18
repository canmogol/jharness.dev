package dev.jharness.sandbox;

/**
 * Wall-clock time of a run, minus time spent waiting for permission decisions (D-003). Pauses may
 * nest; only the outermost counts.
 */
public final class RunClock {

    private final long start = System.nanoTime();
    private long paused;
    private long pauseStart;
    private int depth;

    public synchronized void pause() {
        if (depth++ == 0) {
            pauseStart = System.nanoTime();
        }
    }

    public synchronized void resume() {
        if (depth > 0 && --depth == 0) {
            paused += System.nanoTime() - pauseStart;
        }
    }

    public synchronized boolean isPaused() {
        return depth > 0;
    }

    /** Running time in nanoseconds, not counting pauses. */
    public synchronized long activeNanos() {
        long now = System.nanoTime();
        long p = paused + (depth > 0 ? now - pauseStart : 0);
        return now - start - p;
    }
}
