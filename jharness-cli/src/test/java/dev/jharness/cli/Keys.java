package dev.jharness.cli;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Typed input for a live TUI test (spec 055): strings are keys; a {@link BooleanSupplier} between them holds the rest
 * back until it is true, so a key comes after the turn it is meant for. The end of the script is the end of input.
 */
final class Keys extends InputStream {

    private final Deque<Object> script = new ArrayDeque<>();
    private byte[] current = new byte[0];
    private int pos;

    Keys(Object... parts) {
        for (Object p : parts) {
            script.add(p);
        }
    }

    /**
     * True once the TUI ran what came before and is idle again. The input is read ahead, so the TUI may not have
     * started the turn yet: after a short grace it waits until it has seen the TUI busy and idle again, or, for input
     * that starts no turn (or a turn shorter than one poll), until 3 s have passed with the TUI idle.
     */
    static BooleanSupplier settled(Supplier<Tui> tui) {
        long[] first = {0};
        boolean[] seenBusy = {false};
        return () -> {
            if (first[0] == 0) {
                first[0] = System.nanoTime();
            }
            Tui t = tui.get();
            if (t == null) {
                return false;
            }
            if (!t.idle()) {
                seenBusy[0] = true;
                return false;
            }
            long waited = System.nanoTime() - first[0];
            return waited > 300_000_000L && (seenBusy[0] || waited > 3_000_000_000L);
        };
    }

    /** True once the TUI holds a main-agent question. */
    static BooleanSupplier held(Supplier<Tui> tui) {
        return () -> tui.get() != null && tui.get().prompter().holding();
    }

    /** True after a pause, for keys meant for a prompt that is about to read. */
    static BooleanSupplier pause(long ms) {
        long[] first = {0};
        return () -> {
            if (first[0] == 0) {
                first[0] = System.nanoTime();
            }
            return System.nanoTime() - first[0] > ms * 1_000_000L;
        };
    }

    @Override
    public synchronized int read() {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public synchronized int read(byte[] b, int off, int len) {
        while (pos >= current.length) {
            Object next = script.poll();
            if (next == null) {
                return -1;
            }
            if (next instanceof BooleanSupplier wait) {
                long end = System.nanoTime() + 20_000_000_000L;
                while (!wait.getAsBoolean() && System.nanoTime() < end) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                }
                continue;
            }
            current = ((String) next).getBytes(StandardCharsets.UTF_8);
            pos = 0;
        }
        int n = Math.min(len, current.length - pos);
        System.arraycopy(current, pos, b, off, n);
        pos += n;
        return n;
    }
}
