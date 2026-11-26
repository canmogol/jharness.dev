package dev.jharness.cli;

import java.io.IOException;
import java.io.Writer;

import org.jline.reader.LineReader;

/**
 * The terminal writer under the TUI's output (spec 055). While a main turn runs in the background and the prompt reads,
 * {@link #above(boolean) above} is on: each thread's text is cut into whole lines, which go above the prompt through
 * {@link LineReader#printAbove(String)}; a partial line waits for its newline. Otherwise text goes straight to the terminal.
 */
final class AboveWriter extends Writer {

    private final Writer direct;
    private final LineReader reader;
    private volatile boolean above;
    private final ThreadLocal<StringBuilder> partial = ThreadLocal.withInitial(StringBuilder::new);

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

    @Override
    public void write(char[] buf, int off, int len) throws IOException {
        StringBuilder p = partial.get();
        if (!above) {
            if (!p.isEmpty()) {
                direct.write(p.toString());
                p.setLength(0);
            }
            direct.write(buf, off, len);
            return;
        }
        p.append(buf, off, len);
        int nl;
        while ((nl = p.indexOf("\n")) >= 0) {
            int end = nl > 0 && p.charAt(nl - 1) == '\r' ? nl - 1 : nl;
            String line = p.substring(0, end);
            p.delete(0, nl + 1);
            reader.printAbove(line);
        }
    }

    /** Ends this thread's partial line, if any, so it shows now. */
    void endLine() throws IOException {
        StringBuilder p = partial.get();
        if (p.isEmpty()) {
            return;
        }
        String line = p.toString();
        p.setLength(0);
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
        StringBuilder p = partial.get();
        if (!above && !p.isEmpty()) {
            direct.write(p.toString());
            p.setLength(0);
        }
        direct.flush();
    }

    @Override
    public void close() throws IOException {
        flush();
    }
}
