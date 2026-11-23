package dev.jharness.llm;

import java.time.Duration;

/** Waits before a retry (spec 020). Tests pass one that doesn't sleep; the default ends early on cancel. */
@FunctionalInterface
public interface RetryWait {

    /** @return false if the wait ended because the request was cancelled */
    boolean pause(Duration d) throws InterruptedException;
}
