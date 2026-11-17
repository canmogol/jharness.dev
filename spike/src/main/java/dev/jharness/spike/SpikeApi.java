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
}
