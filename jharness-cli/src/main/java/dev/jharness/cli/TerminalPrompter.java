package dev.jharness.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

import dev.jharness.security.Operation;
import dev.jharness.security.Prompter;

/** Asks for approval in the terminal (AC-04): {@code y} once, {@code a} always for this project, {@code n} no. */
final class TerminalPrompter implements Prompter {

    private final LineReader reader;

    TerminalPrompter(LineReader reader) {
        this.reader = reader;
    }

    @Override
    public Answer ask(Operation op) {
        var w = reader.getTerminal().writer();
        w.println();
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
