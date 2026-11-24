package dev.jharness.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

import dev.jharness.security.Operation;
import dev.jharness.security.Prompter;

/** Asks for approval in the terminal (AC-04): {@code y} once, {@code a} always for this project, {@code n} no. */
final class TerminalPrompter implements Prompter {

    private final LineReader reader;
    private final Styler styler;
    /** Escaping writer for text and the raw writer for styled output (spec 022). */
    private final java.io.PrintWriter out;
    private final java.io.PrintWriter raw;
    volatile EscWatcher watcher;
    /** The path whose diff the last question showed, so the TUI doesn't print it twice. */
    volatile String shownPath;
    /** True while no turn runs: no one is waiting for a question then, so it is answered UNATTENDED (spec 027). */
    volatile boolean idle = true;

    TerminalPrompter(LineReader reader, Styler styler, java.io.PrintWriter out, java.io.PrintWriter raw) {
        this.reader = reader;
        this.styler = styler;
        this.out = out;
        this.raw = raw;
    }

    /** One question at a time: a turn's own questions and the proxy's can come from different threads. */
    @Override
    public synchronized Answer ask(Operation op) {
        if (idle) {
            return Answer.UNATTENDED;
        }
        EscWatcher w = watcher;
        if (w != null) {
            w.pause();
        }
        try {
            return question(op);
        } finally {
            if (w != null) {
                w.resume();
            }
        }
    }

    private Answer question(Operation op) {
        out.println();
        out.flush();
        String diff = op.detail().path("diff").asText("");
        if (!diff.isEmpty()) {
            raw.print(styler.diff(diff));
            raw.flush();
            shownPath = op.detail().path("path").asText(null);
        }
        out.println("Allow " + op.api() + ": " + op.summary() + "?");
        out.flush();
        while (true) {
            String line;
            try {
                line = reader.readLine("[y] once  [a] always for this project  [n] no: ");
            } catch (UserInterruptException | EndOfFileException e) {
                return Answer.DENY;
            }
            switch (line.strip().toLowerCase()) {
                case "y", "yes" -> {
                    return Answer.ONCE;
                }
                case "a", "always" -> {
                    return Answer.ALWAYS;
                }
                case "n", "no" -> {
                    return Answer.DENY;
                }
                default -> {
                    out.println("Please answer y, a or n.");
                    out.flush();
                }
            }
        }
    }
}
