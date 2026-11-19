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
    volatile EscWatcher watcher;
    /** The path whose diff the last question showed, so the TUI doesn't print it twice. */
    volatile String shownPath;

    TerminalPrompter(LineReader reader, Styler styler) {
        this.reader = reader;
        this.styler = styler;
    }

    @Override
    public Answer ask(Operation op) {
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
        var w = reader.getTerminal().writer();
        w.println();
        String diff = op.detail().path("diff").asText("");
        if (!diff.isEmpty()) {
            w.print(styler.diff(diff));
            shownPath = op.detail().path("path").asText(null);
        }
        w.println("Allow " + op.api() + ": " + op.summary() + "?");
        w.flush();
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
                default -> reader.getTerminal().writer().println("Please answer y, a or n.");
            }
        }
    }
}
