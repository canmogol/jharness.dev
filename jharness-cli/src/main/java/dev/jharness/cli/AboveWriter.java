package dev.jharness.cli;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jline.reader.LineReader;

/**
 * The terminal writer under the TUI's output (spec 055). While a main turn runs in the background and the prompt reads,
 * {@link #above(boolean) above} is on: each thread's text is cut into whole lines, which go above the prompt through
 * {@link LineReader#printAbove(String)}; a partial line waits for its newline. Otherwise text goes straight to the terminal.
 *
 * <p>Spec 067: while a question is shown, {@link #hold()} keeps every other thread's text back, so nothing lands between
 * the question and its answer line; {@link #release()} prints it.
 */
final class AboveWriter extends Writer {

    private final Writer direct;
    private final LineReader reader;
    private volatile boolean above;
    private volatile Thread owner;
    private final Map<Thread, StringBuilder> partials = new ConcurrentHashMap<>();

    AboveWriter(Writer direct, LineReader reader) {
        this.direct = direct;
        this.reader = reader;
    }

    /** Turns line-by-line output above the prompt on or off. */
    void above(boolean on) {
        above = on;
    }

    boolean above() {
        return above;
    }

    private StringBuilder mine() {
        return partials.computeIfAbsent(Thread.currentThread(), t -> new StringBuilder());
    }

    /** Whether another thread holds the output, so this thread's text waits. */
    private boolean waits() {
        Thread o = owner;
        return o != null && o != Thread.currentThread();
    }

    @Override
    public void write(char[] buf, int off, int len) throws IOException {
        StringBuilder p = mine();
        synchronized (p) {
            if (waits()) {
                p.append(buf, off, len);
                return;
            }
            if (!above) {
                if (!p.isEmpty()) {
                    direct.write(p.toString());
                    p.setLength(0);
                }
                direct.write(buf, off, len);
                return;
            }
            p.append(buf, off, len);
            emitLines(p);
        }
    }

    /** Under {@code p}'s lock: prints its whole lines above the prompt, keeping the partial last one. */
    private void emitLines(StringBuilder p) {
        int nl;
        while ((nl = p.indexOf("\n")) >= 0) {
            int end = nl > 0 && p.charAt(nl - 1) == '\r' ? nl - 1 : nl;
            String line = p.substring(0, end);
            p.delete(0, nl + 1);
            reader.printAbove(line);
        }
    }

    /** Spec 067: from now until {@link #release()}, only this thread's text shows; the others' waits. */
    void hold() {
        owner = Thread.currentThread();
    }

    /** Ends {@link #hold()} and prints what the other threads wrote meanwhile: whole lines, or everything when not above. */
    void release() throws IOException {
        owner = null;
        for (var e : partials.entrySet()) {
            if (e.getKey() == Thread.currentThread()) {
                continue;
            }
            StringBuilder p = e.getValue();
            synchronized (p) {
                if (p.isEmpty()) {
                    if (!e.getKey().isAlive()) {
                        partials.remove(e.getKey(), p);
                    }
                    continue;
                }
                if (above) {
                    emitLines(p);
                    if (!e.getKey().isAlive() && !p.isEmpty()) {
                        reader.printAbove(p.toString());
                        p.setLength(0);
                    }
                } else {
                    direct.write(p.toString());
                    p.setLength(0);
                }
            }
        }
        direct.flush();
    }

    /** Ends this thread's partial line, if any, so it shows now. */
    void endLine() throws IOException {
        StringBuilder p = mine();
        String line;
        synchronized (p) {
            if (p.isEmpty() || waits()) {
                return;
            }
            line = p.toString();
            p.setLength(0);
        }
        if (above) {
            reader.printAbove(line);
        } else {
            direct.write(line);
            direct.write(System.lineSeparator());
            direct.flush();
        }
    }

    @Override
    public void flush() throws IOException {
        StringBuilder p = mine();
        synchronized (p) {
            if (waits()) {
                return;
            }
            if (!above && !p.isEmpty()) {
                direct.write(p.toString());
                p.setLength(0);
            }
        }
        direct.flush();
    }

    @Override
    public void close() throws IOException {
        flush();
    }
}
