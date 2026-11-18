package dev.jharness.spike;

import org.graalvm.polyglot.HostAccess;

/**
 * The only host object the guest sees, bound as {@code api}. Strings and primitives only
 * (constitution Article VI). Methods are listed in reachability-metadata.json for Native Image.
 */
public final class SpikeApi {

    @HostAccess.Export
    public String echo(String value) {
        return value;
    }

    /** Blocks the guest thread inside a host call; used by the wall-clock and CPU-time probes. */
    @HostAccess.Export
    public void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    /** Burns host CPU inside a host call; used by the CPU-time probe (AC-14). */
    @HostAccess.Export
    public void busy(long millis) {
        long end = System.nanoTime() + millis * 1_000_000L;
        long x = 0;
        while (System.nanoTime() < end) {
            x = x * 31 + 7;
        }
        sink = x;
    }

    private volatile long sink;
}
