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
    /** How many turns or answers keep the prompter from being idle; see {@link #enter()}. */
    private final java.util.concurrent.atomic.AtomicInteger users = new java.util.concurrent.atomic.AtomicInteger();
    /**
     * True while a main turn runs in the background (spec 055): its questions don't take the prompt. Each waits as a held
     * question until {@link #answerHeld()}, or until {@link #cancelHeld()} denies it.
     */
    volatile boolean hold;
    /** Told when a question starts and stops waiting: {@code waiting(op)}, then {@code ended("asked"|"stopped")}. */
    volatile Holds holds = new Holds() {
    };
    private final java.util.List<Ticket> held = new java.util.ArrayList<>();

    /** Listens to held questions (spec 055). */
    interface Holds {
        default void waiting(Operation op) {
        }

        default void ended(Operation op, String outcome) {
        }
    }

    private static final class Ticket {
        final Operation op;
        boolean go;
        boolean cancelled;
        boolean done;

        Ticket(Operation op) {
            this.op = op;
        }
    }

    TerminalPrompter(LineReader reader, Styler styler, java.io.PrintWriter out, java.io.PrintWriter raw) {
        this.reader = reader;
        this.styler = styler;
        this.out = out;
        this.raw = raw;
    }

    /** A turn or an answer starts: questions are asked, not answered UNATTENDED. */
    void enter() {
        users.incrementAndGet();
        idle = false;
    }

    /** It ended; when none is left, the prompter is idle again. */
    void leave() {
        if (users.updateAndGet(n -> Math.max(0, n - 1)) == 0) {
            idle = true;
        }
    }

    @Override
    public Answer ask(Operation op) {
        if (idle) {
            return Answer.UNATTENDED;
        }
        // A background subagent's question that the user chose to answer (/answer, /switch) is asked at once.
        if (!hold || dev.jharness.security.Unattended.active()) {
            return askNow(op);
        }
        Ticket t = new Ticket(op);
        synchronized (held) {
            held.add(t);
        }
        boolean go = false;
        try {
            holds.waiting(op);
        } catch (RuntimeException e) {
            // A listener that failed must not leave the ticket behind.
            synchronized (held) {
                held.remove(t);
            }
            throw e;
        }
        synchronized (held) {
            try {
                while (!t.go && !t.cancelled) {
                    held.wait();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                t.cancelled = true;
            }
            held.remove(t);
            go = t.go && !t.cancelled;
        }
        try {
            holds.ended(op, go ? "asked" : "stopped");
            return go ? askNow(op) : Answer.DENY;
        } finally {
            synchronized (held) {
                t.done = true;
                held.notifyAll();
            }
        }
    }

    /** Whether a main-agent question is held (spec 055). */
    boolean holding() {
        synchronized (held) {
            return !held.isEmpty();
        }
    }

    /**
     * {@code /answer main} (spec 055): lets the oldest held question ask now, on its own thread, and returns once it was
     * answered or denied.
     *
     * @return false if none is held
     */
    boolean answerHeld() {
        synchronized (held) {
            if (held.isEmpty()) {
                return false;
            }
            Ticket t = held.getFirst();
            t.go = true;
            held.notifyAll();
            try {
                while (!t.done) {
                    held.wait();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return true;
        }
    }

    /** Esc or the end of the session (spec 055): every held question is denied. */
    void cancelHeld() {
        synchronized (held) {
            held.forEach(t -> t.cancelled = true);
            held.notifyAll();
        }
    }

    /** One question at a time: a turn's own questions and the proxy's can come from different threads. */
    private synchronized Answer askNow(Operation op) {
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
        if (op.api().equals("proposePlan")) {
            // Spec 036: the plan itself was shown from its event.
            while (true) {
                String line;
                try {
                    line = reader.readLine("Leave plan mode and carry out this plan? [y] yes  [n] no, keep planning: ");
                } catch (UserInterruptException | EndOfFileException e) {
                    return Answer.DENY;
                }
                switch (line.strip().toLowerCase()) {
                    case "y", "yes" -> {
                        return Answer.ONCE;
                    }
                    case "n", "no" -> {
                        return Answer.DENY;
                    }
                    default -> {
                        out.println("Please answer y or n.");
                        out.flush();
                    }
                }
            }
        }
        String review = op.detail().path("review").path("reason").asText("");
        if (!review.isEmpty()) {
            // Spec 038: the reviewer turned an allow into this question; say why.
            out.println(styler.dim("Reviewer: " + review));
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
